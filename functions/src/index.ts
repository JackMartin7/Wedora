import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/https";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/firestore";
import { logger } from "firebase-functions";

initializeApp();

/**
 * The one Firebase Auth UID allowed to disable or re-enable another
 * account.
 *
 * Kept in sync by hand with WedoraAdmin.UID (the Kotlin constant) and
 * firestore.rules' own isAdmin() — three places, one value, no way to
 * share a single source of truth across languages and deploy targets. If
 * this value ever needs to change, all three must be updated and
 * redeployed together.
 */
const ADMIN_UID = "QgOuA5no4jR6hsFoNp5UFWMceHk2";

/**
 * Shared by both callables below: rejects any caller who isn't ADMIN_UID,
 * then returns the validated targetUid string. Deliberately not trusting
 * the app's own client-side gating (ProfileActivity's hidden row,
 * AdminReportDetailActivity's own onCreate check) alone — those are UI
 * conveniences; this is the actual enforcement, since a modified client or
 * a raw call to either function's HTTPS endpoint would bypass every
 * client-side check entirely.
 */
function requireAdminAndTargetUid(request: CallableRequest): string {
  if (request.auth?.uid !== ADMIN_UID) {
    throw new HttpsError(
      "permission-denied",
      "Only the admin account may perform this action."
    );
  }

  const targetUid = request.data?.targetUid;
  if (typeof targetUid !== "string" || targetUid.length === 0) {
    throw new HttpsError("invalid-argument", "targetUid is required.");
  }
  return targetUid;
}

/**
 * Hard-disables a user's Firebase Auth account — called from
 * AdminReportDetailActivity's ban flow, after the Firestore-level ban
 * (isBanned/banReason on the user's profile, plus their pending reports
 * marked "banned") has already succeeded.
 *
 * That Firestore write alone doesn't stop anyone from signing in — it's
 * just data. This function is what actually enforces a ban:
 * updateUser({ disabled: true }) blocks every future sign-in attempt, and
 * revokeRefreshTokens invalidates any session the banned user already has
 * open on any device *right now*, not just future ones — without it, a
 * device that authenticated before the ban would keep working until its
 * client-side token happened to expire on its own.
 */
export const disableUserAccount = onCall(async (request) => {
  const targetUid = requireAdminAndTargetUid(request);

  try {
    const auth = getAuth();
    await auth.updateUser(targetUid, { disabled: true });
    await auth.revokeRefreshTokens(targetUid);
    return { success: true };
  } catch (error) {
    console.error(`Failed to disable account ${targetUid}`, error);
    throw new HttpsError(
      "internal",
      "Failed to disable the account.",
      error instanceof Error ? error.message : String(error)
    );
  }
});

/**
 * The reverse of [disableUserAccount] — called from
 * AdminReportDetailActivity's unban flow, after the Firestore-level
 * unban (isBanned cleared to false, banReason deleted) has already
 * succeeded.
 *
 * No revokeRefreshTokens counterpart here: re-enabling doesn't need to
 * force anything. A disabled account has no valid session to invalidate
 * (Auth already rejects every request from a disabled user, tokens
 * included) and un-disabling should simply let sign-in work again on the
 * user's next attempt, not push a change out to any device on its own.
 */
export const enableUserAccount = onCall(async (request) => {
  const targetUid = requireAdminAndTargetUid(request);

  try {
    await getAuth().updateUser(targetUid, { disabled: false });
    return { success: true };
  } catch (error) {
    console.error(`Failed to re-enable account ${targetUid}`, error);
    throw new HttpsError(
      "internal",
      "Failed to re-enable the account.",
      error instanceof Error ? error.message : String(error)
    );
  }
});

// ============================================================================
// Push notifications — replaces send_notification.php entirely. Both
// functions below call the Admin SDK's messaging API directly rather than
// relaying through a third-party host, and fire from the Firestore write
// itself: no client-side HTTP call, and none of PushNotificationSender's
// dedupe/rate-limit bookkeeping is needed here, since a Firestore trigger
// runs exactly once per write with no dependency on the sending device's own
// network — the failure modes that bookkeeping existed for don't apply.
// ============================================================================

const PUSH_TYPE_MATCH = "match";
const PUSH_TYPE_MESSAGE = "message";
const PUSH_TYPE_LIKE = "like";

