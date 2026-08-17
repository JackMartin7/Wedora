package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.wedora.app.databinding.ActivityAccountSettingsBinding
import com.wedora.app.databinding.DialogPasswordPromptBinding

/**
 * Change/set password, change email, delete account.
 *
 * All three are "sensitive" operations in Firebase's terms and need a recent
 * sign-in, so each re-authenticates first. Change Password takes the current
 * password from the form; the other two have no password field of their own,
 * so they raise a prompt dialog.
 *
 * Re-auth failure is deliberately reported as "current password is incorrect"
 * rather than a generic error — a wrong password is overwhelmingly the reason
 * it fails, and it's the one cause the user can act on.
 *
 * None of that has a password to check for a Google-only account (one signed
 * up via GoogleAuthHelper and never set one) — [hasPasswordProvider] is the
 * gate everything below branches on. Change Email and Delete Account
 * re-authenticate with a fresh Google credential instead (see
 * [reauthenticateWithGoogle]); the password section offers Set Password
 * instead of Change Password, and needs no re-auth of its own at all —
 * linking a new credential onto an already-signed-in session is not one of
 * Firebase's "sensitive" operations the way updating or deleting is.
 */
class AccountSettingsActivity : WedoraBaseActivity(), DeleteAccountBottomSheet.Host {

    private companion object {
        const val TAG = "WedoraAccount"

        /** Firebase's own minimum; repeated here so the message can say it. */
        const val MIN_PASSWORD_LENGTH = 6
    }

    private lateinit var binding: ActivityAccountSettingsBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Whether the signed-in account has an email/password credential linked. */
    private var hasPasswordProvider = false

