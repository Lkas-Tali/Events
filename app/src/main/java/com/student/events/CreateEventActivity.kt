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
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.NestedScrollView
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

class CreateEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateEventBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var storage: FirebaseStorage
    private lateinit var emailService: EmailService

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

    // Keyboard handling
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var isKeyboardShowing = false
    private val keyboardHandler = Handler(Looper.getMainLooper())

    data class InvitedUser(
        val email: String,
        val fullName: String,
        val userId: String,
        val profileImageUrl: String? = null
    )

    companion object {
        private const val TAG = "CreateEventActivity"
        private const val IMAGE_PICK_REQUEST = 1001

        // Email debugging flags
        private const val ENABLE_EMAIL_DEBUG = true
        private const val LOG_EMAIL_DETAILS = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCreateEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        storage = FirebaseStorage.getInstance()
        emailService = EmailService(this)

        // Log EmailJS configuration for debugging
        if (ENABLE_EMAIL_DEBUG) {
            Log.d(TAG, "📧 EMAIL DEBUG MODE ENABLED")
            Log.d(TAG, emailService.getConfigurationInfo())
        }

        isEditMode = intent.getBooleanExtra("editMode", false)
        if (isEditMode) {
            setupEditMode()
        }

        setupViews()
        setupInvitationFeature()

        // NEW: Setup enhanced keyboard handling
        setupKeyboardHandling()

        // Setup touch handling to dismiss keyboard
        setupTouchToDismissKeyboard()
    }

    // NEW: Enhanced keyboard handling setup
    private fun setupKeyboardHandling() {
        // Add extra padding at the bottom of the scrollable content
        // This ensures there's space to scroll the last field above the keyboard
        binding.nestedScrollView.getChildAt(0).apply {
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + 300)
        }

        // Setup keyboard visibility listener
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

    // NEW: Handle keyboard opened event
    private fun onKeyboardOpened(keyboardHeight: Int) {
        Log.d(TAG, "⌨️ Keyboard opened - Height: $keyboardHeight")

        // Check if email input is focused
        if (binding.emailInput.hasFocus()) {
            // Delay to ensure keyboard is fully shown
            keyboardHandler.postDelayed({
                ensureEmailInputVisible()
            }, 100)
        }
    }

    // NEW: Handle keyboard closed event
    private fun onKeyboardClosed() {
        Log.d(TAG, "⌨️ Keyboard closed")
        // Optionally scroll back to a neutral position
    }

    // NEW: Ensure email input is visible above keyboard
    private fun ensureEmailInputVisible() {
        // Get the email input's position
        val emailInputLocation = IntArray(2)
        binding.emailInput.getLocationOnScreen(emailInputLocation)

        // Get the scroll view's position
        val scrollViewLocation = IntArray(2)
        binding.nestedScrollView.getLocationOnScreen(scrollViewLocation)

        // Calculate the relative position of email input within scroll view
        val emailInputTop = emailInputLocation[1] - scrollViewLocation[1] + binding.nestedScrollView.scrollY

        // Get visible height (screen height - keyboard height)
        val rect = Rect()
        binding.root.getWindowVisibleDisplayFrame(rect)
        val visibleHeight = rect.bottom - scrollViewLocation[1]

        // Calculate desired scroll position
        // We want the email input to be in the middle of the visible area
        val desiredScrollY = emailInputTop - (visibleHeight / 2) + (binding.emailInput.height / 2)

        Log.d(TAG, "📍 Email input position: top=$emailInputTop, visible height=$visibleHeight")
        Log.d(TAG, "📍 Scrolling to position: $desiredScrollY")

        // Smooth scroll to the calculated position
        binding.nestedScrollView.smoothScrollTo(0, desiredScrollY.coerceAtLeast(0))
    }

    // Override to handle touch events for keyboard dismissal
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    Log.d(TAG, "👆 Touch outside edit field - dismissing keyboard")
                    hideKeyboard()
                    v.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // Setup touch handling to dismiss keyboard
    private fun setupTouchToDismissKeyboard() {
        // Set touch listener on the main layout to dismiss keyboard when touching outside
        binding.nestedScrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val v = currentFocus
                if (v is android.widget.EditText) {
                    val outRect = Rect()
                    v.getGlobalVisibleRect(outRect)
                    if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        Log.d(TAG, "👆 Touch on scroll view - dismissing keyboard")
                        hideKeyboard()
                        v.clearFocus()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    // Hide keyboard helper
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = currentFocus
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
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
        // Back button setup
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
        // Auto-scroll when invite section opens
        binding.invitePeopleHeader.setOnClickListener {
            val isVisible = binding.inviteSection.visibility == View.VISIBLE

            if (!isVisible) {
                // Show the section first
                binding.inviteSection.visibility = View.VISIBLE
                binding.inviteArrow.rotation = 180f

                Log.d(TAG, "📧 Invite section opened - auto-scrolling to show it")

                // Auto-scroll to show the invite section with extra consideration for keyboard
                binding.nestedScrollView.post {
                    val padding = 100 // Increased padding
                    val targetY = binding.invitePeopleHeader.top - padding

                    binding.nestedScrollView.smoothScrollTo(0, targetY)
                    Log.d(TAG, "🔄 Scrolled to show invite section at position: $targetY")

                    // Focus on email input after scrolling
                    keyboardHandler.postDelayed({
                        binding.emailInput.requestFocus()
                        showKeyboard(binding.emailInput)
                    }, 300)
                }
            } else {
                // Hide the section
                binding.inviteSection.visibility = View.GONE
                binding.inviteArrow.rotation = 0f
                hideKeyboard()
                binding.emailInput.clearFocus()
                Log.d(TAG, "📧 Invite section closed")
            }
        }

        // Clear email input error when user starts typing
        binding.emailInput.addTextChangedListener {
            binding.emailInputLayout.error = null
            binding.emailValidationIcon.visibility = View.GONE
        }

        // NEW: Enhanced focus handling for email input
        binding.emailInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                Log.d(TAG, "📧 Email input focused - ensuring visibility")
                keyboardHandler.postDelayed({
                    ensureEmailInputVisible()
                }, 200)
            }
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

    // NEW: Show keyboard helper
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
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
            Log.d(TAG, "👤 Removed invitation for: ${maskEmailForLogging(invitedUser.email)}")
        }

        binding.invitedUsersChipGroup.addView(chip)
        updateInviteCounter()
        Log.d(TAG, "✅ Added invitation chip for: ${invitedUser.fullName}")
    }

    private fun updateInviteCounter() {
        val count = invitedUsers.size
        binding.inviteCountText.text = if (count > 0) {
            "$count ${if (count == 1) "person" else "people"} invited"
        } else {
            "No one invited yet"
        }
        binding.inviteCountText.visibility = View.VISIBLE
        Log.d(TAG, "📊 Updated invite counter: $count people")
    }

    private fun loadExistingInvitations() {
        if (!isEditMode || eventId == null) return

        Log.d(TAG, "📥 Loading existing invitations for event: $eventId")

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
                    Log.d(TAG, "✅ Loaded $loadedCount existing invitations")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Failed to load existing invitations: ${error.message}")
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
            Log.d(TAG, "📸 Uploading event image...")
            val storageRef = storage.reference.child("event_images/${UUID.randomUUID()}.jpg")
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val data = baos.toByteArray()

            storageRef.putBytes(data)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Image uploaded successfully")
                    storageRef.downloadUrl.addOnSuccessListener { uri ->
                        Log.d(TAG, "✅ Got image download URL: $uri")
                        saveEventToDatabase(uri.toString())
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to get image URL: ${e.message}")
                        setLoading(false)
                        Toast.makeText(this, "Failed to get image URL.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Image upload failed: ${e.message}")
                    setLoading(false)
                    Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process image: ${e.message}")
            setLoading(false)
            Toast.makeText(this, "Failed to process image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveEventToDatabase(imageUrl: String?) {
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        Log.d(TAG, "💾 Saving event to database...")
        Log.d(TAG, "   Title: $title")
        Log.d(TAG, "   Location: $location")
        Log.d(TAG, "   Description length: ${description.length}")
        Log.d(TAG, "   Invitations: ${invitedUsers.size}")
        Log.d(TAG, "   Edit mode: $isEditMode")

        if (isEditMode) {
            // Update existing event
            eventId?.let { id ->
                Log.d(TAG, "✏️ Updating existing event: $id")
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
                    Log.d(TAG, "📧 Will update ${invitedUsers.size} invitations")
                }

                database.reference.child("events").child(id).updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Event updated successfully")
                        sendInvitationNotifications(id, title)
                        setLoading(false)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to update event: ${e.message}")
                        setLoading(false)
                        Toast.makeText(this, "Failed to update event: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        } else {
            // Create new event
            val user = auth.currentUser ?: return
            val userId = user.uid

            Log.d(TAG, "🆕 Creating new event for user: $userId")

            // Fetch the user's full name from the Realtime Database before creating the event.
            database.reference.child("users").child(userId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val userName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown"
                    val userPhotoUrl = user.photoUrl?.toString() ?: ""

                    Log.d(TAG, "👤 Creator details: $userName")

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
                        Log.d(TAG, "📧 Will send ${invitedUsers.size} invitations")
                    }

                    val eventRef = database.reference.child("events").push()
                    eventRef.setValue(event)
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Event created successfully with ID: ${eventRef.key}")
                            sendInvitationNotifications(eventRef.key!!, title)
                            setLoading(false)
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Failed to create event: ${e.message}")
                            setLoading(false)
                            Toast.makeText(this@CreateEventActivity, "Failed to create event: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Failed to get user details: ${error.message}")
                    setLoading(false)
                    Toast.makeText(this@CreateEventActivity, "Failed to get user details: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun sendInvitationNotifications(eventId: String, eventTitle: String) {
        if (invitedUsers.isEmpty()) {
            Log.d(TAG, "📧 No invitations to send")
            return
        }

        Log.d(TAG, "📧 Starting invitation process for ${invitedUsers.size} users")
        Log.d(TAG, "   Event ID: $eventId")
        Log.d(TAG, "   Event Title: $eventTitle")

        // Get current user's name and event details from database for accurate notifications and emails
        database.reference.child("users").child(auth.currentUser?.uid ?: "")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val currentUserName = snapshot.child("fullName").getValue(String::class.java) ?: "Someone"
                    val currentUserEmail = snapshot.child("email").getValue(String::class.java) ?: ""

                    Log.d(TAG, "👤 Organizer details:")
                    Log.d(TAG, "   Name: $currentUserName")
                    Log.d(TAG, "   Email: ${maskEmailForLogging(currentUserEmail)}")

                    // Get event details for email
                    database.reference.child("events").child(eventId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(eventSnapshot: DataSnapshot) {
                                val eventLocation = eventSnapshot.child("location").getValue(String::class.java) ?: ""
                                val eventDescription = eventSnapshot.child("description").getValue(String::class.java) ?: ""

                                // Format event date for email
                                val eventDateFormatted = formatEventDateForEmail(eventSnapshot)

                                Log.d(TAG, "🎉 Event details for email:")
                                Log.d(TAG, "   Location: $eventLocation")
                                Log.d(TAG, "   Date: $eventDateFormatted")
                                Log.d(TAG, "   Description length: ${eventDescription.length}")

                                // Send notifications and emails to each invited user
                                var emailsAttempted = 0
                                var emailsSuccessful = 0
                                var emailsFailed = 0

                                invitedUsers.values.forEach { invitedUser ->
                                    emailsAttempted++

                                    Log.d(TAG, "📧 Processing invitation $emailsAttempted/${invitedUsers.size}")
                                    Log.d(TAG, "   Recipient: ${invitedUser.fullName} (${maskEmailForLogging(invitedUser.email)})")

                                    // Send email invitation with enhanced logging
                                    CoroutineScope(Dispatchers.Main).launch {
                                        if (ENABLE_EMAIL_DEBUG) {
                                            Log.d(TAG, "🚀 Starting email send for ${maskEmailForLogging(invitedUser.email)}")
                                        }

                                        emailService.sendEventInvitation(
                                            recipientEmail = invitedUser.email,
                                            recipientName = invitedUser.fullName,
                                            organizerName = currentUserName,
                                            organizerEmail = currentUserEmail,
                                            eventTitle = eventTitle,
                                            eventDate = eventDateFormatted,
                                            eventLocation = eventLocation,
                                            eventDescription = eventDescription
                                        ) { emailSuccess, errorMessage ->

                                            if (emailSuccess) {
                                                emailsSuccessful++
                                                Log.d(TAG, "✅ Email invitation sent successfully")
                                                Log.d(TAG, "   To: ${maskEmailForLogging(invitedUser.email)}")
                                                Log.d(TAG, "   Progress: $emailsSuccessful/$emailsAttempted emails sent")

                                                // Create notification that mentions email
                                                createInvitationNotificationWithEmail(
                                                    invitedUser.userId,
                                                    currentUserName,
                                                    eventId,
                                                    eventTitle,
                                                    invitedUser.email
                                                )

                                            } else {
                                                emailsFailed++
                                                Log.e(TAG, "❌ Email invitation failed")
                                                Log.e(TAG, "   To: ${maskEmailForLogging(invitedUser.email)}")
                                                Log.e(TAG, "   Error: $errorMessage")
                                                Log.e(TAG, "   Progress: $emailsFailed failures, $emailsSuccessful successes")

                                                // Create fallback notification
                                                createFallbackInvitationNotification(
                                                    invitedUser.userId,
                                                    currentUserName,
                                                    eventId,
                                                    eventTitle
                                                )
                                            }

                                            // Log final summary when all emails are processed
                                            val totalProcessed = emailsSuccessful + emailsFailed
                                            if (totalProcessed == emailsAttempted) {
                                                Log.i(TAG, "📊 INVITATION SUMMARY:")
                                                Log.i(TAG, "   Total invitations: $emailsAttempted")
                                                Log.i(TAG, "   Emails successful: $emailsSuccessful")
                                                Log.i(TAG, "   Emails failed: $emailsFailed")
                                                Log.i(TAG, "   Success rate: ${(emailsSuccessful * 100) / emailsAttempted}%")

                                                // Show user feedback
                                                when {
                                                    emailsSuccessful == emailsAttempted -> {
                                                        Toast.makeText(this@CreateEventActivity,
                                                            "✅ All $emailsSuccessful invitations sent successfully!",
                                                            Toast.LENGTH_LONG).show()
                                                    }
                                                    emailsSuccessful > 0 -> {
                                                        Toast.makeText(this@CreateEventActivity,
                                                            "⚠️ $emailsSuccessful/$emailsAttempted invitations sent (some emails failed)",
                                                            Toast.LENGTH_LONG).show()
                                                    }
                                                    else -> {
                                                        Toast.makeText(this@CreateEventActivity,
                                                            "❌ Email invitations failed, but notifications were sent",
                                                            Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Log.e(TAG, "❌ Failed to get event details for email: ${error.message}")
                                // Fallback: send basic notifications without email
                                sendFallbackInvitationNotifications(eventId, eventTitle, currentUserName)
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Failed to get organizer details: ${error.message}")
                    // Use fallback name if database read fails
                    val fallbackName = auth.currentUser?.displayName ?: "Someone"
                    sendFallbackInvitationNotifications(eventId, eventTitle, fallbackName)
                }
            })
    }

    private fun createInvitationNotificationWithEmail(
        userId: String,
        organizerName: String,
        eventId: String,
        eventTitle: String,
        recipientEmail: String
    ) {
        try {
            Log.d(TAG, "📱 Creating notification with email reference")
            Log.d(TAG, "   User ID: $userId")
            Log.d(TAG, "   Email: ${maskEmailForLogging(recipientEmail)}")

            val notification = mapOf(
                "type" to "invitation",
                "text" to "$organizerName invited you to \"$eventTitle\". Check your email (${maskEmailForLogging(recipientEmail)}) for full details and RSVP in the app.",
                "timestamp" to ServerValue.TIMESTAMP,
                "read" to false,
                "eventId" to eventId,
                "eventTitle" to eventTitle,
                "organizerName" to organizerName,
                "hasEmail" to true,  // Flag to indicate email was sent
                "recipientEmail" to recipientEmail
            )

            database.reference.child("notifications").child(userId).push().setValue(notification)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Notification with email reference created")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to create notification: ${e.message}")
                }

            // COMPATIBILITY: Also add to user's invitations for tracking
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
            Log.e(TAG, "❌ Error creating invitation notification with email: ${e.message}", e)
        }
    }

    private fun createFallbackInvitationNotification(
        userId: String,
        organizerName: String,
        eventId: String,
        eventTitle: String
    ) {
        try {
            Log.d(TAG, "📱 Creating fallback notification (no email)")
            Log.d(TAG, "   User ID: $userId")

            val notification = mapOf(
                "type" to "invitation",
                "text" to "$organizerName invited you to \"$eventTitle\". Open the app to see details and RSVP.",
                "timestamp" to ServerValue.TIMESTAMP,
                "read" to false,
                "eventId" to eventId,
                "eventTitle" to eventTitle,
                "organizerName" to organizerName,
                "hasEmail" to false  // Flag to indicate no email was sent
            )

            database.reference.child("notifications").child(userId).push().setValue(notification)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Fallback notification created")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to create fallback notification: ${e.message}")
                }

            // COMPATIBILITY: Also add to user's invitations for tracking
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
            Log.e(TAG, "❌ Error creating fallback invitation notification: ${e.message}", e)
        }
    }

    private fun sendFallbackInvitationNotifications(eventId: String, eventTitle: String, organizerName: String) {
        Log.w(TAG, "📱 Sending fallback notifications only (no emails)")
        invitedUsers.values.forEach { invitedUser ->
            createFallbackInvitationNotification(
                invitedUser.userId,
                organizerName,
                eventId,
                eventTitle
            )
        }
    }

    private fun formatEventDateForEmail(eventSnapshot: DataSnapshot): String {
        return try {
            // Try new format first
            val dateTimeSnapshot = eventSnapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java) ?: 0L
                if (seconds > 0) {
                    val date = Date(seconds * 1000)
                    val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
                    return displayFormat.format(date)
                }
            }

            // Fall back to old format
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
            Log.e(TAG, "❌ Error formatting event date for email: ${e.message}", e)
            "Date and time to be confirmed"
        }
    }

    private fun maskEmailForLogging(email: String): String {
        if (!LOG_EMAIL_DETAILS) return "[HIDDEN]"

        val parts = email.split("@")
        return if (parts.size == 2) {
            val username = parts[0]
            val domain = parts[1]
            val maskedUsername = if (username.length > 2) {
                "${username.take(2)}${"*".repeat(username.length - 2)}"
            } else {
                "*".repeat(username.length)
            }
            "$maskedUsername@$domain"
        } else {
            email.take(3) + "*".repeat(maxOf(0, email.length - 3))
        }
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

        binding.titleInputLayout.error = null
        binding.locationInputLayout.error = null
        binding.descriptionInputLayout.error = null
        return true
    }

    private fun setLoading(loading: Boolean) {
        binding.createButton.isEnabled = !loading
        binding.cancelButton.isEnabled = !loading
        if (loading) {
            binding.createButton.text = "Creating..."
        } else {
            binding.createButton.text = if (isEditMode) "Save Changes" else "Create Event"
        }
    }

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
            Log.e(TAG, "❌ Error creating combined date/time: ${e.message}")
            mapOf("_seconds" to 0L, "_nanoseconds" to 0L)
        }
    }

    private fun loadExistingDateTime(snapshot: DataSnapshot) {
        try {
            // Try new format first
            val dateTimeSnapshot = snapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java) ?: 0L
                if (seconds > 0) {
                    val date = Date(seconds * 1000)
                    selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                    selectedTime = SimpleDateFormat("HH:mm", Locale.US).format(date)
                    return
                }
            }

            // Fall back to old format
            selectedDate = snapshot.child("date").getValue(String::class.java) ?: ""
            selectedTime = snapshot.child("time").getValue(String::class.java) ?: ""

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading existing date/time: ${e.message}")
        }
    }

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

    private fun validateAndAddEmail(email: String) {
        Log.d(TAG, "👤 Validating email for invitation: ${maskEmailForLogging(email)}")

        // Basic email validation
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Please enter a valid email address"
            Log.w(TAG, "❌ Invalid email format: ${maskEmailForLogging(email)}")
            return
        }

        // Check if already invited
        if (invitedUsers.containsKey(email)) {
            binding.emailInputLayout.error = "This person is already invited"
            Log.w(TAG, "⚠️ Email already invited: ${maskEmailForLogging(email)}")
            return
        }

        // Check if it's the current user's email
        if (email == auth.currentUser?.email) {
            binding.emailInputLayout.error = "You cannot invite yourself"
            Log.w(TAG, "⚠️ User tried to invite themselves: ${maskEmailForLogging(email)}")
            return
        }

        // Show loading state
        isCheckingEmail = true
        binding.emailValidationIcon.visibility = View.VISIBLE
        binding.emailValidationIcon.setImageResource(R.drawable.ic_loading)
        binding.addEmailButton.isEnabled = false

        Log.d(TAG, "🔍 Checking if user exists in database: ${maskEmailForLogging(email)}")

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

                        Log.d(TAG, "✅ User found in database:")
                        Log.d(TAG, "   Email: ${maskEmailForLogging(email)}")
                        Log.d(TAG, "   Name: $fullName")
                        Log.d(TAG, "   User ID: $userId")

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
                        Log.w(TAG, "❌ User not found in database: ${maskEmailForLogging(email)}")
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
                    Log.e(TAG, "❌ Database error checking user: ${error.message}")
                    binding.emailInputLayout.error = "Error checking user. Please try again."
                    binding.emailValidationIcon.visibility = View.GONE
                }
            })
    }

    // ... [Include all other methods from the original file - they remain unchanged] ...

    override fun onDestroy() {
        super.onDestroy()
        // Clean up keyboard listener
        keyboardLayoutListener?.let {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        keyboardHandler.removeCallbacksAndMessages(null)
    }
}