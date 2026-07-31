package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.os.bundleOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * "Block this user?" confirmation, reusing the shared confirm layout. On
 * confirm it writes the block itself and reports success back to the host via
 * a fragment result, so the screen that opened it can react — the feed drops
 * the card, the profile screen closes.
 */
class BlockConfirmBottomSheet : ConfirmBottomSheet() {

    companion object {
        /** Host listens on this key; a bare result means the block succeeded. */
        const val RESULT_BLOCKED = "result_blocked"

        private const val ARG_TARGET_UID = "arg_target_uid"

        fun newInstance(targetUid: String) = BlockConfirmBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_TARGET_UID, targetUid) }
        }
    }

    override val iconRes = R.drawable.ic_block
    override val titleRes = R.string.block_confirm_title
    override val subtitleRes = R.string.block_confirm_message
    override val primaryLabelRes = R.string.block_confirm_button

    // The sheet stays open and busy until the write resolves, rather than
    // dismissing instantly with no feedback and a toast landing afterwards.
    override val dismissImmediately = false

    override fun onPrimary() {
        val selfUid = FirebaseAuth.getInstance().realUid ?: return
        val targetUid = requireArguments().getString(ARG_TARGET_UID).orEmpty()
        // Captured before the sheet can be dismissed out from under this
        // callback — dismiss() here happens on success/failure, not before.
        val fm = parentFragmentManager
        val context = requireContext().applicationContext

        setBusy(true, R.string.block_confirm_button_busy)
        blockUser(FirebaseFirestore.getInstance(), selfUid, targetUid)
            .addOnSuccessListener {
                Toast.makeText(context, R.string.block_success, Toast.LENGTH_SHORT).show()
                fm.setFragmentResult(RESULT_BLOCKED, bundleOf())
                dismiss()
            }
            .addOnFailureListener { e ->
                Log.w("WedoraModeration", "Failed to block user", e)
                Toast.makeText(context, R.string.block_failed, Toast.LENGTH_LONG).show()
                setBusy(false)
                dismiss()
            }
    }
}
