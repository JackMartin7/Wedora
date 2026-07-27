package com.wedora.app

/**
 * The one Firebase Auth UID with admin access to the Reports queue.
 * Hardcoded rather than stored anywhere editable, and deliberately not the
 * only enforcement point — see firestore.rules' own `isAdmin()` (kept in
 * sync with this value by hand, since rules can't reference a Kotlin
 * constant) and the disableUserAccount Cloud Function's own check in
 * functions/src/index.ts, which trusts neither this client-side gate nor
 * the rules alone. Three places, one value, no way to share a single
 * source of truth across languages and deploy targets.
 */
object WedoraAdmin {
    const val UID = "QgOuA5no4jR6hsFoNp5UFWMceHk2"
}