/**
 * FCM error codes meaning the token is permanently dead - uninstalled,
 * reinstalled with a new token, or app data cleared. Nothing will ever be
 * delivered to it again.
 *
 * messaging/invalid-argument is deliberately NOT here even though it can
 * indicate a bad token: it also fires for a malformed PAYLOAD, so clearing on
 * it would risk wiping a perfectly good token because of a bug in our own
 * message construction. Too broad for a destructive action.
 *
 * Everything else - server-unavailable, internal-error, quota-exceeded,
 * third-party-auth-error, plain network failure - is transient and must leave
 * the token alone.
 */
const DEAD_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/**
 * Removes a token FCM has told us is permanently dead, so later notifications
 * skip that user at fcmTokenFor instead of failing again.
 *
 * Transactional, and only deletes if the stored token is STILL the one that
 * failed. Between reading it and the send coming back, the user may have
 * reinstalled and registered a new token; deleting unconditionally would wipe
 * a working one and silently stop their notifications for good - a worse bug
 * than the wasted send this is cleaning up.
 *
 * Failures here are logged and swallowed: not clearing a dead token costs one
 * wasted send next time, which is not worth failing a notification over.
 */
async function clearDeadToken(uid: string, token: string): Promise<void> {
  const db = getFirestore();
  const ref = db.collection("users").doc(uid);
  try {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (snap.get("fcmToken") !== token) return;
      tx.update(ref, { fcmToken: FieldValue.delete() });
    });
    logger.info(`Cleared dead fcmToken for ${uid}`);
  } catch (e) {
    logger.warn(`Could not clear dead fcmToken for ${uid}`, e);
  }
}

/**
 * Data-only FCM send — never a "notification" payload, so the client's
 * WedoraFirebaseMessagingService.onMessageReceived stays the single place a
 * push becomes a shown system notification (same reasoning that receiver's
 * own doc comment gives, now enforced from the sending side too). Keys
 * mirror exactly what that receiver reads: type, senderUid, title, body.
 *
 * Failures never throw — a push is "nice to have, never blocks the underlying
 * action", the stance every push call site in this app takes. But they are no
 * longer merely logged: a [DEAD_TOKEN_CODES] failure also clears the token, so
 * the same user is not retried forever. That is the one error class with a side
 * effect; everything else is logged and dropped.
 *
 * [recipientUid] exists only for that cleanup — the send itself needs just the
 * token.
 */
async function sendPush(
  token: string,
  recipientUid: string,
  type: string,
  senderUid: string,
  title: string,
  body: string
): Promise<void> {
  try {
    await getMessaging().send({
      token,
      data: { type, senderUid, title, body },
    });
  } catch (error) {
    const code = (error as { code?: string })?.code;
    if (code && DEAD_TOKEN_CODES.has(code)) {
      await clearDeadToken(recipientUid, token);
      return;
    }
    logger.warn(`Push send failed (type=${type}, senderUid=${senderUid})`, error);
  }
}

/** The user's registered fcmToken, or null if they have none. */
async function fcmTokenFor(uid: string): Promise<string | null> {
  const snapshot = await getFirestore().collection("users").doc(uid).get();
  const token = snapshot.get("fcmToken");
  return typeof token === "string" && token.length > 0 ? token : null;
}

/**
 * The user's displayName, falling back to their uid — the same fallback
 * ChatThreadActivity.sendMessagePush uses client-side (there, off Auth's
 * displayName; here, off the Firestore copy ProfileStep1NameActivity keeps
 * in sync with it, since the Admin SDK has no reason to make a second call
 * to Auth just to read a value already sitting on the profile document).
 */
async function displayNameFor(uid: string): Promise<string> {
  const snapshot = await getFirestore().collection("users").doc(uid).get();
  const name = snapshot.get("displayName");
  return typeof name === "string" && name.trim().length > 0 ? name : uid;
}

/**
 * Everyone who has liked, on one side of a match write.
 *
 * The UNION of `likedUsers` and the scalar `likedBy` it replaced, mirroring
 * Match.kt's parseLikedUsers exactly. Liking back on a legacy document writes
 * likedUsers = [the second liker] while the original liker still sits in
 * likedBy alone, so reading only the array drops them.
 *
 * This matters here specifically because the backfill credits legacy likes.
 * Counting only `likedUsers` on delete would decrement the array side and
 * silently leave the legacy like credited forever — an upward drift of
 * exactly the kind this counter is supposed to avoid.
 */
