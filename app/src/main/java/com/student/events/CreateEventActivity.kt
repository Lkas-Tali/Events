package com.student.events

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.student.events.databinding.ActivityCreateEventBinding
import com.student.events.services.EmailService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Activity for creating new events or editing existing ones.
 * Supports image upload, date/time selection, user invitations via email,
 * and comprehensive input validation with enhanced keyboard handling.
 */
class CreateEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateEventBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var storage: FirebaseStorage
    private lateinit var emailService: EmailService

    private var selectedImageUri: Uri? = null
    private var selectedDate: String = ""
    private var selectedTime: String = ""

    // Edit mode properties
    private var isEditMode = false
    private var eventId: String? = null
    private var existingImageUrl: String? = null

    // Invitation management
    private val invitedUsers = mutableMapOf<String, InvitedUser>()
    private var isCheckingEmail = false

    // Enhanced keyboard handling
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var isKeyboardShowing = false
    private val keyboardHandler = Handler(Looper.getMainLooper())

    /**
     * Data class representing a user invited to the event
     */
    data class InvitedUser(
        val email: String,
        val fullName: String,
        val userId: String,
        val profileImageUrl: String? = null
    )

    companion object {
        private const val IMAGE_PICK_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCreateEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeServices()
        checkEditMode()
        setupUserInterface()
        setupInvitationFeature()
        setupKeyboardHandling()
        setupTouchToDismissKeyboard()
    }

    /**
     * Initialize Firebase services and dependencies
     */
    private fun initializeServices() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        storage = FirebaseStorage.getInstance()
        emailService = EmailService(this)
    }

    /**
     * Check if activity was launched in edit mode and populate existing data
     */
    private fun checkEditMode() {
        isEditMode = intent.getBooleanExtra("editMode", false)
        if (isEditMode) {
            setupEditMode()
        }
    }

    /**
     * Configure activity for editing an existing event
     */
    private fun setupEditMode() {
        binding.pageTitle.text = "Edit Event"
        binding.createButton.text = "Save Changes"

        // Extract event data from intent
        eventId = intent.getStringExtra("eventId")
        binding.titleInput.setText(intent.getStringExtra("eventTitle"))
        binding.locationInput.setText(intent.getStringExtra("eventLocation"))
        binding.descriptionInput.setText(intent.getStringExtra("eventDescription"))
        existingImageUrl = intent.getStringExtra("eventImage")

        // Load date/time from database for compatibility with different formats
        eventId?.let { id ->
            database.reference.child("events").child(id)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        loadExistingDateTime(snapshot)
                        binding.dateInput.setText(formatDateForDisplay(selectedDate))
                        binding.timeInput.setText(selectedTime)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        // Use fallback values from intent if database access fails
                        selectedDate = intent.getStringExtra("eventDate") ?: ""
                        selectedTime = intent.getStringExtra("eventTime") ?: ""
                        binding.dateInput.setText(formatDateForDisplay(selectedDate))
                        binding.timeInput.setText(selectedTime)
                    }
                })
        }

        // Load existing event image
        existingImageUrl?.let { url ->
            if (url.isNotEmpty()) {
                binding.imagePreview.visibility = View.VISIBLE
                Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.image_placeholder)
                    .into(binding.imagePreview)
            }
        }

        loadExistingInvitations()
    }

    /**
     * Setup main UI components and event handlers
     */
    private fun setupUserInterface() {
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

    /**
     * Configure invitation feature with email validation and user lookup
     */
    private fun setupInvitationFeature() {
        // Toggle invitation section visibility
        binding.invitePeopleHeader.setOnClickListener {
            val isVisible = binding.inviteSection.visibility == View.VISIBLE

            if (!isVisible) {
                binding.inviteSection.visibility = View.VISIBLE
                binding.inviteArrow.rotation = 180f
                autoScrollToInviteSection()
            } else {
                binding.inviteSection.visibility = View.GONE
                binding.inviteArrow.rotation = 0f
                hideKeyboard()
                binding.emailInput.clearFocus()
            }
        }

        // Clear validation errors when user starts typing
        binding.emailInput.addTextChangedListener {
            binding.emailInputLayout.error = null
            binding.emailValidationIcon.visibility = View.GONE
        }

        // Enhanced focus handling for email input
        binding.emailInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                keyboardHandler.postDelayed({
                    ensureEmailInputVisible()
                }, 200)
            }
        }

        // Handle email input submission
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

        // Add email button handler
        binding.addEmailButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            if (email.isNotEmpty()) {
                validateAndAddEmail(email)
            }
        }

        binding.emailInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }

    /**
     * Auto-scroll to invitation section when opened
     */
    private fun autoScrollToInviteSection() {
        binding.nestedScrollView.post {
            val padding = 100
            val targetY = binding.invitePeopleHeader.top - padding
            binding.nestedScrollView.smoothScrollTo(0, targetY)

            // Focus on email input after scrolling
            keyboardHandler.postDelayed({
                binding.emailInput.requestFocus()
                showKeyboard(binding.emailInput)
            }, 300)
        }
    }

    /**
     * Setup enhanced keyboard handling for better UX
     */
    private fun setupKeyboardHandling() {
        // Add extra padding to scrollable content for keyboard accommodation
        binding.nestedScrollView.getChildAt(0).apply {
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + 300)
        }

        // Setup keyboard visibility detection
        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            binding.root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = binding.root.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            val isKeyboardNowShowing = keypadHeight > screenHeight * 0.15

            if (isKeyboardNowShowing != isKeyboardShowing) {
                isKeyboardShowing = isKeyboardNowShowing

                if (isKeyboardShowing) {
                    onKeyboardOpened(keypadHeight)
                } else {
                    onKeyboardClosed()
                }
            }
        }

        binding.root.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
    }

    /**
     * Handle keyboard opened event with smart scrolling
     */
    private fun onKeyboardOpened(keyboardHeight: Int) {
        // Check if email input is focused and ensure it's visible
        if (binding.emailInput.hasFocus()) {
            keyboardHandler.postDelayed({
                ensureEmailInputVisible()
            }, 100)
        }
    }

    /**
     * Handle keyboard closed event
     */
    private fun onKeyboardClosed() {
        // Keyboard closed - can perform cleanup if needed
    }

    /**
     * Ensure email input field is visible above keyboard
     */
    private fun ensureEmailInputVisible() {
        val emailInputLocation = IntArray(2)
        binding.emailInput.getLocationOnScreen(emailInputLocation)

        val scrollViewLocation = IntArray(2)
        binding.nestedScrollView.getLocationOnScreen(scrollViewLocation)

        // Calculate relative position within scroll view
        val emailInputTop = emailInputLocation[1] - scrollViewLocation[1] + binding.nestedScrollView.scrollY

        // Get visible height (screen height - keyboard height)
        val rect = Rect()
        binding.root.getWindowVisibleDisplayFrame(rect)
        val visibleHeight = rect.bottom - scrollViewLocation[1]

        // Calculate optimal scroll position
        val desiredScrollY = emailInputTop - (visibleHeight / 2) + (binding.emailInput.height / 2)

        // Smooth scroll to calculated position
        binding.nestedScrollView.smoothScrollTo(0, desiredScrollY.coerceAtLeast(0))
    }

    /**
     * Setup touch handling to dismiss keyboard when touching outside input fields
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    hideKeyboard()
                    v.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Configure touch handling for keyboard dismissal
     */
    private fun setupTouchToDismissKeyboard() {
        binding.nestedScrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val v = currentFocus
                if (v is android.widget.EditText) {
                    val outRect = Rect()
                    v.getGlobalVisibleRect(outRect)
                    if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        hideKeyboard()
                        v.clearFocus()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    /**
     * Hide soft keyboard
     */
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = currentFocus
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
        }
    }

    /**
     * Show soft keyboard for specified view
     */
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Add invited user as a removable chip in the UI
     */
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

    /**
     * Update invitation counter display
     */
    private fun updateInviteCounter() {
        val count = invitedUsers.size
        binding.inviteCountText.text = if (count > 0) {
            "$count ${if (count == 1) "person" else "people"} invited"
        } else {
            "No one invited yet"
        }
        binding.inviteCountText.visibility = View.VISIBLE
    }

    /**
     * Load existing invitations when editing an event
     */
    private fun loadExistingInvitations() {
        if (!isEditMode || eventId == null) return

        database.reference.child("events").child(eventId!!).child("invitations")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var loadedCount = 0
                    for (invitationSnapshot in snapshot.children) {
                        val userId = invitationSnapshot.key ?: continue
                        val email = invitationSnapshot.child("email").getValue(String::class.java) ?: continue
                        val fullName = invitationSnapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                        val profileImageUrl = invitationSnapshot.child("profileImageUrl").getValue(String::class.java)

                        val invitedUser = InvitedUser(email, fullName, userId, profileImageUrl)
                        invitedUsers[email] = invitedUser
                        addInvitedUserChip(invitedUser)
                        loadedCount++
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Handle error silently for better UX
                }
            })
    }

    /**
     * Handle event creation or update based on current mode
     */
    private fun handleEventCreationOrUpdate() {
        setLoading(true)
        if (selectedImageUri != null) {
            uploadImageThenSaveEvent()
        } else {
            saveEventToDatabase(existingImageUrl)
        }
    }

    /**
     * Upload selected image to Firebase Storage then save event
     */
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
                        handleUploadError("Failed to get image URL.")
                    }
                }
                .addOnFailureListener { e ->
                    handleUploadError("Image upload failed: ${e.message}")
                }

        } catch (e: Exception) {
            handleUploadError("Failed to process image.")
        }
    }

    /**
     * Handle image upload errors
     */
    private fun handleUploadError(message: String) {
        setLoading(false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Save event data to Firebase Database
     */
    private fun saveEventToDatabase(imageUrl: String?) {
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        if (isEditMode) {
            updateExistingEvent(title, location, description, imageUrl)
        } else {
            createNewEvent(title, location, description, imageUrl)
        }
    }

    /**
     * Update existing event in database
     */
    private fun updateExistingEvent(title: String, location: String, description: String, imageUrl: String?) {
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
                    finishWithSuccess()
                }
                .addOnFailureListener { e ->
                    handleSaveError("Failed to update event: ${e.message}")
                }
        }
    }

    /**
     * Create new event in database
     */
    private fun createNewEvent(title: String, location: String, description: String, imageUrl: String?) {
        val user = auth.currentUser ?: return
        val userId = user.uid

        // Get user details before creating event
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
                        finishWithSuccess()
                    }
                    .addOnFailureListener { e ->
                        handleSaveError("Failed to create event: ${e.message}")
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                handleSaveError("Failed to get user details: ${error.message}")
            }
        })
    }

    /**
     * Send invitation notifications and emails to invited users
     */
    private fun sendInvitationNotifications(eventId: String, eventTitle: String) {
        if (invitedUsers.isEmpty()) return

        database.reference.child("users").child(auth.currentUser?.uid ?: "")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val currentUserName = snapshot.child("fullName").getValue(String::class.java) ?: "Someone"
                    val currentUserEmail = snapshot.child("email").getValue(String::class.java) ?: ""

                    database.reference.child("events").child(eventId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(eventSnapshot: DataSnapshot) {
                                val eventLocation = eventSnapshot.child("location").getValue(String::class.java) ?: ""
                                val eventDescription = eventSnapshot.child("description").getValue(String::class.java) ?: ""
                                val eventDateFormatted = formatEventDateForEmail(eventSnapshot)

                                val emailsAttempted = AtomicInteger(0)
                                val emailsSuccessful = AtomicInteger(0)
                                val emailsFailed = AtomicInteger(0)
                                val totalEmails = invitedUsers.size

                                invitedUsers.values.forEach { invitedUser ->
                                    CoroutineScope(Dispatchers.Main).launch {
                                        val (emailSuccess, _) = emailService.sendEventInvitation(
                                            recipientEmail = invitedUser.email,
                                            recipientName = invitedUser.fullName,
                                            organizerName = currentUserName,
                                            organizerEmail = currentUserEmail,
                                            eventTitle = eventTitle,
                                            eventDate = eventDateFormatted,
                                            eventLocation = eventLocation,
                                            eventDescription = eventDescription
                                        )

                                        if (emailSuccess) {
                                            emailsSuccessful.incrementAndGet()
                                        } else {
                                            emailsFailed.incrementAndGet()
                                        }

                                        // Create notification regardless of email success
                                        if (emailSuccess) {
                                            createInvitationNotificationWithEmail(
                                                invitedUser.userId, currentUserName, eventId, eventTitle, invitedUser.email
                                            )
                                        } else {
                                            createFallbackInvitationNotification(
                                                invitedUser.userId, currentUserName, eventId, eventTitle
                                            )
                                        }

                                        // Show summary when all emails processed
                                        if (emailsAttempted.incrementAndGet() == totalEmails) {
                                            showEmailSummary(emailsSuccessful.get(), emailsFailed.get(), totalEmails)
                                        }
                                    }
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                sendFallbackInvitationNotifications(eventId, eventTitle, currentUserName)
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    val fallbackName = auth.currentUser?.displayName ?: "Someone"
                    sendFallbackInvitationNotifications(eventId, eventTitle, fallbackName)
                }
            })
    }

    /**
     * Show summary of email invitation results
     */
    private fun showEmailSummary(successful: Int, failed: Int, total: Int) {
        runOnUiThread {
            when {
                successful == total -> {
                    Toast.makeText(this@CreateEventActivity,
                        "All $successful invitations sent successfully!",
                        Toast.LENGTH_LONG).show()
                }
                successful > 0 -> {
                    Toast.makeText(this@CreateEventActivity,
                        "$successful/$total invitations sent (some emails failed)",
                        Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(this@CreateEventActivity,
                        "Email invitations failed, but notifications were sent",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Create notification with email reference for successful email invitations
     */
    private fun createInvitationNotificationWithEmail(
        userId: String,
        organizerName: String,
        eventId: String,
        eventTitle: String,
        recipientEmail: String
    ) {
        try {
            val notification = mapOf(
                "type" to "invitation",
                "text" to "$organizerName invited you to \"$eventTitle\". Check your email for full details and RSVP in the app.",
                "timestamp" to ServerValue.TIMESTAMP,
                "read" to false,
                "eventId" to eventId,
                "eventTitle" to eventTitle,
                "organizerName" to organizerName,
                "hasEmail" to true,
                "recipientEmail" to recipientEmail
            )

            database.reference.child("notifications").child(userId).push().setValue(notification)

            // Also add to user's invitations for tracking
            val userInvitation = mapOf(
                "eventId" to eventId,
                "eventTitle" to eventTitle,
                "organizerName" to organizerName,
                "status" to "pending",
                "invitedAt" to ServerValue.TIMESTAMP,
                "hasEmail" to true
            )
            database.reference.child("users").child(userId).child("invitations").child(eventId).setValue(userInvitation)

        } catch (e: Exception) {
            // Handle error silently
        }
    }

    /**
     * Create fallback notification when email sending fails
     */
    private fun createFallbackInvitationNotification(
        userId: String,
        organizerName: String,
        eventId: String,
        eventTitle: String
    ) {
        try {
            val notification = mapOf(
                "type" to "invitation",
                "text" to "$organizerName invited you to \"$eventTitle\". Open the app to see details and RSVP.",
                "timestamp" to ServerValue.TIMESTAMP,
                "read" to false,
                "eventId" to eventId,
                "eventTitle" to eventTitle,
                "organizerName" to organizerName,
                "hasEmail" to false
            )

            database.reference.child("notifications").child(userId).push().setValue(notification)

            // Also add to user's invitations for tracking
            val userInvitation = mapOf(
                "eventId" to eventId,
                "eventTitle" to eventTitle,
                "organizerName" to organizerName,
                "status" to "pending",
                "invitedAt" to ServerValue.TIMESTAMP,
                "hasEmail" to false
            )
            database.reference.child("users").child(userId).child("invitations").child(eventId).setValue(userInvitation)

        } catch (e: Exception) {
            // Handle error silently
        }
    }

    /**
     * Send fallback notifications when organizer details cannot be retrieved
     */
    private fun sendFallbackInvitationNotifications(eventId: String, eventTitle: String, organizerName: String) {
        invitedUsers.values.forEach { invitedUser ->
            createFallbackInvitationNotification(
                invitedUser.userId,
                organizerName,
                eventId,
                eventTitle
            )
        }
    }

    /**
     * Format event date for email display
     */
    private fun formatEventDateForEmail(eventSnapshot: DataSnapshot): String {
        return try {
            val dateTimeSnapshot = eventSnapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java)
                    ?: dateTimeSnapshot.child("seconds").getValue(Long::class.java)
                    ?: 0L
                if (seconds > 0) {
                    val date = Date(seconds * 1000)
                    val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
                    return displayFormat.format(date)
                }
            }

            // Fallback to legacy format
            val dateString = eventSnapshot.child("date").getValue(String::class.java)
            val timeString = eventSnapshot.child("time").getValue(String::class.java)

            if (!dateString.isNullOrEmpty() && !timeString.isNullOrEmpty()) {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val date = inputFormat.parse("$dateString $timeString")
                if (date != null) {
                    val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
                    return displayFormat.format(date)
                }
            }

            "Date and time to be confirmed"

        } catch (e: Exception) {
            "Date and time to be confirmed"
        }
    }

    /**
     * Show date picker dialog with validation to prevent past dates
     */
    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        if (isEditMode && selectedDate.isNotEmpty()) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate)
                date?.let { calendar.time = it }
            } catch (e: Exception) { /* Use current date */ }
        }

        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
                binding.dateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // Prevent selecting past dates
        datePickerDialog.datePicker.minDate = System.currentTimeMillis() - 1000
        datePickerDialog.show()
    }

    /**
     * Show time picker dialog
     */
    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        if (isEditMode && selectedTime.isNotEmpty()) {
            try {
                val parts = selectedTime.split(":")
                if (parts.size == 2) {
                    calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    calendar.set(Calendar.MINUTE, parts[1].toInt())
                }
            } catch (e: Exception) { /* Use current time */ }
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

    /**
     * Launch image selection from device gallery
     */
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

    /**
     * Validate all form inputs before submission
     */
    private fun validateInputs(): Boolean {
        if (isCheckingEmail) {
            Toast.makeText(this, "Please wait while we validate the email address", Toast.LENGTH_SHORT).show()
            return false
        }

        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        // Clear previous errors
        binding.titleInputLayout.error = null
        binding.locationInputLayout.error = null
        binding.descriptionInputLayout.error = null

        // Validate required fields
        when {
            title.isEmpty() -> {
                binding.titleInputLayout.error = "Event title is required"
                return false
            }
            location.isEmpty() -> {
                binding.locationInputLayout.error = "Event location is required"
                return false
            }
            selectedDate.isEmpty() -> {
                Toast.makeText(this, "Please select event date", Toast.LENGTH_SHORT).show()
                return false
            }
            selectedTime.isEmpty() -> {
                Toast.makeText(this, "Please select event time", Toast.LENGTH_SHORT).show()
                return false
            }
            description.isEmpty() -> {
                binding.descriptionInputLayout.error = "Event description is required"
                return false
            }
        }

        // Validate date/time is not in the past
        try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val selectedDateTime = format.parse("$selectedDate $selectedTime")

            // Allow 1 minute grace period
            val validationCalendar = Calendar.getInstance()
            validationCalendar.add(Calendar.MINUTE, -1)

            if (selectedDateTime != null && selectedDateTime.before(validationCalendar.time)) {
                Toast.makeText(this, "Cannot create an event in the past.", Toast.LENGTH_LONG).show()
                return false
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid date or time format.", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    /**
     * Set loading state for UI elements
     */
    private fun setLoading(loading: Boolean) {
        binding.createButton.isEnabled = !loading
        binding.cancelButton.isEnabled = !loading
        if (loading) {
            binding.createButton.text = if (isEditMode) "Saving..." else "Creating..."
        } else {
            binding.createButton.text = if (isEditMode) "Save Changes" else "Create Event"
        }
    }

    /**
     * Create combined date/time object for Firebase storage
     */
    private fun getCombinedDateTime(): Map<String, Long> {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val date = format.parse("$selectedDate $selectedTime")
            if (date != null) {
                mapOf(
                    "_seconds" to (date.time / 1000),
                    "_nanoseconds" to 0L
                )
            } else {
                throw Exception("Failed to parse date")
            }
        } catch (e: Exception) {
            mapOf("_seconds" to 0L, "_nanoseconds" to 0L)
        }
    }

    /**
     * Load existing date/time from database with format compatibility
     */
    private fun loadExistingDateTime(snapshot: DataSnapshot) {
        try {
            val dateTimeSnapshot = snapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java)
                    ?: dateTimeSnapshot.child("seconds").getValue(Long::class.java)
                    ?: 0L
                if (seconds > 0) {
                    val date = Date(seconds * 1000)
                    selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                    selectedTime = SimpleDateFormat("HH:mm", Locale.US).format(date)
                    return
                }
            }

            // Fallback to legacy format
            selectedDate = snapshot.child("date").getValue(String::class.java) ?: ""
            selectedTime = snapshot.child("time").getValue(String::class.java) ?: ""

        } catch (e: Exception) {
            // Use empty values on error
        }
    }

    /**
     * Format date string for display in UI
     */
    private fun formatDateForDisplay(dateString: String): String {
        return try {
            if (dateString.isNotEmpty()) {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val outputFormat = SimpleDateFormat("dd/MM/yyyy", Locale.UK)
                val date = inputFormat.parse(dateString)
                if (date != null) outputFormat.format(date) else ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Validate email and add user to invitation list
     */
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
                        // User found - add to invitations
                        val userSnapshot = snapshot.children.first()
                        val userId = userSnapshot.key ?: ""
                        val fullName = userSnapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                        val profileImageUrl = userSnapshot.child("profileImageUrl").getValue(String::class.java)

                        val invitedUser = InvitedUser(email, fullName, userId, profileImageUrl)
                        invitedUsers[email] = invitedUser

                        // Show success and add chip
                        binding.emailValidationIcon.setImageResource(R.drawable.ic_check_circle)
                        binding.emailValidationIcon.visibility = View.VISIBLE

                        addInvitedUserChip(invitedUser)
                        binding.emailInput.setText("")

                        // Hide success icon after delay
                        binding.emailValidationIcon.postDelayed({
                            binding.emailValidationIcon.visibility = View.GONE
                        }, 1500)

                    } else {
                        // User not found
                        binding.emailInputLayout.error = "User not found. Make sure they have an account."
                        binding.emailValidationIcon.setImageResource(R.drawable.ic_error)
                        binding.emailValidationIcon.visibility = View.VISIBLE

                        // Hide error icon after delay
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

    /**
     * Handle successful save completion
     */
    private fun finishWithSuccess() {
        setLoading(false)
        setResult(Activity.RESULT_OK)
        finish()
    }

    /**
     * Handle save error
     */
    private fun handleSaveError(message: String) {
        setLoading(false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up keyboard listener
        keyboardLayoutListener?.let {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        keyboardHandler.removeCallbacksAndMessages(null)
    }
}