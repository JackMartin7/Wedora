package com.wedora.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.wedora.app.databinding.ActivityMatchCelebrationBinding

/**
 * The full-screen "It's mutual" moment, launched by [MatchCelebration] when a
 * match becomes mutual while the app is in the foreground.
 *
 * Deliberately dumb: it takes a uid, shows two faces, and offers the one action
 * the moment is about. All the decisions about WHEN to celebrate, and about
 * never celebrating the same match twice, live in MatchCelebration - this
 * screen cannot be reached any other way.
 *
 * Both photos are loaded here rather than passed in, because the celebration is
 * triggered from a match document that carries neither. Two reads on a rare,
 * deliberately-marked moment is a fair price; failures fall back to the
 * placeholder rather than blocking the screen.
 */
class MatchCelebrationActivity : WedoraBaseActivity() {

    companion object {
        private const val EXTRA_OTHER_UID = "other_uid"

        fun intent(context: Context, otherUid: String): Intent =
            Intent(context, MatchCelebrationActivity::class.java)
                .putExtra(EXTRA_OTHER_UID, otherUid)
    }

    private lateinit var binding: ActivityMatchCelebrationBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var otherName: String? = null

    private val otherUid: String
        get() = intent.getStringExtra(EXTRA_OTHER_UID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchCelebrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        if (otherUid.isEmpty()) {
            // Nothing to celebrate against; close rather than show two blanks.
            finish()
            return
        }

        loadProfile(FirebaseAuth.getInstance().realUid) { profile ->
            binding.ivCelebrationSelf.loadRemoteProfilePhoto(profile?.photoUrl)
        }
        loadProfile(otherUid) { profile ->
            otherName = profile?.displayName
            binding.ivCelebrationOther.loadRemoteProfilePhoto(profile?.photoUrl)
        }

        binding.btnCelebrationMessage.setOnClickListener {
            // The thread reads its own header from Firestore; this name is only
            // the placeholder shown for the instant before that lands, so an
            // empty string is honest rather than a guessed value.
            startActivity(ChatThreadActivity.intent(this, otherUid, otherName.orEmpty()))
            finish()
        }
        binding.btnCelebrationKeep.setOnClickListener { finish() }
    }

    private fun loadProfile(uid: String?, onResult: (UserProfile?) -> Unit) {
        if (uid.isNullOrEmpty()) {
            onResult(null)
            return
        }
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { onResult(UserProfile.from(it)) }
            .addOnFailureListener { onResult(null) }
    }
}
