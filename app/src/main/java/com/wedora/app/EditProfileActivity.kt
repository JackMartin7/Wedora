package com.wedora.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.wedora.app.databinding.ActivityEditProfileBinding
import java.io.File
import java.io.IOException

/**
 * Full profile editing, reached from ProfileActivity.
 *
 * The screen loads the user's Firestore document once and keeps it as
 * [loaded]. Every field is compared against that snapshot, which gives two
 * things: Save is disabled until something genuinely differs, and the write
 * carries only the changed fields rather than the whole document.
 *
 * The photo is device-local (see [LocalProfilePrefs]) and never goes to
 * Firestore, so it can't live in that diff — a picked photo is staged to a
 * separate file and tracked by [photoChanged], which counts towards enabling
 * Save exactly like an edited field. Nothing the rest of the app reads changes
 * until Save, so leaving without saving discards the pick.
 *
 * Editing city/country is plain text, with no GPS re-detection — but an edit
 * is still forward-geocoded in the background (see [scheduleManualGeocode])
 * so distance keeps working off an approximate fix rather than losing
 * coordinates outright the moment someone corrects a typo in their city.
 */
class EditProfileActivity : WedoraBaseActivity() {

    private companion object {
        const val TAG = "WedoraProfile"

        /** Same age policy as the profile-setup steps. */
        const val MIN_AGE = 18
        const val MAX_AGE = 120

        /**
         * A picked photo lands here and stays until Save, so leaving without
         * saving discards it. Kept separate from the UID-keyed file that
         * [LocalProfilePrefs] points at, which is only overwritten on Save.
         */
        const val STAGED_PHOTO_FILENAME = "profile_photos/staging_edit.jpg"

        /** How long edited city/country typing must settle before geocoding it. */
        const val GEOCODE_DEBOUNCE_MS = 600L
    }

    private lateinit var binding: ActivityEditProfileBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var genderControl: SegmentedControl
    private lateinit var interestedInControl: SegmentedControl

    private lateinit var uid: String

    /**
     * The profile as it was loaded. Null until the read completes, which is
     * also what keeps Save disabled during load — there is nothing to diff
     * against yet, so a change can't be detected.
     */
    private var loaded: UserProfile? = null

    private var isSaving = false

    /**
     * A new photo has been picked and staged but not yet persisted. Counts as
     * a change for the Save button exactly like an edited text field, and is
     * cleared once the staged file has been committed.
     */
    private var photoChanged = false

    /** The staged file backing [photoChanged]; deleted if the user leaves. */
    private var stagedPhotoFile: File? = null

    private val locationResolver by lazy { LocationResolver(this) }

    /**
     * Forward-geocoded coordinates for a city/country the user has actually
     * edited away from [loaded] — see [scheduleManualGeocode]. Null until a
     * resolve lands, on failure, or once the fields are back to matching
     * [loaded] (nothing new to resolve; the original coordinates, if any,
     * are still valid — see the geocode watcher in onCreate).
     */
    private var manualCoords: Pair<Double, Double>? = null

    private val geocodeHandler = Handler(Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null

    /** Same stale-result guard as ProfileStep4DetailsActivity's own token. */
    private var geocodeToken = 0

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) stagePickedPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets(binding.root, applyIme = true)

        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Toast.makeText(this, R.string.error_generic_login, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        uid = currentUid

        setUpSelectors()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.addPressScale()
        binding.ivEditPhoto.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.tvChangePhoto.setOnClickListener { pickImageLauncher.launch("image/*") }

        val watcher = SimpleWatcher { updateSaveEnabled() }
        binding.etName.addTextChangedListener(watcher)
        binding.etAge.addTextChangedListener(watcher)
        binding.etCity.addTextChangedListener(watcher)
        binding.etCountry.addTextChangedListener(watcher)
        binding.etBio.addTextChangedListener(SimpleWatcher {
            updateBioCounter()
            updateSaveEnabled()
        })

        // Separate from `watcher` above: only fires a geocode once city or
        // country actually diverges from `loaded`, so populate()'s initial
        // setText (which merely mirrors `loaded`) never triggers one, and
        // typing back to the original value cancels a pending one rather than
        // resolving coordinates nothing needs.
        val geocodeWatcher = SimpleWatcher {
            val original = loaded
            val changed = original != null &&
                (enteredCity() != original.city.orEmpty().trim() ||
                    enteredCountry() != original.country.orEmpty().trim())
            if (changed) {
                scheduleManualGeocode()
            } else {
                geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
                manualCoords = null
            }
        }
        binding.etCity.addTextChangedListener(geocodeWatcher)
        binding.etCountry.addTextChangedListener(geocodeWatcher)

        binding.ivEditPhoto.loadLocalProfilePhoto(this, uid)
        restoreOrClearStagedPhoto(savedInstanceState != null)
        updateBioCounter()
        updateSaveEnabled()
        loadProfile()
    }

