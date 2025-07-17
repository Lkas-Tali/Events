package com.student.events

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.student.events.databinding.ActivityCreateEventBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CreateEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateEventBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var storage: FirebaseStorage

    private var selectedImageUri: Uri? = null
    private var selectedDate: String = ""
    private var selectedTime: String = ""

    // Edit mode variables
    private var isEditMode = false
    private var eventId: String? = null
    private var existingImageUrl: String? = null

    // Invitation variables
    private val invitedUsers = mutableMapOf<String, InvitedUser>() // email -> InvitedUser
    private var isCheckingEmail = false

    data class InvitedUser(
        val email: String,
        val fullName: String,
        val userId: String,
        val profileImageUrl: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display - EXACTLY like MainActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Configure system bar appearance - EXACTLY like MainActivity
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        binding = ActivityCreateEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        storage = FirebaseStorage.getInstance()

        isEditMode = intent.getBooleanExtra("editMode", false)
        if (isEditMode) {
            setupEditMode()
        }

        // Apply system bar insets - EXACTLY like MainActivity
        applySystemBarInsets()

        setupViews()
        setupInvitationFeature()
    }

    // COPIED EXACTLY from MainActivity
    private fun applySystemBarInsets() {
        val header = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.nestedScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    private fun setupEditMode() {
        binding.pageTitle.text = "Edit Event"
        binding.createButton.text = "Save Changes"

        eventId = intent.getStringExtra("eventId")
        binding.titleInput.setText(intent.getStringExtra("eventTitle"))
        binding.locationInput.setText(intent.getStringExtra("eventLocation"))
        binding.descriptionInput.setText(intent.getStringExtra("eventDescription"))
        existingImageUrl = intent.getStringExtra("eventImage")

        // COMPATIBILITY: Load date/time from database to handle both formats
        eventId?.let { id ->
            database.reference.child("events").child(id)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        loadExistingDateTime(snapshot)
                        binding.dateInput.setText(formatDateForDisplay(selectedDate))
                        binding.timeInput.setText(selectedTime)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        // Use fallback values from intent if available
                        selectedDate = intent.getStringExtra("eventDate") ?: ""
                        selectedTime = intent.getStringExtra("eventTime") ?: ""
                        binding.dateInput.setText(formatDateForDisplay(selectedDate))
                        binding.timeInput.setText(selectedTime)
                    }
                })
        }

        existingImageUrl?.let { url ->
            if (url.isNotEmpty()) {
                binding.imagePreview.visibility = View.VISIBLE
                Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.image_placeholder)
                    .into(binding.imagePreview)
            }
        }

        // Load existing invitations if in edit mode
        loadExistingInvitations()
    }

    private fun setupViews() {
        // Updated back button setup - now using ImageView like ProfileActivity
        binding.backButton.setOnClickListener { finish() }

        binding.dateInput.setOnClickListener { showDatePicker() }
        binding.timeInput.setOnClickListener { showTimePicker() }
        binding.imageUploadArea.setOnClickListener { selectImage() }
        binding.cancelButton.setOnClickListener { finish() }
        binding.createButton.setOnClickListener {
            if (validateInputs()) {
                handleEventCreationOrUpdate()
            }
        }
    }

    private fun setupInvitationFeature() {
        // Toggle invite section visibility
        binding.invitePeopleHeader.setOnClickListener {
            val isVisible = binding.inviteSection.visibility == View.VISIBLE
            binding.inviteSection.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.inviteArrow.rotation = if (isVisible) 0f else 180f
        }

        // Clear email input error when user starts typing
        binding.emailInput.addTextChangedListener {
            binding.emailInputLayout.error = null
            binding.emailValidationIcon.visibility = View.GONE
        }

        // Handle email input
        binding.emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val email = binding.emailInput.text.toString().trim()
                if (email.isNotEmpty()) {
                    validateAndAddEmail(email)
                }
                true
            } else {
                false
            }
        }

        // Add email button
        binding.addEmailButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            if (email.isNotEmpty()) {
                validateAndAddEmail(email)
            }
        }
    }

    private fun validateAndAddEmail(email: String) {
        // Basic email validation
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Please enter a valid email address"
            return
        }

        // Check if already invited
        if (invitedUsers.containsKey(email)) {
            binding.emailInputLayout.error = "This person is already invited"
            return
        }

        // Check if it's the current user's email
        if (email == auth.currentUser?.email) {
            binding.emailInputLayout.error = "You cannot invite yourself"
            return
        }

        // Show loading state
        isCheckingEmail = true
        binding.emailValidationIcon.visibility = View.VISIBLE
        binding.emailValidationIcon.setImageResource(R.drawable.ic_loading)
        binding.addEmailButton.isEnabled = false

        // Check if user exists in database
        database.reference.child("users")
            .orderByChild("email")
            .equalTo(email)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    isCheckingEmail = false
                    binding.addEmailButton.isEnabled = true

                    if (snapshot.exists()) {
                        // User found
                        val userSnapshot = snapshot.children.first()
                        val userId = userSnapshot.key ?: ""
                        val fullName = userSnapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                        val profileImageUrl = userSnapshot.child("profileImageUrl").getValue(String::class.java)

                        val invitedUser = InvitedUser(email, fullName, userId, profileImageUrl)
                        invitedUsers[email] = invitedUser

                        // Show success and add chip
                        binding.emailValidationIcon.setImageResource(R.drawable.ic_check_circle)
                        binding.emailValidationIcon.visibility = View.VISIBLE

                        // Add chip and clear input
                        addInvitedUserChip(invitedUser)
                        binding.emailInput.setText("")

                        // Hide success icon after a moment
                        binding.emailValidationIcon.postDelayed({
                            binding.emailValidationIcon.visibility = View.GONE
                        }, 1500)

                    } else {
                        // User not found
                        binding.emailInputLayout.error = "User not found. Make sure they have an account."
                        binding.emailValidationIcon.setImageResource(R.drawable.ic_error)
                        binding.emailValidationIcon.visibility = View.VISIBLE

                        // Hide error icon after a moment
                        binding.emailValidationIcon.postDelayed({
                            binding.emailValidationIcon.visibility = View.GONE
                        }, 3000)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    isCheckingEmail = false
                    binding.addEmailButton.isEnabled = true
                    binding.emailInputLayout.error = "Error checking user. Please try again."
                    binding.emailValidationIcon.visibility = View.GONE
                }
            })
    }

    private fun addInvitedUserChip(invitedUser: InvitedUser) {
        val chip = Chip(this)
        chip.text = invitedUser.fullName
        chip.isCloseIconVisible = true
        chip.setChipBackgroundColorResource(R.color.app_primary_blue)
        chip.setTextColor(resources.getColor(android.R.color.white, null))
        chip.setCloseIconTintResource(android.R.color.white)

        chip.setOnCloseIconClickListener {
            binding.invitedUsersChipGroup.removeView(chip)
            invitedUsers.remove(invitedUser.email)
            updateInviteCounter()
        }

        binding.invitedUsersChipGroup.addView(chip)
        updateInviteCounter()
    }

    private fun updateInviteCounter() {
        val count = invitedUsers.size
        binding.inviteCountText.text = if (count > 0) {
            "$count ${if (count == 1) "person" else "people"} invited"
        } else {
            "No one invited yet"
        }
        binding.inviteCountText.visibility = View.VISIBLE
    }

    private fun loadExistingInvitations() {
        if (!isEditMode || eventId == null) return

        database.reference.child("events").child(eventId!!).child("invitations")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (invitationSnapshot in snapshot.children) {
                        val userId = invitationSnapshot.key ?: continue
                        val email = invitationSnapshot.child("email").getValue(String::class.java) ?: continue
                        val fullName = invitationSnapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                        val profileImageUrl = invitationSnapshot.child("profileImageUrl").getValue(String::class.java)

                        val invitedUser = InvitedUser(email, fullName, userId, profileImageUrl)
                        invitedUsers[email] = invitedUser
                        addInvitedUserChip(invitedUser)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Handle error silently
                }
            })
    }

    private fun handleEventCreationOrUpdate() {
        setLoading(true)
        if (selectedImageUri != null) {
            uploadImageThenSaveEvent()
        } else {
            saveEventToDatabase(existingImageUrl)
        }
    }

    private fun uploadImageThenSaveEvent() {
        try {
            val storageRef = storage.reference.child("event_images/${UUID.randomUUID()}.jpg")
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val data = baos.toByteArray()

            storageRef.putBytes(data)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { uri ->
                        saveEventToDatabase(uri.toString())
                    }.addOnFailureListener {
                        setLoading(false)
                        Toast.makeText(this, "Failed to get image URL.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    setLoading(false)
                    Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }

        } catch (e: Exception) {
            setLoading(false)
            Toast.makeText(this, "Failed to process image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveEventToDatabase(imageUrl: String?) {
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        if (isEditMode) {
            // Update existing event
            eventId?.let { id ->
                val updates = hashMapOf<String, Any?>(
                    "title" to title,
                    "location" to location,
                    "description" to description,
                    "dateTime" to getCombinedDateTime(),
                    "imageUrl" to imageUrl
                )

                // Add invitations if any
                if (invitedUsers.isNotEmpty()) {
                    val invitationsMap = mutableMapOf<String, Any>()
                    invitedUsers.values.forEach { invitedUser ->
                        invitationsMap[invitedUser.userId] = mapOf(
                            "email" to invitedUser.email,
                            "fullName" to invitedUser.fullName,
                            "profileImageUrl" to (invitedUser.profileImageUrl ?: ""),
                            "status" to "pending",
                            "invitedAt" to ServerValue.TIMESTAMP
                        )
                    }
                    updates["invitations"] = invitationsMap
                }

                database.reference.child("events").child(id).updateChildren(updates)
                    .addOnSuccessListener {
                        sendInvitationNotifications(id, title)
                        setLoading(false)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        setLoading(false)
                        Toast.makeText(this, "Failed to update event: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        } else {
            // Create new event
            val user = auth.currentUser ?: return
            val userId = user.uid

            // Fetch the user's full name from the Realtime Database before creating the event.
            database.reference.child("users").child(userId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val userName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown"
                    val userPhotoUrl = user.photoUrl?.toString() ?: ""

                    val creatorAsAttendee = mapOf(
                        "fullName" to userName,
                        "profileImageUrl" to userPhotoUrl
                    )

                    val event = hashMapOf(
                        "title" to title,
                        "location" to location,
                        "description" to description,
                        "imageUrl" to (imageUrl ?: ""),
                        "organizer" to mapOf("uid" to userId, "fullName" to userName),
                        "attendees" to mapOf(userId to creatorAsAttendee),
                        "attendeesCount" to 1,
                        "status" to "upcoming",
                        "dateTime" to getCombinedDateTime()
                    )

                    // Add invitations if any
                    if (invitedUsers.isNotEmpty()) {
                        val invitationsMap = mutableMapOf<String, Any>()
                        invitedUsers.values.forEach { invitedUser ->
                            invitationsMap[invitedUser.userId] = mapOf(
                                "email" to invitedUser.email,
                                "fullName" to invitedUser.fullName,
                                "profileImageUrl" to (invitedUser.profileImageUrl ?: ""),
                                "status" to "pending",
                                "invitedAt" to ServerValue.TIMESTAMP
                            )
                        }
                        event["invitations"] = invitationsMap
                    }

                    val eventRef = database.reference.child("events").push()
                    eventRef.setValue(event)
                        .addOnSuccessListener {
                            sendInvitationNotifications(eventRef.key!!, title)
                            setLoading(false)
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            Toast.makeText(this@CreateEventActivity, "Failed to create event: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    setLoading(false)
                    Toast.makeText(this@CreateEventActivity, "Failed to get user details: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun sendInvitationNotifications(eventId: String, eventTitle: String) {
        if (invitedUsers.isEmpty()) return

        // Get current user's name from database for accurate notification
        database.reference.child("users").child(auth.currentUser?.uid ?: "")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val currentUserName = snapshot.child("fullName").getValue(String::class.java) ?: "Someone"

                    invitedUsers.values.forEach { invitedUser ->
                        // Send notification using existing notification structure
                        val notification = mapOf(
                            "type" to "invitation",
                            "text" to "$currentUserName invited you to \"$eventTitle\"",
                            "timestamp" to ServerValue.TIMESTAMP,
                            "read" to false,
                            // Additional fields for invitation handling
                            "eventId" to eventId,
                            "eventTitle" to eventTitle,
                            "organizerName" to currentUserName
                        )

                        // Send notification to invited user using existing pattern
                        database.reference.child("notifications").child(invitedUser.userId).push().setValue(notification)

                        // COMPATIBILITY: Also add to user's invitations for tracking
                        val userInvitation = mapOf(
                            "eventId" to eventId,
                            "eventTitle" to eventTitle,
                            "organizerName" to currentUserName,
                            "status" to "pending",
                            "invitedAt" to ServerValue.TIMESTAMP
                        )
                        database.reference.child("users").child(invitedUser.userId).child("invitations").child(eventId).setValue(userInvitation)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Use fallback name if database read fails
                    val fallbackName = auth.currentUser?.displayName ?: "Someone"
                    invitedUsers.values.forEach { invitedUser ->
                        val notification = mapOf(
                            "type" to "invitation",
                            "text" to "$fallbackName invited you to \"$eventTitle\"",
                            "timestamp" to ServerValue.TIMESTAMP,
                            "read" to false,
                            "eventId" to eventId,
                            "eventTitle" to eventTitle,
                            "organizerName" to fallbackName
                        )
                        database.reference.child("notifications").child(invitedUser.userId).push().setValue(notification)
                    }
                }
            })
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        if (isEditMode && selectedDate.isNotEmpty()) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate)
                date?.let { calendar.time = it }
            } catch (e: Exception) { /* Ignore */ }
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
                binding.dateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        if (isEditMode && selectedTime.isNotEmpty()) {
            try {
                val parts = selectedTime.split(":")
                if (parts.size == 2) {
                    calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    calendar.set(Calendar.MINUTE, parts[1].toInt())
                }
            } catch (e: Exception) { /* Ignore */ }
        }
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                selectedTime = String.format("%02d:%02d", hourOfDay, minute)
                binding.timeInput.setText(selectedTime)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, IMAGE_PICK_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                selectedImageUri = uri
                binding.imagePreview.visibility = View.VISIBLE
                binding.imagePreview.setImageURI(uri)
                binding.uploadText.text = "Click to change image"
            }
        }
    }

    private fun validateInputs(): Boolean {
        if (isCheckingEmail) {
            Toast.makeText(this, "Please wait while we validate the email address", Toast.LENGTH_SHORT).show()
            return false
        }

        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        when {
            title.isEmpty() -> {
                binding.titleInputLayout.error = "Event title is required"
                return false
            }
            selectedDate.isEmpty() -> {
                Toast.makeText(this, "Please select a date", Toast.LENGTH_SHORT).show()
                return false
            }
            selectedTime.isEmpty() -> {
                Toast.makeText(this, "Please select a time", Toast.LENGTH_SHORT).show()
                return false
            }
            location.isEmpty() -> {
                binding.locationInputLayout.error = "Location is required"
                return false
            }
            description.isEmpty() -> {
                binding.descriptionInputLayout.error = "Description is required"
                return false
            }
        }
        return true
    }

    private fun getCombinedDateTime(): Map<String, Long>? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val date = format.parse("$selectedDate $selectedTime")
            mapOf("_seconds" to (date!!.time / 1000), "_nanoseconds" to 0)
        } catch (e: Exception) {
            null
        }
    }

    // COMPATIBILITY: Handle both old and new date formats
    private fun loadExistingDateTime(eventSnapshot: DataSnapshot) {
        // Try new format first
        val dateTimeSnapshot = eventSnapshot.child("dateTime")
        if (dateTimeSnapshot.exists()) {
            val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java) ?: 0
            if (seconds > 0) {
                val date = Date(seconds * 1000)
                selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                selectedTime = SimpleDateFormat("HH:mm", Locale.US).format(date)
                return
            }
        }

        // Fall back to old format
        val oldDate = eventSnapshot.child("date").getValue(String::class.java)
        val oldTime = eventSnapshot.child("time").getValue(String::class.java)
        if (!oldDate.isNullOrEmpty() && !oldTime.isNullOrEmpty()) {
            selectedDate = oldDate
            selectedTime = oldTime
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.createButton.isEnabled = false
            binding.cancelButton.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            binding.createButton.isEnabled = true
            binding.cancelButton.isEnabled = true
        }
    }

    private fun formatDateForDisplay(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("dd/MM/yyyy", Locale.UK)
            val date = inputFormat.parse(dateString)
            date?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val IMAGE_PICK_REQUEST = 1001
    }
}