function likersOf(snap: FirebaseFirestore.DocumentSnapshot): string[] {
  const current = (snap.get("likedUsers") as string[] | undefined) ?? [];
  const legacy = snap.get("likedBy") as string | undefined;
  return legacy ? Array.from(new Set([...current, legacy])) : current;
}

/**
 * Keeps `users/{uid}.likesReceivedCount` in step with the likes recorded on
 * match documents.
 *
 * This lives server-side rather than in the client's like batch for two
 * reasons, both hard blockers rather than preferences:
 *
 *  1. firestore.rules only lets a user write their OWN profile document
 *     (`allow update: if isOwner(userId)`), and this counter lives on the
 *     LIKED user's document. Doing it client-side would mean letting any
 *     signed-in account write to any other account's profile — and even
 *     scoped to one field with a delta check, nothing stops a modified
 *     client calling it repeatedly to inflate its own count.
 *  2. The client has five separate paths that record a like (the gated
 *     batch, the already-matched refresh, two fail-open fallbacks, and
 *     ProfileDetail). A trigger on the document they all converge on
 *     covers every one of them, including any added later.
 *
 * The diff below is deliberately general rather than special-casing the
 * three shapes that actually occur today, so a future arrayRemove on
 * likedUsers is handled without touching this:
 *
 *   CREATE [A]        -> B +1        (one-sided like)
 *   UPDATE [A]->[A,B] -> A +1        (liked back, match goes mutual)
 *   DELETE [A]        -> B -1        (unlike)
 *   DELETE [A,B]      -> A -1, B -1  (unlike destroys BOTH likes, since
 *                                     deleteMatchDocument removes the whole
 *                                     document rather than one uid)
 *
 * Every other update — lastMessage, seenBy, hiddenBy, lastReadAt, or a
 * re-like refresh — leaves likedUsers untouched, so both diff sets come out
 * empty and this returns without writing.
 *
 * FieldValue.increment is an atomic server-side transform, so concurrent
 * likes on the same profile cannot lose an update. No transaction is needed
 * or wanted here. (The daily-like counter in LikeLimit.kt genuinely does
 * read-then-write, but it cannot use increment: its reset depends on
 * comparing a stored date.)
 *
 * NOT backfilled. Counts start from zero at deploy and reflect activity from
 * then on; existing matches were never counted. Readers must therefore floor
 * the value at zero, since an unlike of a pre-existing like can drive it
 * negative and increment cannot clamp.
 *
 * Failures are logged and swallowed: a missed count must never cost the user
 * their match notification.
 */
async function syncLikesReceivedCounts(
  change: { before: FirebaseFirestore.DocumentSnapshot;
            after: FirebaseFirestore.DocumentSnapshot }
): Promise<void> {
  const before = likersOf(change.before);
  const after = likersOf(change.after);

  // On a delete there is no `after`, so the participants come from `before`.
  const users = ((change.after.exists
    ? change.after.get("users")
    : change.before.get("users")) as string[] | undefined) ?? [];
  if (users.length !== 2) return;

  const added = after.filter((u) => !before.includes(u));
  const removed = before.filter((u) => !after.includes(u));
  if (added.length === 0 && removed.length === 0) return;

  // Each uid in likedUsers means "this person liked the other one", so the
  // count moves on the OTHER participant, not the liker.
  const deltas = new Map<string, number>();
  const bump = (liker: string, by: number) => {
    const recipient = users.find((u) => u !== liker);
    if (!recipient) return;
    deltas.set(recipient, (deltas.get(recipient) ?? 0) + by);
  };
  added.forEach((u) => bump(u, 1));
  removed.forEach((u) => bump(u, -1));

  try {
    const db = getFirestore();
    const batch = db.batch();
    for (const [uid, delta] of deltas) {
      if (delta === 0) continue;
      batch.set(
        db.collection("users").doc(uid),
        { likesReceivedCount: FieldValue.increment(delta) },
        { merge: true }
      );
    }
    await batch.commit();
  } catch (e) {
    logger.error("Failed to sync likesReceivedCount", e);
  }
}

