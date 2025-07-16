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
import com.google.android.material.tabs.TabLayout
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
    private lateinit var eventsAdapter: EventsAdapter
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var storage: FirebaseStorage

    private val organizedEvents = mutableListOf<Event>()
    private val attendingEvents = mutableListOf<Event>()

    private var currentTab = 0 // 0: Organized, 1: Attending
    private var currentUserId: String? = null
    private var currentUser: FirebaseUser? = null

    // For image upload
    private var selectedImageUri: Uri? = null

    companion object {
        private const val TAG = "ProfileActivity"

        fun newIntent(context: Context): Intent {
            return Intent(context, ProfileActivity::class.java)
        }
    }

    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                showEditProfileDialog() // Reopen dialog with selected image
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display - EXACTLY like MainActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // EXACTLY like MainActivity - Programmatically control the system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // This tells the system that the content behind the status bar is light, so icons should be dark
        insetsController.isAppearanceLightStatusBars = true
        // This tells the system that the content behind the navigation bar is light, so the handle should be dark
        insetsController.isAppearanceLightNavigationBars = true

        try {
            // Initialize Firebase
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

            // Initialize data binding
            binding = DataBindingUtil.setContentView(this, R.layout.activity_profile)

            Log.d(TAG, "ProfileActivity created successfully")

            // EXACTLY like MainActivity - apply system bar insets
            applySystemBarInsets()

            setupViews()
            setupRecyclerView()
            setupTabLayout()
            loadUserProfile()
            loadUserEvents()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            finish()
        }
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

    private fun setupViews() {
        try {
            // Back button - now using ImageView
            binding.backButton.setOnClickListener {
                finish()
            }

            // Profile action buttons
            binding.editProfileButton.setOnClickListener {
                showEditProfileDialog()
            }

            binding.changePasswordButton.setOnClickListener {
                showChangePasswordDialog()
            }

            // Profile image edit button
            binding.profileImageView.setOnClickListener {
                showEditProfileDialog()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up views: ${e.message}", e)
        }
    }

    private fun setupRecyclerView() {
        try {
            eventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event ->
                    Log.d(TAG, "Event clicked: ${event.title}")
                    showCustomEventDetails(event)
                },
                onEditClick = { event ->
                    Log.d(TAG, "Edit event clicked: ${event.title}")
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
                onCancelClick = { event ->
                    Log.d(TAG, "Cancel event clicked: ${event.title}")
                    showCancelEventDialog(event)
                },
                onRsvpClick = { event ->
                    Log.d(TAG, "RSVP clicked: ${event.title}")
                    handleRsvp(event)
                },
                onCancelRsvpClick = { event ->
                    Log.d(TAG, "Cancel RSVP clicked: ${event.title}")
                    showCancelRsvpDialog(event)
                }
            )

            binding.profileEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@ProfileActivity)
                adapter = eventsAdapter
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up RecyclerView: ${e.message}", e)
        }
    }

    private fun setupTabLayout() {
        try {
            binding.eventsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.let {
                        currentTab = it.position
                        Log.d(TAG, "Tab selected: $currentTab")
                        updateEventsForTab(currentTab)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up TabLayout: ${e.message}", e)
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

                                Log.d(TAG, "Loaded user profile: $fullName")

                                // Update UI
                                binding.apply {
                                    profileNameText.text = fullName ?: "User"
                                    profileEmailText.text = email ?: ""
                                    profileAboutText.text = about ?: "Welcome to my profile!"
                                }

                                // Load profile image
                                loadProfileImage(profileImageUrl)

                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing user profile data: ${e.message}", e)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to load user profile: ${error.message}")
                            // Set fallback data
                            binding.apply {
                                profileNameText.text = currentUser?.displayName ?: "User"
                                profileEmailText.text = currentUser?.email ?: ""
                                profileAboutText.text = "Welcome to my profile!"
                                profileImageView.setImageResource(R.drawable.ic_person)
                            }
                        }
                    })
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user profile: ${e.message}", e)
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
                                organizedEvents.clear()
                                attendingEvents.clear()

                                for (eventSnapshot in eventsSnapshot.children) {
                                    val event = eventSnapshot.getValue(Event::class.java)
                                    event?.let {
                                        it.id = eventSnapshot.key ?: ""

                                        if (it.organizer?.uid == uid) {
                                            organizedEvents.add(it)
                                            Log.d(TAG, "Added to organized: ${it.title}")
                                        } else if (it.attendees.containsKey(uid)) {
                                            attendingEvents.add(it)
                                            Log.d(TAG, "Added to attending: ${it.title}")
                                        }
                                    }
                                }

                                // Sort events by date (newest first)
                                organizedEvents.sortBy { it.dateTime?.seconds ?: 0 }
                                attendingEvents.sortBy { it.dateTime?.seconds ?: 0 }

                                Log.d(TAG, "Final counts - Organized: ${organizedEvents.size}, Attending: ${attendingEvents.size}")

                                updateTabCounts()
                                updateEventsForTab(currentTab)

                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing events data: ${e.message}", e)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to load events: ${error.message}")
                        }
                    })
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user events: ${e.message}", e)
        }
    }

    private fun updateTabCounts() {
        try {
            val tabLayout = binding.eventsTabLayout
            tabLayout.getTabAt(0)?.text = "Organized (${organizedEvents.size})"
            tabLayout.getTabAt(1)?.text = "Attending (${attendingEvents.size})"
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tab counts: ${e.message}", e)
        }
    }

    private fun updateEventsForTab(tabPosition: Int) {
        try {
            val filteredEvents = when (tabPosition) {
                0 -> organizedEvents
                1 -> attendingEvents
                else -> emptyList()
            }

            Log.d(TAG, "Updating events for tab $tabPosition, count: ${filteredEvents.size}")

            if (filteredEvents.isEmpty()) {
                binding.emptyStateLayout.visibility = View.VISIBLE
                binding.profileEventsRecyclerView.visibility = View.GONE

                val emptyText = when (tabPosition) {
                    0 -> "You haven't organized any events yet."
                    1 -> "You are not attending any events."
                    else -> "No events available."
                }
                binding.emptyStateText.text = emptyText
            } else {
                binding.emptyStateLayout.visibility = View.GONE
                binding.profileEventsRecyclerView.visibility = View.VISIBLE
                eventsAdapter.updateEvents(filteredEvents)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating events for tab: ${e.message}", e)
        }
    }

    // ========================================
    // EDIT PROFILE FUNCTIONALITY
    // ========================================

    private fun showEditProfileDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val fullNameEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.fullNameEditText)
        val aboutEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.aboutEditText)
        val profileImagePreview = dialogView.findViewById<ImageView>(R.id.profileImagePreview)
        val changeImageButton = dialogView.findViewById<View>(R.id.changeImageButton)
        val saveButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.cancelButton)
        val closeButton = dialogView.findViewById<ImageView>(R.id.closeButton)
        val progressBar = dialogView.findViewById<View>(R.id.progressBar)

        // Pre-populate current data
        fullNameEditText.setText(binding.profileNameText.text.toString())
        aboutEditText.setText(binding.profileAboutText.text.toString())

        // Load current profile image or selected image
        if (selectedImageUri != null) {
            Glide.with(this)
                .load(selectedImageUri)
                .circleCrop()
                .into(profileImagePreview)
        } else {
            // Load current profile image
            currentUserId?.let { uid ->
                database.reference.child("users").child(uid).child("profileImageUrl")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val imageUrl = snapshot.getValue(String::class.java)
                            if (!imageUrl.isNullOrEmpty()) {
                                Glide.with(this@ProfileActivity)
                                    .load(imageUrl)
                                    .placeholder(R.drawable.ic_person)
                                    .circleCrop()
                                    .into(profileImagePreview)
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
            }
        }

        // Set up click listeners
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
                    selectedImageUri = null // Reset after successful upload
                    Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                    loadUserProfile() // Refresh the profile display
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
                // Upload image first
                uploadProfileImage(imageUri) { imageUrl ->
                    if (imageUrl != null) {
                        updateUserData(uid, name, about, imageUrl, callback)
                    } else {
                        callback(false)
                    }
                }
            } else {
                // Update without changing image
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
        val updates = mutableMapOf<String, Any>(
            "fullName" to name,
            "about" to about
        )

        if (imageUrl != null) {
            updates["profileImageUrl"] = imageUrl
        }

        database.reference.child("users").child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Profile updated successfully")
                callback(true)
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to update profile: ${it.message}")
                callback(false)
            }
    }

    // ========================================
    // CHANGE PASSWORD FUNCTIONALITY
    // ========================================

    private fun showChangePasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

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

            // Validation
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
                // Re-authenticate user
                val credential = EmailAuthProvider.getCredential(email, currentPassword)
                user.reauthenticate(credential)
                    .addOnSuccessListener {
                        // Update password
                        user.updatePassword(newPassword)
                            .addOnSuccessListener {
                                Log.d(TAG, "Password updated successfully")
                                callback(true)
                            }
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

    // ========================================
    // EVENT MANAGEMENT FUNCTIONS
    // ========================================

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
                    loadUserEvents() // Refresh events
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
                loadUserEvents() // Refresh events
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
                    loadUserEvents() // Refresh events
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to cancel RSVP", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // ========================================
    // EVENT DETAILS POPUP (SAME AS MAIN ACTIVITY)
    // ========================================

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
        view.findViewById<TextView>(R.id.organizerText).text = "Organized by ${event.organizer?.fullName ?: "Unknown"}"
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