package com.wedora.app

import android.os.Bundle
import android.util.Log
import android.view.View
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.wedora.app.databinding.ActivityAdminPhotoMigrationBinding
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "WedoraPhotoMigration"

/**
 * One-time admin tool: moves every remaining Hostinger-hosted profile photo
 * into Firebase Storage, matching exactly what [PhotoUploadService] now does
 * for every new upload — same object path, same cache-busted URL shape —
 * so a migrated user's photo is indistinguishable from one uploaded fresh.
 *
 * Reachable only via the hidden "Admin: Migrate Photos" row ProfileActivity
 * shows exclusively to [WedoraAdmin.UID]; re-checked here on every launch as
 * defense in depth, same as AdminReportsActivity.
 *
 * Processes one user at a time rather than in parallel — this runs once,
 * by hand, for however many users still have an old photoUrl; there's no
 * reason to add concurrency's failure modes (partial batches, harder-to-read
 * logs) for a tool whose entire job is to be simple and honest about what
 * it did. A failure on one user (most likely Hostinger being down for that
 * one request) is logged and skipped — it never stops the rest of the batch,
 * since the whole point is a clean pass over everyone still on the old host,
 * not an all-or-nothing transaction.
 */
class AdminPhotoMigrationActivity : WedoraBaseActivity() {

    private companion object {
        // Matches PushNotificationSender's ENDPOINT host and PhotoUploadService's
        // git history (the pre-Firebase upload target) — the one host every
        // legacy photoUrl was ever written pointing at.
        const val HOSTINGER_HOST = "tuberstec.com"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
    }

    private lateinit var binding: ActivityAdminPhotoMigrationBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val executor = Executors.newSingleThreadExecutor()

    private val queue = ArrayDeque<Pair<String, String>>()
    private var found = 0
    private var migrated = 0
    private var failed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser?.uid != WedoraAdmin.UID) {
            finish()
            return
        }

        binding = ActivityAdminPhotoMigrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnStart.setOnClickListener { startMigration() }
    }

    private fun startMigration() {
        binding.btnStart.isEnabled = false
        binding.btnStart.setText(R.string.admin_migration_running)
        binding.tvSummary.visibility = View.GONE
        binding.tvLog.text = ""
        found = 0
        migrated = 0
        failed = 0
        queue.clear()

        appendLog(getString(R.string.admin_migration_scanning))

        firestore.collection(UserProfile.COLLECTION).get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { doc ->
                    val url = doc.getString(UserProfile.FIELD_PHOTO_URL)
                    if (url != null && url.contains(HOSTINGER_HOST)) {
                        queue.addLast(doc.id to url)
                    }
                }
                found = queue.size

                if (queue.isEmpty()) {
                    appendLog(getString(R.string.admin_migration_none_found))
                    finishRun()
                    return@addOnSuccessListener
                }

                appendLog("Found $found photo(s) still on the old host — starting.")
                processNext()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to read users collection", e)
                appendLog(getString(R.string.admin_migration_scan_failed, e.message ?: e.toString()))
                resetButton()
            }
    }

    private fun processNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finishRun()
            return
        }
        val (uid, oldUrl) = next
        appendLog("→ $uid: downloading from old host…")

        executor.execute {
            try {
                val bytes = downloadBytes(oldUrl)
                runOnUiThread { uploadBytes(uid, bytes) }
            } catch (e: IOException) {
                Log.w(TAG, "Download failed for $uid", e)
                runOnUiThread { onOneFailed(uid, "download failed: ${e.message}") }
            }
        }
    }

    /** Same WAF-avoidance header PhotoUploadService's old Hostinger-era upload used. */
    private fun downloadBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WedoraApp")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadBytes(uid: String, bytes: ByteArray) {
        // Identical path PhotoUploadService writes to for a fresh upload —
        // a migrated photo and a freshly re-uploaded one land in the exact
        // same place, so there is nothing left to reconcile afterwards.
        val photoRef = FirebaseStorage.getInstance().reference
            .child("profile_photos/$uid/photo.jpg")

        photoRef.putBytes(bytes)
            .addOnSuccessListener {
                photoRef.downloadUrl
                    .addOnSuccessListener { uri ->
                        val cacheBustedUrl = "$uri&v=${System.currentTimeMillis()}"
                        firestore.collection(UserProfile.COLLECTION).document(uid)
                            .update(UserProfile.FIELD_PHOTO_URL, cacheBustedUrl)
                            .addOnSuccessListener { onOneSucceeded(uid) }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Firestore update failed for $uid", e)
                                onOneFailed(uid, "photoUrl update failed: ${e.message}")
                            }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Download URL fetch failed for $uid", e)
                        onOneFailed(uid, "download URL fetch failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Storage upload failed for $uid", e)
                onOneFailed(uid, "storage upload failed: ${e.message}")
            }
    }

    private fun onOneSucceeded(uid: String) {
        migrated++
        appendLog("✓ $uid migrated")
        processNext()
    }

    private fun onOneFailed(uid: String, reason: String) {
        failed++
        appendLog("✗ $uid: $reason")
        processNext()
    }

    private fun finishRun() {
        binding.tvSummary.text = getString(R.string.admin_migration_summary, found, migrated, failed)
        binding.tvSummary.visibility = View.VISIBLE
        resetButton()
    }

    private fun resetButton() {
        binding.btnStart.isEnabled = true
        binding.btnStart.setText(R.string.admin_migration_start)
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(if (binding.tvLog.text.isEmpty()) line else "\n$line")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