    private val googleSignInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, options)
    }

    /** What to do once [googleReauthLauncher] delivers a fresh credential. */
    private var pendingGoogleReauthAction: ((FirebaseUser) -> Unit)? = null

    private val googleReauthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            setBusy(false)
            pendingGoogleReauthAction = null
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            finishGoogleReauth(account)
        } catch (e: ApiException) {
            setBusy(false)
            pendingGoogleReauthAction = null
            Log.w(TAG, "Google re-authentication failed", e)
            Toast.makeText(this, R.string.error_network, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root, applyIme = true)

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        hasPasswordProvider = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
        updatePasswordSectionUi()

        binding.etNewEmail.setText(user.email.orEmpty())

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUpdatePassword.setOnClickListener { updatePassword() }
        binding.btnUpdateEmail.setOnClickListener { updateEmail() }
        binding.btnUpdatePassword.addPressScale()
        binding.btnUpdateEmail.addPressScale()
        binding.btnDeleteAccount.setOnClickListener {
            DeleteAccountBottomSheet().show(supportFragmentManager, "delete_account")
        }
    }

    // ----- Re-authentication ----------------------------------------------

    /**
     * Re-authenticates with the account's own email and [password], then runs
     * [onSuccess].
     *
     * A wrong password surfaces as FirebaseAuthInvalidCredentialsException,
     * which is separated from everything else (network, too-many-requests) so
     * the user isn't told their password is wrong when it isn't.
     */
    private fun reauthenticate(password: String, onSuccess: (FirebaseUser) -> Unit) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            return
        }

        setBusy(true)
        user.reauthenticate(EmailAuthProvider.getCredential(email, password))
            .addOnSuccessListener { onSuccess(user) }
            .addOnFailureListener { e ->
                setBusy(false)
                if (e is FirebaseAuthInvalidCredentialsException) {
                    Toast.makeText(
                        this, R.string.error_current_password_wrong, Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.w(TAG, "Re-authentication failed", e)
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_LONG).show()
                }
            }
    }

    /**
     * Re-authenticates a Google-only account with a fresh Google credential
     * instead of a password — there isn't one to ask for. Tries a silent
     * sign-in first, which succeeds with no UI at all when the cached Google
     * session is still valid (the overwhelmingly common case, since this is
     * the same account the user is already signed into); only falls back to
     * the interactive account-chooser prompt via [googleReauthLauncher] when
     * that fails.
     */
    private fun reauthenticateWithGoogle(onSuccess: (FirebaseUser) -> Unit) {
        pendingGoogleReauthAction = onSuccess
        setBusy(true)
        googleSignInClient.silentSignIn()
            .addOnSuccessListener { account -> finishGoogleReauth(account) }
            .addOnFailureListener { googleReauthLauncher.launch(googleSignInClient.signInIntent) }
    }

    private fun finishGoogleReauth(account: GoogleSignInAccount) {
        val action = pendingGoogleReauthAction
        val user = auth.currentUser
        val idToken = account.idToken
        if (action == null || user == null || idToken == null) {
            setBusy(false)
            pendingGoogleReauthAction = null
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            return
        }

        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null))
            .addOnSuccessListener {
                pendingGoogleReauthAction = null
                action(user)
            }
            .addOnFailureListener { e ->
                setBusy(false)
                pendingGoogleReauthAction = null
                Log.w(TAG, "Google re-authentication failed", e)
                Toast.makeText(this, R.string.error_network, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Re-authenticates via whichever credential this account actually has,
     * then runs [onSuccess] — the one thing Change Email and Delete Account
     * both need, differing only in which prompt they show first.
     */
    private fun reauthenticateAndThen(onSuccess: (FirebaseUser) -> Unit) {
        if (hasPasswordProvider) {
            promptForPassword { password -> reauthenticate(password, onSuccess) }
        } else {
            reauthenticateWithGoogle(onSuccess)
        }
    }

    /** Password prompt for the two actions with no password field on screen. */
    private fun promptForPassword(onEntered: (String) -> Unit) {
        val dialogBinding = DialogPasswordPromptBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reauth_title)
            .setMessage(R.string.reauth_message)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.reauth_confirm) { _, _ ->
                val password = dialogBinding.etDialogPassword.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(
                        this, R.string.error_current_password_required, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onEntered(password)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ----- Change / Set password -------------------------------------------

    /**
     * Reflects [hasPasswordProvider] in the three views whose text or
     * visibility depends on it: the section heading, the "current password"
     * row (only meaningful when there's an existing password to confirm),
     * and the submit button.
     */
    private fun updatePasswordSectionUi() {
        val headingRes =
            if (hasPasswordProvider) R.string.account_change_password else R.string.account_set_password
        binding.tvChangePasswordHeading.setText(headingRes)
        binding.btnUpdatePassword.setText(
            if (hasPasswordProvider) R.string.account_update_password else R.string.account_set_password
        )

        val currentPasswordVisibility = if (hasPasswordProvider) View.VISIBLE else View.GONE
        binding.labelCurrentPassword.visibility = currentPasswordVisibility
        binding.etCurrentPassword.visibility = currentPasswordVisibility
    }

    private fun updatePassword() {
        val current = binding.etCurrentPassword.text.toString()
        val new = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()

        if (hasPasswordProvider && current.isEmpty()) {
            showFieldError(binding.etCurrentPassword, getString(R.string.error_current_password_required))
            return
        }
        if (new.isEmpty()) {
            showFieldError(binding.etNewPassword, getString(R.string.error_new_password_required))
            return
        }
        if (new.length < MIN_PASSWORD_LENGTH) {
            showFieldError(
                binding.etNewPassword,
                getString(R.string.error_password_too_short, MIN_PASSWORD_LENGTH)
            )
            return
        }
        if (new != confirm) {
            showFieldError(binding.etConfirmPassword, getString(R.string.error_passwords_dont_match))
            return
        }

        if (hasPasswordProvider) {
            reauthenticate(current) { user ->
                user.updatePassword(new)
                    .addOnSuccessListener {
                        setBusy(false)
                        clearPasswordFields()
                        Toast.makeText(this, R.string.password_updated, Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to update password", e)
                        setBusy(false)
                        Toast.makeText(
                            this, R.string.error_password_update_failed, Toast.LENGTH_LONG
                        ).show()
                    }
            }
        } else {
            linkPasswordCredential(new)
        }
    }

    /**
     * Adds an email/password credential to a Google-only account without
     * disturbing the existing Google sign-in — the two coexist afterwards as
     * two ways into the same account. Needs no re-authentication first:
     * linking a new provider onto an already-signed-in session isn't one of
     * Firebase's "sensitive" operations the way updating a password or
     * deleting the account is, so there's nothing to reauthenticate.
     */
    private fun linkPasswordCredential(newPassword: String) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            return
        }

        setBusy(true)
        user.linkWithCredential(EmailAuthProvider.getCredential(email, newPassword))
            .addOnSuccessListener {
                setBusy(false)
                clearPasswordFields()
                hasPasswordProvider = true
                updatePasswordSectionUi()
                Toast.makeText(this, R.string.password_set, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                setBusy(false)
                Log.w(TAG, "Failed to link password credential", e)
                val resId = when (e) {
                    is FirebaseAuthUserCollisionException -> R.string.error_email_in_use
                    is FirebaseAuthWeakPasswordException -> R.string.error_weak_password
                    else -> R.string.error_password_update_failed
                }
                Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
            }
    }

    private fun clearPasswordFields() {
        binding.etCurrentPassword.text.clear()
        binding.etNewPassword.text.clear()
        binding.etConfirmPassword.text.clear()
        binding.etCurrentPassword.clearFocus()
    }

    // ----- Change email ---------------------------------------------------

    private fun updateEmail() {
        val newEmail = binding.etNewEmail.text.toString().trim()

        if (newEmail.isEmpty()) {
            showFieldError(binding.etNewEmail, getString(R.string.error_email_required))
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
            showFieldError(binding.etNewEmail, getString(R.string.error_invalid_email))
            return
        }
        if (newEmail.equals(auth.currentUser?.email, ignoreCase = true)) {
            showFieldError(binding.etNewEmail, getString(R.string.error_email_unchanged))
            return
        }

        reauthenticateAndThen { user -> sendEmailChangeVerification(user, newEmail) }
    }

    /**
     * verifyBeforeUpdateEmail, not updateEmail: the address only changes once
     * the user proves they own the new one, so a typo can't lock them out of
     * the account.
     *
     * NOTE: the Firestore `users/{uid}.email` field is NOT updated here, and
     * cannot be — the change hasn't happened yet at this point, and there is
     * no client callback for when the user eventually clicks the link (they may
     * do it on another device, or days later). That field has to be re-synced
     * separately, on the next login after verification: compare
     * FirebaseAuth.currentUser.email against the stored users/{uid}.email and
     * write it through when they differ.
     */
    private fun sendEmailChangeVerification(user: FirebaseUser, newEmail: String) {
        user.verifyBeforeUpdateEmail(newEmail)
            .addOnSuccessListener {
                setBusy(false)
                Toast.makeText(this, R.string.email_verification_sent, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to send email-change verification", e)
                setBusy(false)
                Toast.makeText(this, R.string.error_email_update_failed, Toast.LENGTH_LONG).show()
            }
    }

    // ----- Delete account -------------------------------------------------

    /**
     * From [DeleteAccountBottomSheet] once the user confirms. The re-auth
     * (password or Google, see [reauthenticateAndThen]) and the deletion
     * itself are unchanged — the sheet only replaces the first "are you
     * sure" step.
     */
    override fun onDeleteAccountConfirmed() {
        reauthenticateAndThen { user -> deleteAccountData(user) }
    }

    /**
     * Deletes this user's own data, then the Auth account itself — in that
     * order, and only proceeding to Auth deletion if every Firestore step
     * actually succeeded. An earlier version of this used
     * batch.commit().addOnFailureListener{...}.addOnCompleteListener{...}:
     * addOnCompleteListener fires on both success AND failure of that same
     * Task, so a failed commit still fell through to deleting the Auth
     * account — logged, never surfaced, never blocking. That's why deleted
     * users' Firestore documents were turning up still present: the account
     * was gone, but nothing said the write that was supposed to remove its
     * data had actually failed.
     *
     * Four sources of this user's own data are read in parallel, then
     * deleted together with the profile document in one pass:
     * - blocks/{uid}/blockedUsers — private to this user, nobody else reads it
     * - passes/{uid}/passedUsers — same shape, who they've swiped past
     * - profileViews/{uid}/viewers — who viewed THEIR profile
     * - a collectionGroup("viewers") query for FIELD_VIEWER_UID == uid — the
     *   views THEY made on other people's profiles, which live under those
     *   other users' own profileViews/{otherUid}/viewers subcollections and
     *   so aren't reachable as a subcollection of this user's own document
     *   the way the first three are. See firestore.rules' own comment on
     *   profileViews for why this query is even allowed to find them.
     *
     * Tasks.whenAllSuccess, not whenAllComplete: if even one of the four
     * reads fails, this stops here and reports an error rather than
     * deleting only part of the user's data.
     *
     * Match documents and their messages are deliberately NOT included. They
     * are shared with another participant who still needs their side of the
     * conversation, and a client can't delete only "their half" of a document
     * that represents both people. Deleting them here would silently erase
     * someone else's chat history. Removing the leftover matches of deleted
     * accounts is a server-side job (a Cloud Function on user deletion), not
     * something to do from the departing client.
     */
    private fun deleteAccountData(user: FirebaseUser) {
        val uid = user.uid

        val blockedTask = firestore.collection(Moderation.BLOCKS_COLLECTION)
            .document(uid).collection(Moderation.BLOCKED_USERS_SUBCOLLECTION).get()
        val passedTask = firestore.collection(Passes.COLLECTION)
            .document(uid).collection(Passes.PASSED_USERS_SUBCOLLECTION).get()
        val ownViewersTask = firestore.collection(PROFILE_VIEWS_COLLECTION)
            .document(uid).collection(PROFILE_VIEWS_SUBCOLLECTION_VIEWERS).get()
        val viewedByMeTask = firestore.collectionGroup(PROFILE_VIEWS_SUBCOLLECTION_VIEWERS)
            .whereEqualTo(FIELD_VIEWER_UID, uid)
            .get()

        Tasks.whenAllSuccess<QuerySnapshot>(blockedTask, passedTask, ownViewersTask, viewedByMeTask)
            .addOnSuccessListener { snapshots ->
                val refs = snapshots.flatMap { it.documents }.map { it.reference }.toMutableList()
                refs.add(firestore.collection(UserProfile.COLLECTION).document(uid))

                deleteInBatches(refs)
                    .addOnSuccessListener { deleteProfilePhotoThenAuth(user) }
                    .addOnFailureListener { e ->
                        reportDeleteFailure("delete Firestore data", uid, e)
                    }
            }
            .addOnFailureListener { e ->
                reportDeleteFailure("enumerate data to delete", uid, e)
            }
    }

    /**
     * One place for every delete-account failure: the local log, a
     * Crashlytics non-fatal, and the toast.
     *
     * Crashlytics rather than logcat alone because this failure is
     * data-dependent — the PERMISSION_DENIED on profileViews that prompted
     * this only ever fired for users who had actually been viewed, so it was
     * invisible in testing and only visible in the field with a cable
     * attached. Account deletion is also a legal obligation in several
     * markets, which is a poor thing to be quietly failing.
     *
     * The Firestore status code is pulled into the message rather than left
     * inside the exception's text: PERMISSION_DENIED vs UNAVAILABLE is the
     * whole diagnosis, and it's what groups usefully in the dashboard.
     */
    private fun reportDeleteFailure(step: String, uid: String, e: Exception) {
        val code = (e as? FirebaseFirestoreException)?.code?.name ?: "n/a"
        CrashReporting.record(
            IllegalStateException("Account deletion failed to $step (code=$code)", e),
            where = "AccountSettingsActivity.deleteAccount"
        )
        Log.w(TAG, "Failed to $step for $uid (code=$code); account not deleted", e)
        setBusy(false)
        Toast.makeText(this, R.string.error_account_delete_failed, Toast.LENGTH_LONG).show()
    }

    /**
     * Removes the hosted profile photo, then deletes the Auth account.
     *
     * Storage was the one store account deletion never touched, so every
     * deleted account left `profile_photos/{uid}/photo.jpg` behind for good —
     * bytes with no owner, no reference from any document, and nothing that
     * would ever clean them up.
     *
     * Before the Auth deletion, for the same reason the Firestore pass is:
     * storage.rules scopes the write to the owning uid, so deleting the Auth
     * account first would revoke the credential this delete needs.
     *
     * ERROR_OBJECT_NOT_FOUND counts as success — a user who never uploaded a
     * photo has no object, and that must not block their deletion. Any other
     * Storage error is reported but also NOT allowed to block: a leftover
     * image is worth strictly less than the user's right to delete their
     * account, so it's logged for cleanup rather than turned into a wall.
     */
    private fun deleteProfilePhotoThenAuth(user: FirebaseUser) {
        val uid = user.uid
        FirebaseStorage.getInstance().reference
            .child("profile_photos/$uid/photo.jpg")
            .delete()
            .addOnSuccessListener { deleteAuthAccount(user) }
            .addOnFailureListener { e ->
                val notFound = (e as? StorageException)?.errorCode ==
                    StorageException.ERROR_OBJECT_NOT_FOUND
                if (!notFound) {
                    CrashReporting.record(
                        IllegalStateException("Orphaned profile photo left for $uid", e),
                        where = "AccountSettingsActivity.deleteProfilePhoto"
                    )
                    Log.w(TAG, "Couldn't delete stored photo for $uid; continuing", e)
                }
                deleteAuthAccount(user)
            }
    }

    /**
     * Deletes [refs] across as many batches as needed — Firestore caps a
     * single batch at 500 writes, and passes alone can already number up to
     * [Passes.MAX_LOADED] (500) for one user. Chunks of 450 rather than 500
     * to leave headroom for the profile document [deleteAccountData] always
     * appends on top of whatever the four reads found.
     *
     * Batches commit sequentially, each chained via continueWithTask off the
     * previous one, and a batch that fails short-circuits every batch after
     * it (checking prev.isSuccessful and propagating its exception onward
     * instead of attempting the next chunk) — the caller sees one failure
     * for the whole operation rather than a partial, silently-incomplete
     * deletion.
     */
    private fun deleteInBatches(refs: List<DocumentReference>): Task<Void> {
        var chain: Task<Void> = Tasks.forResult(null)
        for (chunk in refs.chunked(450)) {
            chain = chain.continueWithTask { previous ->
                if (!previous.isSuccessful) {
                    Tasks.forException(previous.exception ?: Exception("Batch delete failed"))
                } else {
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it) }
                    batch.commit()
                }
            }
        }
        return chain
    }

    /**
     * Firestore is already gone by the time this runs — this is the last
     * step, not a parallel one. Deleting Auth before the Firestore cleanup
     * (the ordering this replaces) would revoke the credentials those writes
     * are authorised by, leaving the documents behind with no signed-in user
     * able to remove them.
     */
    private fun deleteAuthAccount(user: FirebaseUser) {
        val uid = user.uid
        user.delete()
            .addOnSuccessListener { onAccountDeleted(uid) }
            .addOnFailureListener { e ->
                // Worth its own report rather than routing through
                // reportDeleteFailure: reaching here means the user's data is
                // already gone but the account isn't, which is the one
                // partial state this flow can end up in.
                CrashReporting.record(
                    IllegalStateException("Data deleted but auth account survived", e),
                    where = "AccountSettingsActivity.deleteAuthAccount"
                )
                Log.w(TAG, "Firestore data deleted but failed to delete auth account for $uid", e)
                setBusy(false)
                Toast.makeText(
                    this, R.string.error_account_delete_failed, Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun onAccountDeleted(uid: String) {
        // Local state is cleared only after the account is really gone, so a
        // failed delete doesn't sign the user out of an account they still have.
        clearAllWedoraData(this, uid)
        auth.signOut()

        startActivity(
            Intent(this, SplashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ----- Shared UI ------------------------------------------------------

    private fun showFieldError(field: EditText, message: String) {
        field.error = message
        field.requestFocus()
    }

    private fun setBusy(busy: Boolean) {
        binding.loadingOverlay.visibility = if (busy) View.VISIBLE else View.GONE
    }
}
