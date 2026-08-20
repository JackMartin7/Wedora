# `likesReceivedCount` — how it works, and how to re-backfill it

`users/{uid}.likesReceivedCount` is the number of likes a profile has received.
It is shown on the Home swipe card and on Profile Detail.

This document exists because the field has two non-obvious properties — it is
written **only** by a Cloud Function, and computing it correctly requires a
field the app no longer writes — and because a re-backfill is the kind of thing
that gets done once a year by someone who wasn't here the first time.

## Who writes it

**Only** the `onMatchWritten` Cloud Function (`functions/src/index.ts`,
`syncLikesReceivedCounts`). Clients never write it, and `firestore.rules`
enforces that via `likesReceivedCountUnchanged()`.

That is not a style choice. The counter lives on the **liked** user's document,
and the rules only permit a user to write their **own** profile
(`allow update: if isOwner(userId)`). Doing it client-side would mean opening
every profile document to writes from any signed-in account; even scoped to one
field with a delta check, nothing stops a modified client calling it repeatedly
to inflate its own count.

It also means the five separate client paths that record a like — the gated
batch, the already-matched refresh, the two fail-open fallbacks in
`LikeLimit.kt`, and ProfileDetail — are all covered by one trigger on the
document they converge on.

`FieldValue.increment` is an atomic server-side transform, so concurrent likes
cannot lose an update. No transaction is involved. (The *daily-like* counter in
`LikeLimit.kt` does read-then-write and genuinely has that race, but it cannot
use `increment`: its reset depends on comparing a stored date string.)

## The rule that is easy to get wrong

**The likers on a match are the UNION of `likedUsers` and the legacy scalar
`likedBy`.**

This mirrors `Match.kt`'s `parseLikedUsers`. Liking back on a legacy document
writes `likedUsers = [the second liker]` while the original liker survives only
in `likedBy`, so reading the array alone drops them.

As of the August 2026 backfill, 11 of 1,640 match documents still carried
`likedBy`, and **6 of those had no `likedUsers` array at all**. One account
(`C2QRrp…`) had *both* of its received likes visible only through the legacy
field — the array-only reading would have given it 0 instead of 2.

Any code that counts likes must apply the union. `likersOf()` in
`functions/src/index.ts` is the reference implementation.

## For each liker, the count moves on the OTHER participant

A uid in `likedUsers` means *this person liked the other one*. So the recipient
is `users.find(u => u !== liker)`. Getting this backwards is the failure mode
the global invariant below is designed to catch.

## Unlike is destructive

`unlikeInPlace` calls `deleteMatchDocument`, which deletes the **whole match
document**, not one uid. On a mutual match that removes both likes at once, so
both participants decrement. `syncLikesReceivedCounts` handles this by diffing
`likersOf(before)` against `likersOf(after)` rather than special-casing, so
create / like-back / unlike / any future `arrayRemove` all fall out of one rule.

## Re-running the backfill

### When you would

- The counter drifted (a function outage, a bulk data edit, a restore).
- A verify pass reports genuine diffs.
- Historical matches were imported.

You do **not** need to re-run it for normal operation. The function keeps the
value current on its own.

### The algorithm

Paginate `matches` (500/page, `orderBy("__name__")` + `startAfter`), and for
each document:

```js
const users = data.users;                  // skip unless length === 2
const likers = likersOf(data);             // UNION - see above
for (const L of likers) {
  if (!users.includes(L)) continue;        // anomaly, log it
  const recipient = users.find(u => u !== L);
  tally[recipient] = (tally[recipient] ?? 0) + 1;
}
```

Then write with an **absolute value**:

```js
batch.set(db.collection("users").doc(uid),
          { likesReceivedCount: tally.get(uid) },
          { merge: true });
```

**Never `increment` in a backfill.** The absolute set is what makes the whole
thing idempotent — re-running recomputes the same totals and writes the same
result. `increment` would double-count on every run.

Two further rules:

- **Write only where computed differs from stored.** Users with no likes are
  skipped entirely; an absent field already parses as 0 in `UserProfile.from`.
- **Skip uids with no user document.** `set` + `merge` would create a stub
  profile for a deleted account. As of August 2026 there were 19 such uids,
  still referenced by matches — correctly left uncounted.

### Where to run it

A **local Node script using the Admin SDK**, dry-run by default, requiring an
explicit `--commit`. Not a Cloud Function: that would mean deploying an endpoint
capable of rewriting every user's like count, which then persists until somebody
remembers to delete it.

The Admin SDK bypasses rules, so `likesReceivedCountUnchanged()` does not need
to be relaxed. Never weaken that rule to run a migration.

Target selection should be driven by `FIRESTORE_EMULATOR_HOST` — set means
emulator and production is unreachable; unset means production via the
service-account key (gitignored, see `.gitignore`). Print the target and mode on
every run so a `--commit` can never be ambiguous about where it landed.

### Verification, in order

1. **Emulator with seeded fixtures.** Cover one-sided, mutual, legacy
   `likedBy`-only, legacy mixed with `likedUsers`, legacy duplicating the array,
   a malformed single-participant doc, a liker who is not a participant, and a
   recipient with no user document. Assert against counts worked out by hand.
2. **Idempotency.** Run `--commit` twice against the emulator; the second must
   write 0 documents and change nothing.
3. **Production dry-run.** Review the table before committing anything.
4. **Global invariant.** The sum of per-user tallies must equal the
   independently accumulated count of `(liker, match)` pairs. This catches
   attribution bugs that per-user spot checks miss.
5. **Independent spot-check.** Recount a handful of accounts using
   `where("users", "array-contains", uid)` — a *different* query path, not a
   re-run of the same scan. Include at least one account involved in a legacy
   document.
6. **Post-run verify pass.** Re-run the dry-run; every value should read
   "already correct". The doc-less uids will always show as differing, since
   they can never be written — that count is structural, not drift.

### Known race

The function increments while the backfill sets absolute values. A like landing
mid-run can be overwritten and lost. It shows up in step 6 as a genuine diff and
a re-run fixes it safely. Prefer a quiet window; the August 2026 run took a few
seconds and lost nothing.

## Baseline: August 2026 run

Useful as a sanity check that a future run is in the right ballpark.

| | |
|---|---|
| matches scanned | 1,640 |
| like entries counted | 1,750 |
| users with at least one like | 254 |
| documents written | 235 |
| uids skipped (no user document) | 19 |
| anomalies | 0 |
| cost | well under $0.01, inside the free tier |

Highest single count was 54. Verify pass immediately afterwards reported 235
already correct and no genuine diffs.
