package com.student.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityPublicProfileBinding
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*

class PublicProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPublicProfileBinding
    private lateinit var upcomingEventsAdapter: EventsAdapter
    private lateinit var pastEventsAdapter: EventsAdapter
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private val upcomingEvents = mutableListOf<Event>()
    private val pastEvents = mutableListOf<Event>()

    private var profileUserId: String? = null
    private var profileUserName: String? = null
    private var currentUserId: String? = null

    // **FIXED: Use real-time listeners like MainActivity**
    private var userDataListener: ValueEventListener? = null
    private var userDataRef: DatabaseReference? = null
    private var eventsListener: ValueEventListener? = null
    private var eventsRef: DatabaseReference? = null

    // **FIXED: Simple refresh state management**
    private var isRefreshing = false

    companion object {
        private const val TAG = "PublicProfileActivity"
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_USER_NAME = "user_name"

        fun newIntent(context: Context, userId: String, userName: String? = null): Intent {
            return Intent(context, PublicProfileActivity::class.java).apply {
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_USER_NAME, userName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display - EXACTLY like your other activities
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Configure system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        try {
            // Initialize Firebase
            auth = FirebaseAuth.getInstance()
            database = FirebaseDatabase.getInstance()
            currentUserId = auth.currentUser?.uid

            // **FIXED: Simple auth check without complex listeners**
            if (currentUserId == null) {
                Log.e(TAG, "User not authenticated")
                Toast.makeText(this, "Please log in to view profiles", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }

            // Get user data from intent
            profileUserId = intent.getStringExtra(EXTRA_USER_ID)
            profileUserName = intent.getStringExtra(EXTRA_USER_NAME)

            if (profileUserId == null) {
                Log.e(TAG, "No user ID provided")
                finish()
                return
            }

            // Initialize data binding
            binding = DataBindingUtil.setContentView(this, R.layout.activity_public_profile)

            Log.d(TAG, "PublicProfileActivity created for user: $profileUserId")

            // Apply system bar insets
            applySystemBarInsets()

            setupViews()
            setupRecyclerViews()
            setupSwipeRefresh()

            // **FIXED: Start real-time listeners like MainActivity**
            setupRealTimeListeners()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            Toast.makeText(this, "Error initializing profile: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Apply system bar insets - EXACTLY like your other activities
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

    // **FIXED: Setup real-time listeners exactly like MainActivity**
    private fun setupRealTimeListeners() {
        Log.d(TAG, "Setting up real-time listeners")
        setupUserDataListener()
        setupEventsListener()
    }

    // **FIXED: Real-time user data listener like MainActivity**
    private fun setupUserDataListener() {
        profileUserId?.let { uid ->
            // Remove any existing listener first
            userDataListener?.let { listener ->
                userDataRef?.removeEventListener(listener)
            }

            Log.d(TAG, "Setting up user data listener for UID: $uid")
            userDataRef = database.reference.child("users").child(uid)
            userDataListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val fullName = snapshot.child("fullName").getValue(String::class.java)
                        val about = snapshot.child("about").getValue(String::class.java)
                        val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                        Log.d(TAG, "User data updated - Name: $fullName, Image: ${!profileImageUrl.isNullOrEmpty()}")

                        // Update UI on main thread
                        runOnUiThread {
                            binding.apply {
                                profileNameText.text = fullName ?: "User"
                                profileAboutText.text = about ?: "Welcome to my profile!"
                                headerTitle.text = fullName ?: "Profile"
                            }

                            // Update profile user name for contact modal
                            profileUserName = fullName

                            // Load profile image
                            loadProfileImage(profileImageUrl)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing user data: ${e.message}", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to load user profile: ${error.message}")

                    // **FIXED: Handle specific errors without aggressive auth checks**
                    runOnUiThread {
                        // Set fallback data
                        binding.apply {
                            profileNameText.text = "User"
                            profileAboutText.text = "Welcome to my profile!"
                            profileImageView.setImageResource(R.drawable.ic_person)
                        }

                        if (error.code == DatabaseError.PERMISSION_DENIED) {
                            Toast.makeText(this@PublicProfileActivity, "Access denied to this profile", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            userDataRef?.addValueEventListener(userDataListener!!)
        }
    }

    // **FIXED: Real-time events listener like MainActivity**
    private fun setupEventsListener() {
        // Remove any existing listener first
        eventsListener?.let { listener ->
            eventsRef?.removeEventListener(listener)
        }

        Log.d(TAG, "Setting up events listener")
        eventsRef = database.reference.child("events")

        eventsListener = object : ValueEventListener {
            override fun onDataChange(eventsSnapshot: DataSnapshot) {
                try {
                    Log.d(TAG, "Events data changed. Total events: ${eventsSnapshot.childrenCount}")

                    // Clear lists
                    upcomingEvents.clear()
                    pastEvents.clear()

                    val currentTime = System.currentTimeMillis() / 1000 // Current time in seconds

                    // Process each event
                    for (eventSnapshot in eventsSnapshot.children) {
                        try {
                            val eventId = eventSnapshot.key ?: continue
                            Log.d(TAG, "Processing event with ID: $eventId")

                            // Manual parsing to handle different data structures
                            val event = parseEventFromSnapshot(eventSnapshot, eventId)

                            if (event != null) {
                                // Only include events organized by this user
                                if (event.organizer?.uid == profileUserId) {
                                    // Check if event is upcoming or past
                                    val eventTime = event.dateTime?.seconds ?: Long.MAX_VALUE
                                    if (eventTime > currentTime) {
                                        upcomingEvents.add(event)
                                        Log.d(TAG, "Added to upcoming: ${event.title}")
                                    } else {
                                        pastEvents.add(event)
                                        Log.d(TAG, "Added to past: ${event.title}")
                                    }
                                }
                            } else {
                                Log.e(TAG, "Failed to parse event: $eventId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing event ${eventSnapshot.key}: ${e.message}", e)
                        }
                    }

                    Log.d(TAG, "Final counts - Upcoming: ${upcomingEvents.size}, Past: ${pastEvents.size}")

                    // Sort events by date
                    upcomingEvents.sortBy { it.dateTime?.seconds ?: 0 }
                    pastEvents.sortByDescending { it.dateTime?.seconds ?: 0 } // Recent first for past events

                    // Update UI on main thread
                    runOnUiThread {
                        updateEventsList()
                        updateTotalEventsCount()

                        // Stop refresh indicator
                        binding.swipeRefreshLayout.isRefreshing = false
                        isRefreshing = false
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing events data: ${e.message}", e)
                    runOnUiThread {
                        binding.swipeRefreshLayout.isRefreshing = false
                        isRefreshing = false
                        Toast.makeText(this@PublicProfileActivity, "Error loading events", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to load events: ${error.code} - ${error.message}")

                runOnUiThread {
                    binding.swipeRefreshLayout.isRefreshing = false
                    isRefreshing = false

                    val errorMessage = when (error.code) {
                        DatabaseError.PERMISSION_DENIED -> "Access denied to events data"
                        DatabaseError.NETWORK_ERROR -> "Network error. Please check your connection."
                        else -> "Failed to load events"
                    }
                    Toast.makeText(this@PublicProfileActivity, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }

        eventsRef?.addValueEventListener(eventsListener!!)
    }

    // **FIXED: Simple refresh without complex state management**
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.app_primary_blue,
            R.color.app_success,
            R.color.app_accent
        )

        binding.swipeRefreshLayout.setOnRefreshListener {
            if (!isRefreshing) {
                Log.d(TAG, "Pull-to-refresh triggered")
                isRefreshing = true

                // **FIXED: Simple refresh - just re-setup listeners**
                // The real-time listeners will automatically get fresh data
                setupRealTimeListeners()

                // Auto-stop refresh after 3 seconds as fallback
                binding.swipeRefreshLayout.postDelayed({
                    binding.swipeRefreshLayout.isRefreshing = false
                    isRefreshing = false
                }, 3000)
            }
        }
    }

    // Manual event parsing to handle different data structures
    private fun parseEventFromSnapshot(snapshot: DataSnapshot, eventId: String): Event? {
        return try {
            val title = snapshot.child("title").getValue(String::class.java) ?: ""
            val location = snapshot.child("location").getValue(String::class.java) ?: ""
            val description = snapshot.child("description").getValue(String::class.java) ?: ""
            val status = snapshot.child("status").getValue(String::class.java) ?: "upcoming"
            val imageUrl = snapshot.child("imageUrl").getValue(String::class.java)
            val attendeesCount = snapshot.child("attendeesCount").getValue(Int::class.java) ?: 0

            // Parse organizer
            val organizerSnapshot = snapshot.child("organizer")
            val organizer = if (organizerSnapshot.exists()) {
                com.student.events.models.Organizer(
                    uid = organizerSnapshot.child("uid").getValue(String::class.java) ?: "",
                    fullName = organizerSnapshot.child("fullName").getValue(String::class.java) ?: ""
                )
            } else null

            // Parse dateTime (handle both old and new formats)
            val dateTime = parseDateTime(snapshot)

            // Parse attendees
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
            // Try new format first
            val dateTimeSnapshot = snapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                com.student.events.models.DateTime(
                    seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java) ?: 0L,
                    nanoseconds = dateTimeSnapshot.child("_nanoseconds").getValue(Long::class.java) ?: 0L
                )
            } else {
                // Fall back to old format - convert to new format for consistency
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

    private fun setupViews() {
        try {
            // Set header title
            binding.headerTitle.text = profileUserName ?: "Profile"

            // Back button
            binding.backButton.setOnClickListener {
                finish()
            }

            // Contact organizer button
            binding.contactOrganizerButton.setOnClickListener {
                showContactModal()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up views: ${e.message}", e)
        }
    }

    private fun setupRecyclerViews() {
        try {
            upcomingEventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event ->
                    Log.d(TAG, "Event clicked: ${event.title}")
                    showCustomEventDetails(event)
                },
                onEditClick = { /* Not applicable for public profile */ },
                onCancelClick = { /* Not applicable for public profile */ },
                onRsvpClick = { event ->
                    Log.d(TAG, "RSVP clicked: ${event.title}")
                    handleRsvp(event)
                },
                onCancelRsvpClick = { event ->
                    Log.d(TAG, "Cancel RSVP clicked: ${event.title}")
                    handleCancelRsvp(event)
                }
            )

            pastEventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event ->
                    Log.d(TAG, "Past event clicked: ${event.title}")
                    showCustomEventDetails(event)
                },
                onEditClick = { /* Not applicable for public profile */ },
                onCancelClick = { /* Not applicable for public profile */ },
                onRsvpClick = { /* Past events can't be RSVP'd */ },
                onCancelRsvpClick = { /* Past events can't be cancelled */ }
            )

            binding.upcomingEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@PublicProfileActivity)
                adapter = upcomingEventsAdapter
            }

            binding.pastEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@PublicProfileActivity)
                adapter = pastEventsAdapter
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up RecyclerViews: ${e.message}", e)
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

    private fun updateEventsList() {
        try {
            // Update upcoming events
            if (upcomingEvents.isEmpty()) {
                binding.upcomingEventsRecyclerView.visibility = View.GONE
                binding.upcomingEmptyState.visibility = View.VISIBLE
            } else {
                binding.upcomingEventsRecyclerView.visibility = View.VISIBLE
                binding.upcomingEmptyState.visibility = View.GONE
                upcomingEventsAdapter.updateEvents(upcomingEvents)
            }

            // Update past events
            if (pastEvents.isEmpty()) {
                binding.pastEventsRecyclerView.visibility = View.GONE
                binding.pastEmptyState.visibility = View.VISIBLE
            } else {
                binding.pastEventsRecyclerView.visibility = View.VISIBLE
                binding.pastEmptyState.visibility = View.GONE
                pastEventsAdapter.updateEvents(pastEvents)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating events list: ${e.message}", e)
        }
    }

    private fun updateTotalEventsCount() {
        val totalEvents = upcomingEvents.size + pastEvents.size
        binding.totalEventsText.text = "$totalEvents Total Events Hosted"
    }

    // ========================================
    // CONTACT ORGANIZER FUNCTIONALITY
    // ========================================

    private fun showContactModal() {
        try {
            setMainContentInteraction(false)
            val modalView = LayoutInflater.from(this).inflate(R.layout.dialog_contact_organizer, binding.contactModalContainer, false)

            val modalTitle = modalView.findViewById<TextView>(R.id.modalTitle)
            modalTitle.text = "Contact ${profileUserName ?: "Organizer"}"

            binding.contactModalContainer.addView(modalView)
            binding.darkScrim.visibility = View.VISIBLE
            animateModalIn(modalView)

            setupContactModalListeners(modalView)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing contact modal: ${e.message}", e)
        }
    }

    private fun setupContactModalListeners(modalView: View) {
        val closeButton = modalView.findViewById<ImageView>(R.id.closeButton)
        val cancelButton = modalView.findViewById<View>(R.id.cancelButton)
        val sendButton = modalView.findViewById<View>(R.id.sendButton)
        val nameInput = modalView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameInput)
        val emailInput = modalView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.emailInput)
        val messageInput = modalView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.messageInput)

        // Pre-populate current user's info if available
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid).get()
                .addOnSuccessListener { snapshot ->
                    nameInput.setText(snapshot.child("fullName").getValue(String::class.java) ?: "")
                    emailInput.setText(snapshot.child("email").getValue(String::class.java) ?: "")
                }
        }

        closeButton.setOnClickListener { animateModalOut(modalView) }
        cancelButton.setOnClickListener { animateModalOut(modalView) }

        sendButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            val message = messageInput.text.toString().trim()

            if (name.isEmpty() || email.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendContactMessage(modalView, name, email, message)
        }
    }

    private fun sendContactMessage(modalView: View, name: String, email: String, message: String) {
        try {
            // Show loading state
            showModalState(modalView, "loading")

            // Simulate sending message (in real app, you'd send to a backend)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Show success state
                showModalState(modalView, "success")

                // Create notification for organizer
                profileUserId?.let { organizerId ->
                    createNotification(organizerId, "contact", "$name sent you a message about your events.")
                }

                // Auto-close after 2 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    animateModalOut(modalView)
                }, 2000)

            }, 1500)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending contact message: ${e.message}", e)
            Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
            showModalState(modalView, "form")
        }
    }

    private fun showModalState(modalView: View, state: String) {
        val formContent = modalView.findViewById<View>(R.id.formContent)
        val loadingContent = modalView.findViewById<View>(R.id.loadingContent)
        val successContent = modalView.findViewById<View>(R.id.successContent)

        when (state) {
            "form" -> {
                formContent.visibility = View.VISIBLE
                loadingContent.visibility = View.GONE
                successContent.visibility = View.GONE
            }
            "loading" -> {
                formContent.visibility = View.GONE
                loadingContent.visibility = View.VISIBLE
                successContent.visibility = View.GONE
            }
            "success" -> {
                formContent.visibility = View.GONE
                loadingContent.visibility = View.GONE
                successContent.visibility = View.VISIBLE
            }
        }
    }

    private fun createNotification(userId: String, type: String, text: String) {
        try {
            val notification = mapOf(
                "type" to type,
                "text" to text,
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "read" to false
            )
            database.reference.child("notifications").child(userId).push().setValue(notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification: ${e.message}", e)
        }
    }

    // ========================================
    // MODAL ANIMATIONS - Same style as your other activities
    // ========================================

    private fun animateModalIn(modalView: View) {
        binding.contactModalContainer.visibility = View.VISIBLE

        val scrimFadeIn = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 1f)
        scrimFadeIn.duration = 400

        modalView.alpha = 0f
        modalView.scaleX = 0.8f
        modalView.scaleY = 0.8f

        val modalFadeIn = ObjectAnimator.ofFloat(modalView, "alpha", 1f)
        val modalScaleX = ObjectAnimator.ofFloat(modalView, "scaleX", 1f)
        val modalScaleY = ObjectAnimator.ofFloat(modalView, "scaleY", 1f)

        val modalAnimatorSet = AnimatorSet()
        modalAnimatorSet.playTogether(modalFadeIn, modalScaleX, modalScaleY)
        modalAnimatorSet.interpolator = OvershootInterpolator(1.1f)
        modalAnimatorSet.duration = 500

        val finalAnimatorSet = AnimatorSet()
        finalAnimatorSet.play(scrimFadeIn).with(modalAnimatorSet)
        finalAnimatorSet.start()
    }

    private fun animateModalOut(modalView: View) {
        val scrimFadeOut = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 0f)
        scrimFadeOut.duration = 300

        val modalFadeOut = ObjectAnimator.ofFloat(modalView, "alpha", 0f)
        val modalSlideDown = ObjectAnimator.ofFloat(modalView, "translationY", 100f)

        val modalAnimatorSet = AnimatorSet()
        modalAnimatorSet.playTogether(modalFadeOut, modalSlideDown)
        modalAnimatorSet.interpolator = DecelerateInterpolator()
        modalAnimatorSet.duration = 300

        val finalAnimatorSet = AnimatorSet()
        finalAnimatorSet.playTogether(scrimFadeOut, modalAnimatorSet)
        finalAnimatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.darkScrim.visibility = View.GONE
                binding.contactModalContainer.visibility = View.GONE
                binding.contactModalContainer.removeView(modalView)
                setMainContentInteraction(true)
            }
        })
        finalAnimatorSet.start()
    }

    // ========================================
    // EVENT DETAILS POPUP - Same as your other activities
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

    // ========================================
    // EVENT ACTIONS
    // ========================================

    private fun handleRsvp(event: Event) {
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid).get().addOnSuccessListener { snapshot ->
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java) ?: ""

                val updates = hashMapOf<String, Any>(
                    "events/${event.id}/attendees/$uid/fullName" to fullName,
                    "events/${event.id}/attendees/$uid/profileImageUrl" to profileImageUrl,
                    "events/${event.id}/attendeesCount" to event.attendeesCount + 1
                )

                database.reference.updateChildren(updates)
                    .addOnSuccessListener {
                        Toast.makeText(this, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_SHORT).show()
                        event.organizer?.uid?.let { organizerId ->
                            createNotification(organizerId, "rsvp", "$fullName accepted your invite to ${event.title}.")
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to RSVP", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    private fun handleCancelRsvp(event: Event) {
        currentUserId?.let { uid ->
            val updates = hashMapOf<String, Any?>(
                "events/${event.id}/attendees/$uid" to null,
                "events/${event.id}/attendeesCount" to (event.attendeesCount - 1).coerceAtLeast(0)
            )
            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "RSVP cancelled", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to cancel RSVP", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }

    // **FIXED: Proper cleanup like MainActivity**
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called - cleaning up listeners")

        // Clean up real-time listeners
        userDataListener?.let { listener ->
            userDataRef?.removeEventListener(listener)
            userDataListener = null
            userDataRef = null
        }

        eventsListener?.let { listener ->
            eventsRef?.removeEventListener(listener)
            eventsListener = null
            eventsRef = null
        }

        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Stop refresh if activity is pausing
        if (isRefreshing) {
            binding.swipeRefreshLayout.isRefreshing = false
            isRefreshing = false
        }
    }
}