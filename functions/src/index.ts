import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { HttpsError, onCall } from "firebase-functions/https";

initializeApp();

/**
 * The one Firebase Auth UID allowed to disable another account.
 *
 * Kept in sync by hand with WedoraAdmin.UID (the Kotlin constant) and
 * firestore.rules' own isAdmin() — three places, one value, no way to
 * share a single source of truth across languages and deploy targets. If
 * this value ever needs to change, all three must be updated and
 * redeployed together.
 */
const ADMIN_UID = "QgOuA5no4jR6hsFoNp5UFWMceHk2";

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
 *
 * The caller's UID is checked against ADMIN_UID before anything else —
 * deliberately not trusting the app's own client-side gating
 * (ProfileActivity's hidden row, AdminReportDetailActivity's own onCreate
 * check) alone. Those are UI conveniences; this is the actual enforcement,
 * since a modified client or a raw call to this function's HTTPS endpoint
 * would bypass every client-side check entirely.
 */
export const disableUserAccount = onCall(async (request) => {
  if (request.auth?.uid !== ADMIN_UID) {
    throw new HttpsError(
      "permission-denied",
      "Only the admin account may disable a user."
    );
  }

  const targetUid = request.data?.targetUid;
  if (typeof targetUid !== "string" || targetUid.length === 0) {
    throw new HttpsError("invalid-argument", "targetUid is required.");
  }

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
