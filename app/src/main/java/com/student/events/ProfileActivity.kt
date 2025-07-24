package com.student.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityProfileBinding
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*


class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    // --- FIX: Use two separate, dedicated adapters to prevent view recycling issues ---
    private lateinit var upcomingEventsAdapter: EventsAdapter
    private lateinit var pastEventsAdapter: EventsAdapter
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var storage: FirebaseStorage

    private val upcomingEvents = mutableListOf<Event>()
    private val pastEvents = mutableListOf<Event>()

    private var currentUserId: String? = null
    private var currentUser: FirebaseUser? = null

    private var selectedImageUri: Uri? = null

    companion object {
        private const val TAG = "ProfileActivity"

        fun newIntent(context: Context): Intent {
            return Intent(context, ProfileActivity::class.java)
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                showEditProfileDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        try {
            auth = FirebaseAuth.getInstance()
            database = FirebaseDatabase.getInstance()
            storage = FirebaseStorage.getInstance()
            currentUser = auth.currentUser
            currentUserId = currentUser?.uid

            if (currentUserId == null) {
                Log.e(TAG, "No current user found")
                finish()
                return
            }

            binding = DataBindingUtil.setContentView(this, R.layout.activity_profile)

            Log.d(TAG, "ProfileActivity created successfully")

            applySystemBarInsets()
            setupViews()
            setupRecyclerViews()
            setupSwipeRefresh()
            loadUserProfile()
            loadUserEvents()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            finish()
        }
    }

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

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.app_primary_blue,
            R.color.app_success,
            R.color.app_accent
        )

        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshAllData()
        }
    }

    private fun refreshAllData() {
        Log.d(TAG, "Refreshing profile data...")
        upcomingEvents.clear()
        pastEvents.clear()
        loadUserProfile()
        loadUserEvents()
    }

    private fun setupViews() {
        try {
            binding.backButton.setOnClickListener {
                finish()
            }
            binding.editProfileButton.setOnClickListener {
                showEditProfileDialog()
            }
            binding.changePasswordButton.setOnClickListener {
                showChangePasswordDialog()
            }
            binding.profileImageView.setOnClickListener {
                showEditProfileDialog()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up views: ${e.message}", e)
        }
    }

    // --- FIX: This function now correctly initializes two separate adapter instances ---
    private fun setupRecyclerViews() {
        try {
            // --- FIX: Apply the EXACT same RecyclerView configuration as MainActivity ---

            upcomingEventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event -> showCustomEventDetails(event) },
                onEditClick = { event ->
                    val intent = Intent(this, CreateEventActivity::class.java).apply {
                        putExtra("editMode", true)
                        putExtra("eventId", event.id)
                        putExtra("eventTitle", event.title)
                        event.dateTime?.seconds?.let { seconds ->
                            val date = Date(seconds * 1000)
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
                            putExtra("eventDate", dateFormat.format(date))
                            putExtra("eventTime", timeFormat.format(date))
                        }
                        putExtra("eventLocation", event.location)
                        putExtra("eventDescription", event.description)
                        putExtra("eventImage", event.imageUrl)
                    }
                    startActivity(intent)
                },
                onCancelClick = { event -> showCancelEventDialog(event) },
                onRsvpClick = { event -> handleRsvp(event) },
                onCancelRsvpClick = { event -> showCancelRsvpDialog(event) }
            )

            pastEventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event -> showCustomEventDetails(event) },
                onEditClick = { /* No edit for past events */ },
                onCancelClick = { /* No cancel for past events */ },
                onRsvpClick = { /* No RSVP for past events */ },
                onCancelRsvpClick = { /* No cancel RSVP for past events */ }
            )

            // --- CRITICAL FIX: Apply MainActivity's exact RecyclerView configuration ---
            binding.upcomingEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@ProfileActivity)
                adapter = upcomingEventsAdapter
                // KEY FIXES from MainActivity:
                isNestedScrollingEnabled = false
                setHasFixedSize(false)
                // Enable hardware acceleration for alpha rendering
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            binding.pastEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@ProfileActivity)
                adapter = pastEventsAdapter
                // KEY FIXES from MainActivity:
                isNestedScrollingEnabled = false
                setHasFixedSize(false)
                // Enable hardware acceleration for alpha rendering
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up RecyclerViews: ${e.message}", e)
        }
    }

    private fun loadUserProfile() {
        try {
            currentUserId?.let { uid ->
                database.reference.child("users").child(uid)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            try {
                                val fullName = snapshot.child("fullName").getValue(String::class.java)
                                val email = snapshot.child("email").getValue(String::class.java)
                                val about = snapshot.child("about").getValue(String::class.java)
                                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                                binding.apply {
                                    profileNameText.text = fullName ?: "User"
                                    profileEmailText.text = email ?: ""
                                    profileAboutText.text = about ?: "Welcome to my profile!"
                                }
                                loadProfileImage(profileImageUrl)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing user profile data: ${e.message}", e)
                            } finally {
                                binding.swipeRefreshLayout.isRefreshing = false
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to load user profile: ${error.message}")
                            binding.apply {
                                profileNameText.text = currentUser?.displayName ?: "User"
                                profileEmailText.text = currentUser?.email ?: ""
                                profileAboutText.text = "Welcome to my profile!"
                                profileImageView.setImageResource(R.drawable.ic_person)
                            }
                            binding.swipeRefreshLayout.isRefreshing = false
                        }
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user profile: ${e.message}", e)
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun loadProfileImage(profileImageUrl: String?) {
        if (!profileImageUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(profileImageUrl)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .circleCrop()
                .into(binding.profileImageView)
        } else {
            binding.profileImageView.setImageResource(R.drawable.ic_person)
        }
    }

    private fun loadUserEvents() {
        try {
            currentUserId?.let { uid ->
                database.reference.child("events")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(eventsSnapshot: DataSnapshot) {
                            try {
                                upcomingEvents.clear()
                                pastEvents.clear()
                                val currentTime = System.currentTimeMillis() / 1000

                                for (eventSnapshot in eventsSnapshot.children) {
                                    try {
                                        val event = parseEventFromSnapshot(eventSnapshot, eventSnapshot.key ?: continue)
                                        if (event != null) {
                                            val isOrganizer = event.organizer?.uid == uid
                                            val isAttendee = event.attendees.containsKey(uid)
                                            if (isOrganizer || isAttendee) {
                                                if ((event.dateTime?.seconds ?: Long.MAX_VALUE) > currentTime) {
                                                    upcomingEvents.add(event)
                                                } else {
                                                    pastEvents.add(event)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error parsing event ${eventSnapshot.key}: ${e.message}", e)
                                    }
                                }
                                upcomingEvents.sortBy { it.dateTime?.seconds ?: 0 }
                                pastEvents.sortByDescending { it.dateTime?.seconds ?: 0 }
                                updateEventsList()
                                updateTotalEventsCount()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing events data: ${e.message}", e)
                            } finally {
                                binding.swipeRefreshLayout.isRefreshing = false
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to load events: ${error.code} - ${error.message}")
                            binding.swipeRefreshLayout.isRefreshing = false
                        }
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user events: ${e.message}", e)
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun parseEventFromSnapshot(snapshot: DataSnapshot, eventId: String): Event? {
        return try {
            val title = snapshot.child("title").getValue(String::class.java) ?: ""
            val location = snapshot.child("location").getValue(String::class.java) ?: ""
            val description = snapshot.child("description").getValue(String::class.java) ?: ""
            val status = snapshot.child("status").getValue(String::class.java) ?: "upcoming"
            val imageUrl = snapshot.child("imageUrl").getValue(String::class.java)
            val attendeesCount = snapshot.child("attendeesCount").getValue(Int::class.java) ?: 0

            val organizerSnapshot = snapshot.child("organizer")
            val organizer = if (organizerSnapshot.exists()) {
                com.student.events.models.Organizer(
                    uid = organizerSnapshot.child("uid").getValue(String::class.java) ?: "",
                    fullName = organizerSnapshot.child("fullName").getValue(String::class.java) ?: ""
                )
            } else null

            val dateTime = parseDateTime(snapshot)

            val attendeesMap = mutableMapOf<String, com.student.events.models.Attendee>()
            val attendeesSnapshot = snapshot.child("attendees")
            for (attendeeSnapshot in attendeesSnapshot.children) {
                val attendeeId = attendeeSnapshot.key ?: continue
                val attendee = com.student.events.models.Attendee(
                    fullName = attendeeSnapshot.child("fullName").getValue(String::class.java) ?: "",
                    profileImageUrl = attendeeSnapshot.child("profileImageUrl").getValue(String::class.java) ?: ""
                )
                attendeesMap[attendeeId] = attendee
            }

            Event(
                id = eventId,
                title = title,
                location = location,
                description = description,
                organizer = organizer,
                attendees = attendeesMap,
                attendeesCount = attendeesCount,
                status = status,
                dateTime = dateTime,
                imageUrl = imageUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error manually parsing event: ${e.message}", e)
            null
        }
    }

    private fun parseDateTime(snapshot: DataSnapshot): com.student.events.models.DateTime? {
        return try {
            val dateTimeSnapshot = snapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java)
                    ?: dateTimeSnapshot.child("seconds").getValue(Long::class.java)
                    ?: 0L
                val nanoseconds = dateTimeSnapshot.child("_nanoseconds").getValue(Long::class.java)
                    ?: dateTimeSnapshot.child("nanoseconds").getValue(Long::class.java)
                    ?: 0L
                com.student.events.models.DateTime(seconds = seconds, nanoseconds = nanoseconds)
            } else {
                val dateString = snapshot.child("date").getValue(String::class.java)
                val timeString = snapshot.child("time").getValue(String::class.java)
                if (!dateString.isNullOrEmpty() && !timeString.isNullOrEmpty()) {
                    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val date = format.parse("$dateString $timeString")
                    if (date != null) {
                        com.student.events.models.DateTime(
                            seconds = date.time / 1000,
                            nanoseconds = 0L
                        )
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing dateTime: ${e.message}", e)
            null
        }
    }

    // --- FIX: This function now correctly updates the two separate adapter instances ---
    private fun updateEventsList() {
        try {
            upcomingEventsAdapter.updateEvents(upcomingEvents)
            binding.upcomingEmptyState.visibility = if (upcomingEvents.isEmpty()) View.VISIBLE else View.GONE

            pastEventsAdapter.updateEvents(pastEvents)
            binding.pastEmptyState.visibility = if (pastEvents.isEmpty()) View.VISIBLE else View.GONE

        } catch (e: Exception) {
            Log.e(TAG, "Error updating events list: ${e.message}", e)
        }
    }

    private fun updateTotalEventsCount() {
        val totalEvents = upcomingEvents.size + pastEvents.size
        binding.totalEventsText.text = "$totalEvents Total Events"
    }

    private fun showEditProfileDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).create()
        val fullNameEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.fullNameEditText)
        val aboutEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.aboutEditText)
        val profileImagePreview = dialogView.findViewById<ImageView>(R.id.profileImagePreview)
        val changeImageButton = dialogView.findViewById<View>(R.id.changeImageButton)
        val saveButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.cancelButton)
        val closeButton = dialogView.findViewById<ImageView>(R.id.closeButton)
        val progressBar = dialogView.findViewById<View>(R.id.progressBar)

        fullNameEditText.setText(binding.profileNameText.text.toString())
        aboutEditText.setText(binding.profileAboutText.text.toString())

        if (selectedImageUri != null) {
            Glide.with(this).load(selectedImageUri).circleCrop().into(profileImagePreview)
        } else {
            currentUserId?.let { uid ->
                database.reference.child("users").child(uid).child("profileImageUrl")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val imageUrl = snapshot.getValue(String::class.java)
                            if (!imageUrl.isNullOrEmpty()) {
                                Glide.with(this@ProfileActivity).load(imageUrl).placeholder(R.drawable.ic_person).circleCrop().into(profileImagePreview)
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
            }
        }

        changeImageButton.setOnClickListener {
            openImagePicker()
            dialog.dismiss()
        }

        saveButton.setOnClickListener {
            val newName = fullNameEditText.text.toString().trim()
            val newAbout = aboutEditText.text.toString().trim()
            if (newName.isEmpty()) {
                fullNameEditText.error = "Name cannot be empty"
                return@setOnClickListener
            }
            progressBar.visibility = View.VISIBLE
            saveButton.isEnabled = false
            saveProfileChanges(newName, newAbout, selectedImageUri) {
                progressBar.visibility = View.GONE
                saveButton.isEnabled = true
                if (it) {
                    dialog.dismiss()
                    selectedImageUri = null
                    Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                    loadUserProfile()
                } else {
                    Toast.makeText(this, "Failed to update profile", Toast.LENGTH_SHORT).show()
                }
            }
        }

        cancelButton.setOnClickListener {
            selectedImageUri = null
            dialog.dismiss()
        }
        closeButton.setOnClickListener {
            selectedImageUri = null
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private fun saveProfileChanges(name: String, about: String, imageUri: Uri?, callback: (Boolean) -> Unit) {
        currentUserId?.let { uid ->
            if (imageUri != null) {
                uploadProfileImage(imageUri) { imageUrl ->
                    if (imageUrl != null) {
                        updateUserData(uid, name, about, imageUrl, callback)
                    } else {
                        callback(false)
                    }
                }
            } else {
                updateUserData(uid, name, about, null, callback)
            }
        } ?: callback(false)
    }

    private fun uploadProfileImage(imageUri: Uri, callback: (String?) -> Unit) {
        val imageRef = storage.reference.child("profile_images/${currentUserId}_${System.currentTimeMillis()}.jpg")
        imageRef.putFile(imageUri)
            .addOnSuccessListener {
                imageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    callback(downloadUri.toString())
                }.addOnFailureListener {
                    Log.e(TAG, "Failed to get download URL: ${it.message}")
                    callback(null)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to upload image: ${it.message}")
                callback(null)
            }
    }

    private fun updateUserData(uid: String, name: String, about: String, imageUrl: String?, callback: (Boolean) -> Unit) {
        val updates = mutableMapOf<String, Any>("fullName" to name, "about" to about)
        if (imageUrl != null) {
            updates["profileImageUrl"] = imageUrl
        }
        database.reference.child("users").child(uid).updateChildren(updates)
            .addOnSuccessListener { callback(true) }
            .addOnFailureListener {
                Log.e(TAG, "Failed to update profile: ${it.message}")
                callback(false)
            }
    }

    private fun showChangePasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).create()
        val currentPasswordEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.currentPasswordEditText)
        val newPasswordEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.newPasswordEditText)
        val confirmPasswordEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.confirmPasswordEditText)
        val changePasswordButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.changePasswordButton)
        val cancelButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.cancelButton)
        val closeButton = dialogView.findViewById<ImageView>(R.id.closeButton)
        val progressBar = dialogView.findViewById<View>(R.id.progressBar)

        changePasswordButton.setOnClickListener {
            val currentPassword = currentPasswordEditText.text.toString()
            val newPassword = newPasswordEditText.text.toString()
            val confirmPassword = confirmPasswordEditText.text.toString()
            if (currentPassword.isEmpty()) {
                currentPasswordEditText.error = "Current password is required"
                return@setOnClickListener
            }
            if (newPassword.length < 8) {
                newPasswordEditText.error = "New password must be at least 8 characters"
                return@setOnClickListener
            }
            if (newPassword != confirmPassword) {
                confirmPasswordEditText.error = "Passwords do not match"
                return@setOnClickListener
            }
            progressBar.visibility = View.VISIBLE
            changePasswordButton.isEnabled = false
            changePassword(currentPassword, newPassword) { success ->
                progressBar.visibility = View.GONE
                changePasswordButton.isEnabled = true
                if (success) {
                    dialog.dismiss()
                    Toast.makeText(this, "Password changed successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to change password. Please check your current password.", Toast.LENGTH_LONG).show()
                }
            }
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun changePassword(currentPassword: String, newPassword: String, callback: (Boolean) -> Unit) {
        currentUser?.let { user ->
            val email = user.email
            if (email != null) {
                val credential = EmailAuthProvider.getCredential(email, currentPassword)
                user.reauthenticate(credential)
                    .addOnSuccessListener {
                        user.updatePassword(newPassword)
                            .addOnSuccessListener { callback(true) }
                            .addOnFailureListener {
                                Log.e(TAG, "Failed to update password: ${it.message}")
                                callback(false)
                            }
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Re-authentication failed: ${it.message}")
                        callback(false)
                    }
            } else {
                callback(false)
            }
        } ?: callback(false)
    }

    private fun handleRsvp(event: Event) {
        currentUserId?.let { uid ->
            val currentUser = auth.currentUser
            val userName = currentUser?.displayName ?: "A User"
            val updates = hashMapOf<String, Any>(
                "events/${event.id}/attendees/$uid/fullName" to userName,
                "events/${event.id}/attendees/$uid/profileImageUrl" to (currentUser?.photoUrl?.toString() ?: ""),
                "events/${event.id}/attendeesCount" to event.attendeesCount + 1
            )
            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_SHORT).show()
                    loadUserEvents()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to RSVP", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showCancelEventDialog(event: Event) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Event")
            .setMessage("Are you sure you want to permanently cancel and delete \"${event.title}\"? This action cannot be undone.")
            .setPositiveButton("Yes, Cancel Event") { _, _ -> deleteEvent(event) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showCancelRsvpDialog(event: Event) {
        AlertDialog.Builder(this)
            .setTitle("Cancel RSVP")
            .setMessage("Are you sure you want to cancel your RSVP for \"${event.title}\"?")
            .setPositiveButton("Yes, Cancel RSVP") { _, _ -> cancelRsvp(event) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun deleteEvent(event: Event) {
        database.reference.child("events").child(event.id).removeValue()
            .addOnSuccessListener {
                Toast.makeText(this, "Event deleted successfully", Toast.LENGTH_SHORT).show()
                loadUserEvents()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to delete event", Toast.LENGTH_SHORT).show()
            }
    }

    private fun cancelRsvp(event: Event) {
        currentUserId?.let { uid ->
            val updates = hashMapOf<String, Any?>(
                "events/${event.id}/attendees/$uid" to null,
                "events/${event.id}/attendeesCount" to (event.attendeesCount - 1).coerceAtLeast(0)
            )
            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "RSVP cancelled", Toast.LENGTH_SHORT).show()
                    loadUserEvents()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to cancel RSVP", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showCustomEventDetails(event: Event) {
        setMainContentInteraction(false)
        val detailsView = LayoutInflater.from(this).inflate(R.layout.dialog_event_details, binding.eventDetailsContainer, false)
        populateDetailsView(detailsView, event)
        binding.eventDetailsContainer.addView(detailsView)
        binding.darkScrim.visibility = View.VISIBLE
        animateDetailsIn(detailsView)
    }

    private fun populateDetailsView(view: View, event: Event) {
        view.findViewById<TextView>(R.id.eventTitle).text = event.title
        view.findViewById<ImageView>(R.id.closeButton).setOnClickListener {
            animateDetailsOut(view)
        }
        val imageView = view.findViewById<ImageView>(R.id.eventImage)
        if (!event.imageUrl.isNullOrEmpty()) {
            imageView.visibility = View.VISIBLE
            Glide.with(this).load(event.imageUrl).into(imageView)
        } else {
            imageView.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.dateTimeText).text = formatDateTime(event)
        view.findViewById<TextView>(R.id.locationText).text = event.location
        view.findViewById<TextView>(R.id.descriptionText).text = event.description
        view.findViewById<TextView>(R.id.attendeesText).text = "${event.attendeesCount} people attending"
        val organizerText = view.findViewById<TextView>(R.id.organizerText)
        val organizerClickableSection = view.findViewById<LinearLayout>(R.id.organizerClickableSection)
        val organizerHintText = view.findViewById<TextView>(R.id.organizerHintText)
        val organizerArrow = view.findViewById<ImageView>(R.id.organizerArrow)
        val organizerName = event.organizer?.fullName ?: "Unknown"
        organizerText.text = organizerName
        val organizerUid = event.organizer?.uid
        if (organizerUid != null && organizerUid != currentUserId) {
            organizerClickableSection.setOnClickListener {
                Log.d(TAG, "Opening profile for organizer: $organizerName ($organizerUid)")
                animateDetailsOut(view)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val intent = PublicProfileActivity.newIntent(this@ProfileActivity, organizerUid, organizerName)
                    startActivity(intent)
                }, 300)
            }
            organizerClickableSection.isClickable = true
            organizerClickableSection.isFocusable = true
            organizerHintText.visibility = View.VISIBLE
            organizerArrow.visibility = View.VISIBLE
        } else {
            organizerClickableSection.setOnClickListener(null)
            organizerClickableSection.isClickable = false
            organizerClickableSection.isFocusable = false
            organizerClickableSection.background = null
            if (organizerUid == currentUserId) {
                organizerText.text = "$organizerName (You)"
                organizerText.setTextColor(resources.getColor(R.color.app_text_secondary, null))
            }
            organizerHintText.visibility = View.GONE
            organizerArrow.visibility = View.GONE
        }
    }

    private fun animateDetailsIn(detailsView: View) {
        binding.eventDetailsContainer.visibility = View.VISIBLE
        val scrimFadeIn = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 1f)
        scrimFadeIn.duration = 400
        detailsView.alpha = 0f
        detailsView.scaleX = 0.8f
        detailsView.scaleY = 0.8f
        val cardFadeIn = ObjectAnimator.ofFloat(detailsView, "alpha", 1f)
        val cardScaleX = ObjectAnimator.ofFloat(detailsView, "scaleX", 1f)
        val cardScaleY = ObjectAnimator.ofFloat(detailsView, "scaleY", 1f)
        val cardAnimatorSet = AnimatorSet()
        cardAnimatorSet.playTogether(cardFadeIn, cardScaleX, cardScaleY)
        cardAnimatorSet.interpolator = OvershootInterpolator(1.1f)
        cardAnimatorSet.duration = 500
        val contentContainer = detailsView.findViewById<ViewGroup>(R.id.contentContainer)
        val contentAnimators = AnimatorSet()
        val animators = mutableListOf<Animator>()
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            child.alpha = 0f
            child.translationY = 80f
            val childFade = ObjectAnimator.ofFloat(child, "alpha", 1f)
            val childSlide = ObjectAnimator.ofFloat(child, "translationY", 0f)
            val childSet = AnimatorSet()
            childSet.playTogether(childFade, childSlide)
            childSet.duration = 400
            childSet.interpolator = DecelerateInterpolator()
            childSet.startDelay = (i * 60).toLong()
            animators.add(childSet)
        }
        contentAnimators.playTogether(animators)
        val finalAnimatorSet = AnimatorSet()
        finalAnimatorSet.play(scrimFadeIn).with(cardAnimatorSet).before(contentAnimators)
        finalAnimatorSet.start()
    }

    private fun animateDetailsOut(detailsView: View) {
        val scrimFadeOut = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 0f)
        scrimFadeOut.duration = 300
        val cardFadeOut = ObjectAnimator.ofFloat(detailsView, "alpha", 0f)
        val cardSlideDown = ObjectAnimator.ofFloat(detailsView, "translationY", 100f)
        val cardAnimatorSet = AnimatorSet()
        cardAnimatorSet.playTogether(cardFadeOut, cardSlideDown)
        cardAnimatorSet.interpolator = DecelerateInterpolator()
        cardAnimatorSet.duration = 300
        val finalAnimatorSet = AnimatorSet()
        finalAnimatorSet.playTogether(scrimFadeOut, cardAnimatorSet)
        finalAnimatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.darkScrim.visibility = View.GONE
                binding.eventDetailsContainer.visibility = View.GONE
                binding.eventDetailsContainer.removeView(detailsView)
                setMainContentInteraction(true)
            }
        })
        finalAnimatorSet.start()
    }

    private fun setMainContentInteraction(enabled: Boolean) {
        fun setViewAndChildrenEnabled(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    setViewAndChildrenEnabled(child, enabled)
                }
            }
        }
        val mainContent = findViewById<View>(R.id.mainContent) ?: return
        setViewAndChildrenEnabled(mainContent, enabled)
    }

    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }
}