    /**
     * The staging file outlives the activity instance, so it has to be
     * reconciled on every start.
     *
     * After a configuration change the pick is still current — adopt it, so
     * rotating doesn't silently revert the preview and disable Save. On a
     * fresh launch it's a leftover from a session that ended without saving
     * (or was killed mid-edit), so delete it rather than resurrect a pick the
     * user already walked away from.
     */
    private fun restoreOrClearStagedPhoto(isRecreated: Boolean) {
        val staging = File(filesDir, STAGED_PHOTO_FILENAME)
        if (!staging.exists()) return

        if (isRecreated) {
            stagedPhotoFile = staging
            photoChanged = true
            showPreview(staging)
        } else {
            staging.delete()
        }
    }

    /**
     * The segmented controls notify through the same hook as the text fields,
     * so tapping one enables Save exactly as typing does.
     */
    private fun setUpSelectors() {
        genderControl = SegmentedControl(
            listOf(
                binding.tvGenderMale to Gender.MALE,
                binding.tvGenderFemale to Gender.FEMALE,
                binding.tvGenderOther to Gender.OTHER
            ),
            onSelected = {
                // Both groups offer different options per gender — a man is a
                // widower, and looks for a wife rather than a marriage — so a
                // gender change rebuilds both. A selection absent from the new
                // list is dropped rather than carried across; the user picks
                // again from what now applies.
                showIntentOptions(
                    myStatus = binding.chipsMyStatus.selectedOption(),
                    lookingFor = binding.chipsLookingFor.selectedOption()
                )
                updateSaveEnabled()
            }
        )
        interestedInControl = SegmentedControl(
            listOf(
                binding.tvInterestedMale to Gender.MALE,
                binding.tvInterestedFemale to Gender.FEMALE,
                binding.tvInterestedOther to Gender.OTHER
            ),
            onSelected = { updateSaveEnabled() }
        )

        showIntentOptions(myStatus = null, lookingFor = null)
    }

    /** Rebuilds both intent groups for whichever gender is selected now. */
    private fun showIntentOptions(myStatus: String?, lookingFor: String?) {
        val gender = genderControl.selected?.firestoreValue
        binding.chipsMyStatus.setOptions(
            options = MarriageIntent.statusOptions(gender),
            selected = listOfNotNull(myStatus),
            onChanged = { updateSaveEnabled() }
        )
        binding.chipsLookingFor.setOptions(
            options = MarriageIntent.lookingForOptions(gender),
            selected = listOfNotNull(lookingFor),
            onChanged = { updateSaveEnabled() }
        )
    }

    // ----- Load -----------------------------------------------------------

