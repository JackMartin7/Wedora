package com.wedora.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import com.wedora.app.databinding.ActivityAccountSettingsBinding
import com.wedora.app.databinding.DialogPasswordPromptBinding

/**
 * Change password, change email, delete account.
 *
 * All three are "sensitive" operations in Firebase's terms and need a recent
 * sign-in, so each re-authenticates first. Change Password takes the current
 * password from the form; the other two have no password field of their own,
 * so they raise a prompt dialog.
 *
 * Re-auth failure is deliberately reported as "current password is incorrect"
 * rather than a generic error — a wrong password is overwhelmingly the reason
 * it fails, and it's the one cause the user can act on.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            finish()
            return
        }

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

    // ----- Change password ------------------------------------------------

    private fun updatePassword() {
        val current = binding.etCurrentPassword.text.toString()
        val new = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()

        if (current.isEmpty()) {
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

        promptForPassword { password ->
            reauthenticate(password) { user -> sendEmailChangeVerification(user, newEmail) }
        }
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
     * From [DeleteAccountBottomSheet] once the user confirms. The password
     * re-auth and the deletion itself are unchanged — the sheet only replaces
     * the first "are you sure" step.
     */
    override fun onDeleteAccountConfirmed() {
        promptForPassword { password ->
            reauthenticate(password) { user -> deleteAccountData(user) }
        }
    }

    /**
     * Deletes this user's own data, then the Auth account itself.
     *
     * Match documents and their messages are deliberately NOT deleted. They are
     * shared with another participant who still needs their side of the
     * conversation, and a client can't delete only "their half" of a document
     * that represents both people. Deleting them here would silently erase
     * someone else's chat history. Removing the leftover matches of deleted
     * accounts is a server-side job (a Cloud Function on user deletion), not
     * something to do from the departing client.
     *
     * The blocks subcollection IS deleted: it's private to this user, nobody
     * else reads it, and the existing rules already permit the owner to delete
     * their own entries.
     */
    private fun deleteAccountData(user: FirebaseUser) {
        val uid = user.uid

        firestore.collection(Moderation.BLOCKS_COLLECTION)
            .document(uid)
            .collection(Moderation.BLOCKED_USERS_SUBCOLLECTION)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = firestore.batch()
                snapshot.documents.forEach { batch.delete(it.reference) }
                batch.delete(firestore.collection(UserProfile.COLLECTION).document(uid))
                commitDeletion(user, batch)
            }
            .addOnFailureListener { e ->
                // Couldn't enumerate the block list — delete the profile
                // document anyway rather than stranding the user in an account
                // they've asked to remove.
                Log.w(TAG, "Couldn't read block list while deleting account", e)
                val batch = firestore.batch()
                batch.delete(firestore.collection(UserProfile.COLLECTION).document(uid))
                commitDeletion(user, batch)
            }
    }

    /**
     * Firestore first, then the Auth account. Deleting Auth first would revoke
     * the credentials the Firestore writes are authorised by, leaving the
     * documents behind with no signed-in user able to remove them.
     */
    private fun commitDeletion(user: FirebaseUser, batch: WriteBatch) {
        val uid = user.uid
        batch.commit()
            .addOnFailureListener { e ->
                // Logged, not fatal: the account deletion below is the part the
                // user asked for, and a leftover profile document is recoverable
                // server-side. Stopping here would leave them unable to proceed.
                Log.w(TAG, "Failed to delete Firestore data for $uid", e)
            }
            .addOnCompleteListener {
                user.delete()
                    .addOnSuccessListener { onAccountDeleted(uid) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to delete auth account", e)
                        setBusy(false)
                        Toast.makeText(
                            this, R.string.error_account_delete_failed, Toast.LENGTH_LONG
                        ).show()
                    }
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