/**
 * Fires on every write to a match document — create, update, or delete —
 * and decides for itself whether either push applies. That's this app's
 * match model leaving no simpler option, not a stylistic choice: a match
 * document is always CREATED one-sided (`likedUsers` holds exactly the
 * first liker's uid at creation time, enforced by firestore.rules'
 * isValidNewMatch() — see Match.kt's own doc comment), and only ever
 * becomes mutual via a later UPDATE, when the second person's like adds
 * their uid to that same array. A trigger that only ever fired on create
 * could never observe the moment a match actually completes — this one
 * covers both from a single Firestore-triggered function, reproducing the
 * "New Match!" vs "Someone liked your profile!" split LikeLimit.kt's own
 * sendLikeOrMatchPush already makes client-side.
 */
export const onMatchWritten = onDocumentWritten(
  "matches/{matchId}",
  async (event) => {
    const change = event.data;
    if (!change) return;

    // Before the early-return below: the counter has to observe deletes too,
    // which is exactly the case the push logic skips.
    await syncLikesReceivedCounts(change);

    if (!change.after.exists) return; // deleted (an unlike) — nothing to notify

    const after = change.after;
    const users = (after.get("users") as string[] | undefined) ?? [];
    const likedUsersAfter = (after.get("likedUsers") as string[] | undefined) ?? [];
    if (users.length !== 2) return;

    if (!change.before.exists) {
      // Fresh document — always one-sided per isValidNewMatch(). The one
      // uid NOT in likedUsers is who this is news to.
      if (likedUsersAfter.length !== 1) return;
      const liker = likedUsersAfter[0];
      const recipient = users.find((u) => u !== liker);
      if (!recipient) return;

      const token = await fcmTokenFor(recipient);
      if (!token) return;
      await sendPush(token, recipient, PUSH_TYPE_LIKE, liker, "Someone liked your profile! ❤️", "");
      return;
    }

    // An update. Only interesting when likedUsers just grew from one uid to
    // both — the second person liked back and the match went mutual. Every
    // other update this fires for (a re-like refresh, or a change to
    // lastMessage/seenBy/hiddenBy/lastReadAt) leaves likedUsers untouched
    // and is a silent no-op here.
    const likedUsersBefore = (change.before.get("likedUsers") as string[] | undefined) ?? [];
    if (likedUsersBefore.length !== 1 || likedUsersAfter.length !== 2) return;

    const newLiker = likedUsersAfter.find((u) => !likedUsersBefore.includes(u));
    const originalLiker = likedUsersBefore[0];
    if (!newLiker || !originalLiker) return;

    // Whoever just completed the match (newLiker) already sees it instantly
    // in their own client — this push is for the original liker, finding
    // out the like they left earlier just became mutual.
    const token = await fcmTokenFor(originalLiker);
    if (!token) return;
    await sendPush(
      token,
      originalLiker,
      PUSH_TYPE_MATCH,
      newLiker,
      "New Match! 🎉",
      "You matched with someone new"
    );
  }
);

/**
 * Fires once per new chat message. Looks up the recipient (the match's
 * other participant) and their fcmToken, and the sender's displayName for
 * the push's title, then sends a data-only push carrying the message text
 * as a preview — length-capped defensively, since FCM's total data-payload
 * limit is 4KB and nothing on the client enforces a message length limit.
 */
export const onMessageSent = onDocumentCreated(
  "matches/{matchId}/messages/{messageId}",
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const senderId = snapshot.get("senderId") as string | undefined;
    const text = (snapshot.get("text") as string | undefined) ?? "";
    if (!senderId) return;

    const matchId = event.params.matchId;
    const matchDoc = await getFirestore().collection("matches").doc(matchId).get();
    const users = (matchDoc.get("users") as string[] | undefined) ?? [];
    const recipient = users.find((u) => u !== senderId);
    if (!recipient) return;

    const [token, senderName] = await Promise.all([
      fcmTokenFor(recipient),
      displayNameFor(senderId),
    ]);
    if (!token) return;

    const preview = text.length > 200 ? `${text.slice(0, 200)}…` : text;
    await sendPush(token, recipient, PUSH_TYPE_MESSAGE, senderId, senderName, preview);
  }
);