    private fun loadProfile() {
        setFormVisible(false)
        firestore.collection(UserProfile.COLLECTION).document(uid).get()
            .addOnSuccessListener { snapshot ->
                val profile = UserProfile.from(snapshot)
                loaded = profile
                populate(profile)
                setFormVisible(true)
                updateSaveEnabled()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load profile for editing", e)
                Toast.makeText(this, R.string.edit_profile_load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
    }

    /**
     * Name falls back to the Auth display name: accounts created before the
     * Firestore user document existed have no displayName field there, and
     * showing an empty box would invite the user to blank their own name.
     */
    private fun populate(profile: UserProfile) {
        binding.etName.setText(
            profile.displayName?.takeIf { it.isNotBlank() }
                ?: auth.currentUser?.displayName.orEmpty()
        )
        binding.etAge.setText(profile.age?.toString().orEmpty())
        binding.etBio.setText(profile.bio.orEmpty())
        binding.etCity.setText(profile.city.orEmpty())
        binding.etCountry.setText(profile.country.orEmpty())

        genderFrom(profile.gender)?.let { genderControl.select(it) }
        genderFrom(profile.interestedIn)?.let { interestedInControl.select(it) }

        // After the gender control has been set, so the option lists match the
        // stored gender rather than the empty default.
        showIntentOptions(myStatus = profile.myStatus, lookingFor = profile.lookingFor)

        updateBioCounter()
    }

    /** Maps a stored Firestore value back to its [Gender], or null if unset. */
    private fun genderFrom(value: String?): Gender? =
        Gender.values().firstOrNull { it.firestoreValue == value }

    private fun setFormVisible(visible: Boolean) {
        binding.progressLoading.visibility = if (visible) View.GONE else View.VISIBLE
    }

    // ----- Change detection -----------------------------------------------

    /**
     * Debounced forward-geocode of the edited city/country into
     * [manualCoords], mirroring ProfileStep4DetailsActivity's own — see that
     * class's doc comment for why this needs no location permission.
     */
    private fun scheduleManualGeocode() {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        manualCoords = null

        val city = enteredCity()
        val country = enteredCountry()
        if (city.isEmpty() || country.isEmpty()) return

        val token = ++geocodeToken
        val runnable = Runnable {
            locationResolver.resolveCoordinates(
                city, country,
                onSuccess = { lat, lon -> if (token == geocodeToken) manualCoords = lat to lon },
                onFailure = { Log.i(TAG, "Could not forward-geocode edited city/country") }
            )
        }
        geocodeRunnable = runnable
        geocodeHandler.postDelayed(runnable, GEOCODE_DEBOUNCE_MS)
    }

    private fun enteredName() = binding.etName.text.toString().trim()
    private fun enteredCity() = binding.etCity.text.toString().trim()
    private fun enteredCountry() = binding.etCountry.text.toString().trim()
    private fun enteredBio() = binding.etBio.text.toString().trim()

    /**
     * Syntactically plausible age. As in the setup steps this does NOT
     * apply the 18+ policy — that is checked on Save so an under-age entry
     * gets an explicit message rather than a silently dead button.
     */
    private fun enteredAge(): Int? =
        binding.etAge.text.toString().trim().toIntOrNull()?.takeIf { it in 1..MAX_AGE }

    /**
     * Every field that differs from what was loaded, keyed by Firestore field
     * name. This is both the "is Save enabled" test and the actual write
     * payload, so the two can never disagree.
     *
     * Blank input maps to null rather than "", so clearing an optional field
     * (bio) is a real change and stores a null instead of an empty string.
     */
    private fun changedFields(): Map<String, Any?> {
        val original = loaded ?: return emptyMap()
        val changes = mutableMapOf<String, Any?>()

        val name = enteredName()
        if (name != original.displayName.orEmpty().trim()) {
            changes[UserProfile.FIELD_DISPLAY_NAME] = name
        }

        val age = enteredAge()
        if (age != original.age) {
            changes[UserProfile.FIELD_AGE] = age
        }

        val bio = enteredBio()
        if (bio != original.bio.orEmpty().trim()) {
            changes[UserProfile.FIELD_BIO] = bio.ifEmpty { null }
        }

        val city = enteredCity()
        if (city != original.city.orEmpty().trim()) {
            changes[UserProfile.FIELD_CITY] = city
        }

        val country = enteredCountry()
        if (country != original.country.orEmpty().trim()) {
            changes[UserProfile.FIELD_COUNTRY] = country
        }

        // This screen edits city/country by hand rather than re-detecting via
        // GPS, so any change here means the typed place no longer matches
        // whatever coordinates were stored. The geocode watcher in onCreate
        // forward-geocodes the new text as it's typed, so if that settled in
        // time [manualCoords] has an approximate replacement; otherwise the
        // stale fix is cleared rather than left pointing at the old city, and
        // distance falls open for this user until a later edit resolves.
        // delete() is a no-op when there were none to begin with.
        if (changes.containsKey(UserProfile.FIELD_CITY) ||
            changes.containsKey(UserProfile.FIELD_COUNTRY)
        ) {
            val coords = manualCoords
            if (coords != null) {
                // Coordinates are rounded to ~1km precision before storage as
                // a privacy measure — since Firestore rules currently allow
                // any signed-in user to read this field directly (needed for
                // the matching feed), this prevents raw coordinates from
                // revealing someone's precise/exact location even if read
                // outside the app's own distance-badge UI. Applies here (and
                // in ProfileStep4DetailsActivity) to any GPS-detected fix
                // too, if one is ever re-added to this screen.
                changes[UserProfile.FIELD_LATITUDE] = fuzzCoordinate(coords.first)
                changes[UserProfile.FIELD_LONGITUDE] = fuzzCoordinate(coords.second)
            } else {
                changes[UserProfile.FIELD_LATITUDE] = FieldValue.delete()
                changes[UserProfile.FIELD_LONGITUDE] = FieldValue.delete()
            }
        }

        val gender = genderControl.selected?.firestoreValue
        if (gender != null && gender != original.gender) {
            changes[UserProfile.FIELD_GENDER] = gender
        }

        val interestedIn = interestedInControl.selected?.firestoreValue
        if (interestedIn != null && interestedIn != original.interestedIn) {
            changes[UserProfile.FIELD_INTERESTED_IN] = interestedIn
        }

        val myStatus = binding.chipsMyStatus.selectedOption()
        if (myStatus != null && myStatus != original.myStatus) {
            changes[UserProfile.FIELD_MY_STATUS] = myStatus
        }

        val lookingFor = binding.chipsLookingFor.selectedOption()
        if (lookingFor != null && lookingFor != original.lookingFor) {
            changes[UserProfile.FIELD_LOOKING_FOR] = lookingFor
        }

        return changes
    }

    /**
     * A staged photo counts as a change on its own, so picking one and
     * touching nothing else still enables Save. The photo isn't a Firestore
     * field, so it can't live in [changedFields] — hence the separate flag.
     */
    private fun hasChanges(): Boolean = photoChanged || changedFields().isNotEmpty()

    private fun updateSaveEnabled() {
        val enabled = !isSaving && loaded != null && hasChanges()
        binding.btnSave.isEnabled = enabled
        // A TextView doesn't dim itself when disabled the way a button does.
        binding.btnSave.alpha = if (enabled) 1f else 0.4f
    }

    private fun updateBioCounter() {
        binding.tvBioCounter.text = getString(
            R.string.bio_counter_format,
            binding.etBio.text.length,
            UserProfile.MAX_BIO_LENGTH
        )
    }

    // ----- Photo ----------------------------------------------------------

    /**
     * Copies the picked image into internal storage straight away rather than
     * holding the picker's Uri, whose grant isn't guaranteed to outlive this
     * screen — same reasoning as SignUpActivity.
     *
     * It goes to a staging file, not to the live UID-keyed one. The preview
     * updates immediately so the choice is visible, but nothing the rest of
     * the app reads changes until Save: [LocalProfilePrefs] still points at
     * the old photo, so backing out discards the pick.
     */
    private fun stagePickedPhoto(uri: Uri) {
        try {
            val staging = File(filesDir, STAGED_PHOTO_FILENAME)
            staging.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("openInputStream returned null")

            stagedPhotoFile = staging
            photoChanged = true
            showPreview(staging)
            updateSaveEnabled()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy picked profile photo", e)
            Toast.makeText(this, R.string.error_photo_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Both caches are skipped because the staging path is identical between
     * picks — Glide would otherwise treat the second pick as the same image
     * and redisplay the first.
     */
    private fun showPreview(file: File) {
        Glide.with(this)
            .load(file)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .centerCrop()
            .into(binding.ivEditPhoto)
    }

    /**
     * Moves the staged photo onto the live UID-keyed file and points
     * [LocalProfilePrefs] at it. Returns false if the move failed, so the
     * caller can tell the user rather than silently keeping the old photo.
     *
     * Copy-then-delete rather than renameTo: both paths are in filesDir so a
     * rename would normally work, but a failed rename leaves no way to tell
     * whether the destination survived intact.
     *
     * Also kicks off [uploadThenSaveUrl] once the local copy is confirmed
     * good — fire-and-forget, same as ProfileStep5PhotoActivity's own upload;
     * this function's own success/failure is purely about the local file.
     */
    private fun commitStagedPhoto(): Boolean {
        val staging = stagedPhotoFile ?: return true
        return try {
            val destination = File(filesDir, "profile_photos/$uid.jpg")
            destination.parentFile?.mkdirs()
            staging.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            LocalProfilePrefs.setPhotoPath(this, uid, destination.absolutePath)

            staging.delete()
            stagedPhotoFile = null
            photoChanged = false
            uploadThenSaveUrl(destination)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Failed to commit staged profile photo", e)
            false
        }
    }

    /**
     * Fire-and-forget: never blocks Save from finishing, and a failure only
     * shows a toast — the local copy this device's own Profile screen reads
     * is already committed either way. Re-picking and saving again retries
     * this from scratch.
     *
     * The Firestore write is NOT gated on isFinishing — Save calls finish()
     * synchronously right after this fires off, well before the network
     * round trip completes, so by the time the callback lands the Activity
     * is routinely already finishing. A Firestore write has no dependency on
     * the Activity being alive; only the failure Toast (genuinely UI) is
     * guarded by isFinishing.
     */
    private fun uploadThenSaveUrl(file: File) {
        PhotoUploadService.uploadProfilePhoto(this, file.absolutePath, uid) { success, url, error ->
            if (success && url != null) {
                firestore.collection(UserProfile.COLLECTION).document(uid)
                    .set(mapOf(UserProfile.FIELD_PHOTO_URL to url), SetOptions.merge())
                    .addOnFailureListener { e ->
                        logFirestoreWriteFailure(TAG, "Uploaded photo but failed to save its url", e)
                    }
            } else {
                Log.w(TAG, "Profile photo upload failed: $error")
                if (!isFinishing) {
                    Toast.makeText(this, R.string.error_photo_upload_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Drops a staged photo the user never saved. */
    private fun discardStagedPhoto() {
        stagedPhotoFile?.delete()
        stagedPhotoFile = null
        photoChanged = false
    }

    // ----- Save -----------------------------------------------------------

    private fun save() {
        val changes = changedFields()
        if (!hasChanges()) return

        if (enteredName().isEmpty()) {
            binding.etName.error = getString(R.string.error_name_required)
            binding.etName.requestFocus()
            return
        }

        // Age is validated whenever one is present, not only when it changed:
        // the rules require the stored document to carry an 18+ age on every
        // update, so a bad age blocks an otherwise unrelated edit anyway.
        val ageText = binding.etAge.text.toString().trim()
        if (ageText.isEmpty()) {
            binding.etAge.error = getString(R.string.error_age_required)
            binding.etAge.requestFocus()
            return
        }
        val age = enteredAge()
        if (age == null) {
            binding.etAge.error = getString(R.string.error_age_invalid)
            binding.etAge.requestFocus()
            return
        }
        if (age < MIN_AGE) {
            binding.etAge.error = getString(R.string.error_age_minimum, MIN_AGE)
            binding.etAge.requestFocus()
            return
        }

        // Both are required. The chips can only be empty on a profile that
        // predates these fields — the completion gate normally fills them in
        // first, but an edit shouldn't be able to save without them either.
        if (binding.chipsMyStatus.selectedOption() == null) {
            Toast.makeText(this, R.string.error_my_status_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (binding.chipsLookingFor.selectedOption() == null) {
            Toast.makeText(this, R.string.error_looking_for_required, Toast.LENGTH_SHORT).show()
            return
        }

        setSaving(true)

        // Photo-only save: nothing to send, so skip the round trip entirely
        // rather than writing an empty merge.
        if (changes.isEmpty()) {
            persistPhotoThenFinish(changes)
            return
        }

        // merge, not update(): an account predating the Firestore user document
        // has none, and update() fails on a missing document.
        firestore.collection(UserProfile.COLLECTION).document(uid)
            .set(changes, SetOptions.merge())
            .addOnSuccessListener { persistPhotoThenFinish(changes) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to save profile edits", e)
                setSaving(false)
                Toast.makeText(this, R.string.error_profile_update_failed, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Commits the staged photo, after the Firestore write rather than before,
     * so a failed save leaves nothing persisted at all.
     *
     * A photo failure here is reported but doesn't block finishing when field
     * changes already went through — those really did save, and stranding the
     * user on the screen would invite them to save again.
     */
    private fun persistPhotoThenFinish(changes: Map<String, Any?>) {
        if (photoChanged && !commitStagedPhoto()) {
            Toast.makeText(this, R.string.error_photo_copy_failed, Toast.LENGTH_SHORT).show()
            if (changes.isEmpty()) {
                // Nothing at all was saved — stay put so the pick isn't lost.
                setSaving(false)
                return
            }
        }
        onSaved(changes)
    }

    /**
     * Firestore is the source of truth and has already been written, so a
     * failure to mirror the name onto the Auth profile is logged but not
     * surfaced — the save genuinely succeeded. The Auth displayName is kept in
     * sync only because Home and Profile read the greeting from it.
     */
    private fun onSaved(changes: Map<String, Any?>) {
        // Changing gender or who they're interested in re-words the status and
        // looking-for options, so any filter built from the old list is
        // dropped. Left alone it would keep narrowing the feed while the filter
        // screen — now showing the other wording — offered no way to clear it.
        //
        // interestedIn matters because the filter's options follow the gender
        // of the people being filtered, not the viewer's own.
        if (changes.containsKey(UserProfile.FIELD_GENDER) ||
            changes.containsKey(UserProfile.FIELD_INTERESTED_IN)
        ) {
            FilterPrefs.clearIntentFilters(this)
        }

        val newName = changes[UserProfile.FIELD_DISPLAY_NAME] as? String
        if (newName.isNullOrBlank()) {
            finishSaved()
            return
        }

        val user = auth.currentUser
        if (user == null) {
            finishSaved()
            return
        }

        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(newName).build()
        ).addOnCompleteListener {
            if (!it.isSuccessful) {
                Log.w(TAG, "Saved profile but failed to update Auth displayName", it.exception)
            }
            finishSaved()
        }
    }

    private fun finishSaved() {
        Toast.makeText(this, R.string.profile_updated, Toast.LENGTH_SHORT).show()
        // ProfileActivity re-reads Firestore in onResume, so returning to it is
        // all that's needed for the change to show.
        finish()
    }

    private fun setSaving(saving: Boolean) {
        isSaving = saving
        binding.btnSave.setText(if (saving) R.string.btn_saving else R.string.btn_save)
        updateSaveEnabled()
    }

    /**
     * Cleans up a staged photo the user never saved — the back arrow, the
     * system back gesture and a swipe-away all land here. [commitStagedPhoto]
     * clears the flag on success, so a saved photo is never deleted.
     *
     * Guarded on isFinishing so a configuration change — which destroys the
     * activity without the user leaving — keeps the staged file for
     * [restoreOrClearStagedPhoto] to adopt.
     */
    override fun onDestroy() {
        if (isFinishing) discardStagedPhoto()
        super.onDestroy()
    }

    /** Minimal TextWatcher so the fields can share one re-validate hook. */
    private class SimpleWatcher(private val onChanged: () -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
    }
}
