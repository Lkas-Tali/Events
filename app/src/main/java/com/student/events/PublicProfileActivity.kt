package com.student.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityPublicProfileBinding
import com.student.events.models.Event
import com.student.events.services.EmailService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * PublicProfileActivity displays a user's public profile with their hosted events.
 * Allows viewing event details, RSVPing to events, and contacting the profile owner.
 */
class PublicProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPublicProfileBinding
    private lateinit var upcomingEventsAdapter: EventsAdapter
    private lateinit var pastEventsAdapter: EventsAdapter
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var emailService: EmailService

    // Event data collections
    private val upcomingEvents = mutableListOf<Event>()
    private val pastEvents = mutableListOf<Event>()

    // User identifiers
    private var profileUserId: String? = null
    private var profileUserName: String? = null
    private var currentUserId: String? = null

    // Firebase listeners for cleanup
    private var userDataListener: ValueEventListener? = null
    private var userDataRef: DatabaseReference? = null
    private var eventsListener: ValueEventListener? = null
    private var eventsRef: DatabaseReference? = null

    private var isRefreshing = false

    companion object {
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_USER_NAME = "user_name"

        /**
         * Create intent for viewing a user's public profile
         */
        fun newIntent(context: Context, userId: String, userName: String? = null): Intent {
            return Intent(context, PublicProfileActivity::class.java).apply {
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_USER_NAME, userName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        try {
            initializeServices()
            extractIntentData()
            setupUserInterface()
            setupDataListeners()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing profile: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Initialize Firebase services and dependencies
     */
    private fun initializeServices() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        emailService = EmailService(this)
        currentUserId = auth.currentUser?.uid

        // Verify user authentication
        if (currentUserId == null) {
            Toast.makeText(this, "Please log in to view profiles", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
    }

    /**
     * Extract profile data from intent extras
     */
    private fun extractIntentData() {
        profileUserId = intent.getStringExtra(EXTRA_USER_ID)
        profileUserName = intent.getStringExtra(EXTRA_USER_NAME)

        if (profileUserId == null) {
            finish()
            return
        }
    }

    /**
     * Setup the main user interface components
     */
    private fun setupUserInterface() {
        binding = DataBindingUtil.setContentView(this, R.layout.activity_public_profile)

        applySystemBarInsets()
        setupViews()
        setupRecyclerViews()
        setupSwipeRefresh()
    }

    /**
     * Apply system bar insets for edge-to-edge display
     */
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

    /**
     * Initialize Firebase real-time listeners
     */
    private fun setupDataListeners() {
        setupUserDataListener()
        setupEventsListener()
    }

    /**
     * Setup listener for user profile data updates
     */
    private fun setupUserDataListener() {
        profileUserId?.let { uid ->
            // Clean up existing listener
            userDataListener?.let { listener ->
                userDataRef?.removeEventListener(listener)
            }

            userDataRef = database.reference.child("users").child(uid)
            userDataListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val fullName = snapshot.child("fullName").getValue(String::class.java)
                        val about = snapshot.child("about").getValue(String::class.java)
                        val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                        runOnUiThread {
                            updateUserInterface(fullName, about, profileImageUrl)
                        }
                    } catch (e: Exception) {
                        // Handle parsing errors gracefully
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    runOnUiThread {
                        // Set default values if data loading fails
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

    /**
     * Update UI with user profile information
     */
    private fun updateUserInterface(fullName: String?, about: String?, profileImageUrl: String?) {
        binding.apply {
            profileNameText.text = fullName ?: "User"
            profileAboutText.text = about ?: "Welcome to my profile!"
            headerTitle.text = fullName ?: "Profile"
        }

        profileUserName = fullName
        loadProfileImage(profileImageUrl)
    }

    /**
     * Setup listener for events data updates
     */
    private fun setupEventsListener() {
        // Clean up existing listener
        eventsListener?.let { listener ->
            eventsRef?.removeEventListener(listener)
        }

        eventsRef = database.reference.child("events")
        eventsListener = object : ValueEventListener {
            override fun onDataChange(eventsSnapshot: DataSnapshot) {
                try {
                    processEventsData(eventsSnapshot)
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.swipeRefreshLayout.isRefreshing = false
                        isRefreshing = false
                        Toast.makeText(this@PublicProfileActivity, "Error loading events", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
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

    /**
     * Process events data and categorize by time
     */
    private fun processEventsData(eventsSnapshot: DataSnapshot) {
        upcomingEvents.clear()
        pastEvents.clear()

        val currentTime = System.currentTimeMillis() / 1000

        for (eventSnapshot in eventsSnapshot.children) {
            try {
                val eventId = eventSnapshot.key ?: continue
                val event = parseEventFromSnapshot(eventSnapshot, eventId)

                if (event != null && event.organizer?.uid == profileUserId) {
                    val eventTime = event.dateTime?.seconds ?: Long.MAX_VALUE
                    if (eventTime > currentTime) {
                        upcomingEvents.add(event)
                    } else {
                        pastEvents.add(event)
                    }
                }
            } catch (e: Exception) {
                // Skip invalid events
            }
        }

        // Sort events by date
        upcomingEvents.sortBy { it.dateTime?.seconds ?: 0 }
        pastEvents.sortByDescending { it.dateTime?.seconds ?: 0 }

        runOnUiThread {
            updateEventsList()
            updateTotalEventsCount()
            binding.swipeRefreshLayout.isRefreshing = false
            isRefreshing = false
        }
    }

    /**
     * Parse event data from Firebase snapshot
     */
    private fun parseEventFromSnapshot(snapshot: DataSnapshot, eventId: String): Event? {
        return try {
            val title = snapshot.child("title").getValue(String::class.java) ?: ""
            val location = snapshot.child("location").getValue(String::class.java) ?: ""
            val description = snapshot.child("description").getValue(String::class.java) ?: ""
            val status = snapshot.child("status").getValue(String::class.java) ?: "upcoming"
            val imageUrl = snapshot.child("imageUrl").getValue(String::class.java)
            val attendeesCount = snapshot.child("attendeesCount").getValue(Int::class.java) ?: 0

            // Parse organizer information
            val organizerSnapshot = snapshot.child("organizer")
            val organizer = if (organizerSnapshot.exists()) {
                com.student.events.models.Organizer(
                    uid = organizerSnapshot.child("uid").getValue(String::class.java) ?: "",
                    fullName = organizerSnapshot.child("fullName").getValue(String::class.java) ?: ""
                )
            } else null

            val dateTime = parseDateTime(snapshot)

            // Parse attendees map
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
            null
        }
    }

    /**
     * Parse date/time information from event snapshot
     */
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
                // Fallback for legacy date format
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
            null
        }
    }

    /**
     * Setup UI components and click listeners
     */
    private fun setupViews() {
        try {
            binding.headerTitle.text = profileUserName ?: "Profile"

            binding.backButton.setOnClickListener {
                finish()
            }

            binding.contactOrganizerButton.setOnClickListener {
                showContactModal()
            }
        } catch (e: Exception) {
            // Handle setup errors gracefully
        }
    }

    /**
     * Configure RecyclerView adapters for upcoming and past events
     */
    private fun setupRecyclerViews() {
        try {
            upcomingEventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event ->
                    showCustomEventDetails(event, fromInviteNotification = false)
                },
                onEditClick = { },
                onCancelClick = { },
                onRsvpClick = { event ->
                    handleRsvp(event)
                },
                onCancelRsvpClick = { event ->
                    showCancelRsvpDialog(event)
                }
            )

            pastEventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event ->
                    showCustomEventDetails(event, fromInviteNotification = false)
                },
                onEditClick = { },
                onCancelClick = { },
                onRsvpClick = { },
                onCancelRsvpClick = { }
            )

            // Configure upcoming events RecyclerView
            binding.upcomingEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@PublicProfileActivity)
                adapter = upcomingEventsAdapter
                isNestedScrollingEnabled = false
                setHasFixedSize(false)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            // Configure past events RecyclerView
            binding.pastEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@PublicProfileActivity)
                adapter = pastEventsAdapter
                isNestedScrollingEnabled = false
                setHasFixedSize(false)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        } catch (e: Exception) {
            // Handle RecyclerView setup errors
        }
    }

    /**
     * Configure swipe-to-refresh functionality
     */
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.app_primary_blue,
            R.color.app_success,
            R.color.app_accent
        )

        binding.swipeRefreshLayout.setOnRefreshListener {
            if (!isRefreshing) {
                isRefreshing = true
                // Real-time listeners will handle the update
                binding.swipeRefreshLayout.postDelayed({
                    binding.swipeRefreshLayout.isRefreshing = false
                    isRefreshing = false
                }, 1000)
            }
        }
    }

    /**
     * Load profile image using Glide
     */
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

    /**
     * Update RecyclerView adapters with current events data
     */
    private fun updateEventsList() {
        try {
            upcomingEventsAdapter.updateEvents(upcomingEvents.toList())
            binding.upcomingEmptyState.visibility = if (upcomingEvents.isEmpty()) View.VISIBLE else View.GONE

            pastEventsAdapter.updateEvents(pastEvents.toList())
            binding.pastEmptyState.visibility = if (pastEvents.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            // Handle update errors gracefully
        }
    }

    /**
     * Update the total events count display
     */
    private fun updateTotalEventsCount() {
        val totalEvents = upcomingEvents.size + pastEvents.size
        binding.totalEventsText.text = "$totalEvents Total Events Hosted"
    }

    /**
     * Display detailed event information in modal dialog
     */
    private fun showCustomEventDetails(event: Event, fromInviteNotification: Boolean = false) {
        setMainContentInteraction(false)
        val detailsView = LayoutInflater.from(this).inflate(R.layout.dialog_event_details, binding.eventDetailsContainer, false)
        populateDetailsView(detailsView, event, fromInviteNotification)
        binding.eventDetailsContainer.addView(detailsView)

        binding.darkScrim.visibility = View.VISIBLE
        animateDetailsIn(detailsView)
    }

    /**
     * Populate event details dialog with event information
     */
    private fun populateDetailsView(view: View, event: Event, fromInviteNotification: Boolean = false) {
        view.findViewById<TextView>(R.id.eventTitle).text = event.title
        view.findViewById<ImageView>(R.id.closeButton).setOnClickListener {
            animateDetailsOut(view)
        }

        // Load event image if available
        val imageView = view.findViewById<ImageView>(R.id.eventImage)
        if (!event.imageUrl.isNullOrEmpty()) {
            imageView.visibility = View.VISIBLE
            Glide.with(this).load(event.imageUrl).into(imageView)
        } else {
            imageView.visibility = View.GONE
        }

        // Populate event details
        view.findViewById<TextView>(R.id.dateTimeText).text = formatDateTime(event)
        view.findViewById<TextView>(R.id.locationText).text = event.location
        view.findViewById<TextView>(R.id.descriptionText).text = event.description
        view.findViewById<TextView>(R.id.attendeesText).text = "${event.attendeesCount} people attending"
        view.findViewById<TextView>(R.id.organizerText).text = "${event.organizer?.fullName ?: "Unknown"}"

        // Configure organizer section for profile view
        val organizerSection = view.findViewById<LinearLayout>(R.id.organizerClickableSection)
        organizerSection.isClickable = false
        organizerSection.isFocusable = false
        organizerSection.background = null

        val organizerArrow = view.findViewById<ImageView>(R.id.organizerArrow)
        organizerArrow.visibility = View.GONE

        val organizerHint = view.findViewById<TextView>(R.id.organizerHintText)
        organizerHint.text = "This profile's organizer"
        organizerHint.setTextColor(getColor(R.color.app_text_tertiary))

        configureActionButtons(view, event, fromInviteNotification)
    }

    /**
     * Configure action buttons for event details dialog
     */
    private fun configureActionButtons(view: View, event: Event, fromInviteNotification: Boolean) {
        val actionButtonsContainer = view.findViewById<LinearLayout>(R.id.actionButtonsContainer)
        val invitationContextText = view.findViewById<TextView>(R.id.invitationContextText)
        val actionButton = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.actionButton)
        val secondaryButton = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.secondaryButton)

        if (fromInviteNotification) {
            actionButtonsContainer.visibility = View.VISIBLE
            invitationContextText.visibility = View.VISIBLE

            val isMyEvent = event.organizer?.uid == currentUserId
            val isAttending = event.attendees.containsKey(currentUserId)

            when {
                isMyEvent -> {
                    invitationContextText.visibility = View.GONE
                    actionButton.text = "View Details"
                    actionButton.setOnClickListener { animateDetailsOut(view) }
                    secondaryButton.visibility = View.GONE
                }

                isAttending -> {
                    invitationContextText.text = "You're already attending this event"
                    invitationContextText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0)

                    actionButton.text = "Cancel RSVP"
                    actionButton.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.app_error))
                    actionButton.setOnClickListener { handleCancelRsvpFromDetails(event, view) }
                    secondaryButton.visibility = View.GONE
                }

                else -> {
                    invitationContextText.text = "You've been invited to this event"
                    invitationContextText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_person_add, 0, 0, 0)

                    actionButton.text = "Accept"
                    actionButton.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.app_primary_blue))
                    actionButton.setOnClickListener { handleRsvpFromDetails(event, view) }

                    secondaryButton.text = "Decline"
                    secondaryButton.visibility = View.VISIBLE
                    secondaryButton.setOnClickListener {
                        animateDetailsOut(view)
                        Toast.makeText(this@PublicProfileActivity, "Invitation declined", Toast.LENGTH_SHORT).show()
                    }

                    // Configure button layout for side-by-side buttons
                    val commonLayoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    val marginInPixels = (4 * resources.displayMetrics.density).toInt()

                    val acceptParams = LinearLayout.LayoutParams(commonLayoutParams)
                    acceptParams.marginEnd = marginInPixels
                    actionButton.layoutParams = acceptParams

                    val declineParams = LinearLayout.LayoutParams(commonLayoutParams)
                    declineParams.marginStart = marginInPixels
                    secondaryButton.layoutParams = declineParams
                }
            }
        } else {
            actionButtonsContainer.visibility = View.GONE
        }
    }

    /**
     * Handle RSVP action from event details dialog
     */
    private fun handleRsvpFromDetails(event: Event, detailsView: View) {
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid).get().addOnSuccessListener { snapshot ->
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java) ?: ""

                val updates = hashMapOf<String, Any>(
                    "events/${event.id}/attendees/$uid/fullName" to fullName,
                    "events/${event.id}/attendees/$uid/profileImageUrl" to profileImageUrl,
                    "events/${event.id}/attendeesCount" to ServerValue.increment(1)
                )

                database.reference.updateChildren(updates)
                    .addOnSuccessListener {
                        Toast.makeText(this, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_LONG).show()

                        event.organizer?.uid?.let { organizerId ->
                            createNotification(organizerId, "rsvp", "$fullName RSVP'd to ${event.title}.")
                        }
                        animateDetailsOut(detailsView)
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to RSVP: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    /**
     * Handle cancel RSVP action from event details dialog
     */
    private fun handleCancelRsvpFromDetails(event: Event, detailsView: View) {
        currentUserId?.let { uid ->
            val updates = hashMapOf<String, Any?>(
                "events/${event.id}/attendees/$uid" to null,
                "events/${event.id}/attendeesCount" to ServerValue.increment(-1)
            )

            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "RSVP cancelled for \"${event.title}\"", Toast.LENGTH_SHORT).show()
                    animateDetailsOut(detailsView)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to cancel RSVP: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * Animate event details dialog entrance with smooth scaling and fading
     */
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

        // Animate content elements sequentially
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

    /**
     * Animate event details dialog exit with fade and slide
     */
    private fun animateDetailsOut(detailsView: View) {
        val scrimFadeOut = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 0f)
        scrimFadeOut.duration = 300

        val cardFadeOut = ObjectAnimator.ofFloat(detailsView, "alpha", 0f)
        val cardSlideDownAnimator = ObjectAnimator.ofFloat(detailsView, "translationY", 100f)

        val cardAnimatorSet = AnimatorSet()
        cardAnimatorSet.playTogether(cardFadeOut, cardSlideDownAnimator)
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

    /**
     * Enable or disable user interaction with main content
     */
    private fun setMainContentInteraction(enabled: Boolean) {
        fun setViewAndChildrenEnabled(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    setViewAndChildrenEnabled(view.getChildAt(i), enabled)
                }
            }
        }

        val mainContent = findViewById<View>(R.id.mainContent) ?: return
        setViewAndChildrenEnabled(mainContent, enabled)
    }

    /**
     * Display contact modal for messaging the profile owner
     */
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
            // Handle modal creation errors gracefully
        }
    }

    /**
     * Setup contact modal input handlers and buttons
     */
    private fun setupContactModalListeners(modalView: View) {
        val closeButton = modalView.findViewById<ImageView>(R.id.closeButton)
        val cancelButton = modalView.findViewById<View>(R.id.cancelButton)
        val sendButton = modalView.findViewById<View>(R.id.sendButton)
        val nameInput = modalView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameInput)
        val emailInput = modalView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.emailInput)
        val messageInput = modalView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.messageInput)

        // Configure input types for better UX
        nameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        emailInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        messageInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // Pre-populate with current user's information
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid).get()
                .addOnSuccessListener { snapshot ->
                    val userName = snapshot.child("fullName").getValue(String::class.java) ?: ""
                    val userEmail = snapshot.child("email").getValue(String::class.java) ?: ""

                    nameInput.setText(userName)
                    emailInput.setText(userEmail)
                }
                .addOnFailureListener {
                    // Handle error silently, user can input manually
                }
        }

        // Setup button listeners
        closeButton.setOnClickListener { animateModalOut(modalView) }
        cancelButton.setOnClickListener { animateModalOut(modalView) }

        sendButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            val message = messageInput.text.toString().trim()

            // Validate input fields
            if (name.isEmpty() || email.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendContactMessage(modalView, name, email, message)
        }
    }

    /**
     * Send contact message via email service and notifications
     */
    private fun sendContactMessage(modalView: View, name: String, email: String, message: String) {
        try {
            showModalState(modalView, "loading")

            CoroutineScope(Dispatchers.Main).launch {
                profileUserId?.let { recipientId ->
                    database.reference.child("users").child(recipientId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val recipientEmail = snapshot.child("email").getValue(String::class.java)
                                val recipientName = snapshot.child("fullName").getValue(String::class.java) ?: "User"

                                if (recipientEmail != null) {
                                    // Send via email service
                                    sendEmailMessage(modalView, recipientId, recipientEmail, recipientName, name, email, message)
                                } else {
                                    // Fallback to notification only
                                    sendNotificationMessage(modalView, recipientId, name, message)
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                // Fallback to notification on database error
                                sendNotificationMessage(modalView, recipientId, name, message)
                            }
                        })
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
            showModalState(modalView, "form")
        }
    }

    /**
     * Send message via email service
     */
    private fun sendEmailMessage(modalView: View, recipientId: String, recipientEmail: String,
                                 recipientName: String, senderName: String, senderEmail: String, message: String) {
        val eventContext = "events organized by $recipientName"

        CoroutineScope(Dispatchers.Main).launch {
            val (success, errorMessage) = emailService.sendContactMessage(
                recipientEmail = recipientEmail,
                recipientName = recipientName,
                senderName = senderName,
                senderEmail = senderEmail,
                messageContent = message,
                eventContext = eventContext
            )

            if (success) {
                showModalState(modalView, "success")
                createEmailNotification(recipientId, senderName, senderEmail)

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    animateModalOut(modalView)
                }, 2000)

                Toast.makeText(this@PublicProfileActivity,
                    "Message sent successfully! They'll receive it via email.",
                    Toast.LENGTH_LONG).show()
            } else {
                // Email failed, use notification fallback
                showModalState(modalView, "success")
                createFallbackNotification(recipientId, senderName, message)

                Toast.makeText(this@PublicProfileActivity,
                    "Message sent via notification (email delivery failed)",
                    Toast.LENGTH_LONG).show()

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    animateModalOut(modalView)
                }, 2000)
            }
        }
    }

    /**
     * Send message via notification only
     */
    private fun sendNotificationMessage(modalView: View, recipientId: String, senderName: String, message: String) {
        showModalState(modalView, "success")
        createFallbackNotification(recipientId, senderName, message)

        Toast.makeText(this,
            "Message sent via notification",
            Toast.LENGTH_SHORT).show()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            animateModalOut(modalView)
        }, 2000)
    }

    /**
     * Create notification with email reference
     */
    private fun createEmailNotification(recipientId: String, senderName: String, senderEmail: String) {
        try {
            val notification = mapOf(
                "type" to "contact",
                "text" to "$senderName sent you a message about your events. Check your email for the full message.",
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "read" to false,
                "senderName" to senderName,
                "senderEmail" to senderEmail,
                "hasEmail" to true
            )

            database.reference.child("notifications").child(recipientId).push().setValue(notification)
        } catch (e: Exception) {
            // Handle notification creation error silently
        }
    }

    /**
     * Create fallback notification with full message content
     */
    private fun createFallbackNotification(recipientId: String, senderName: String, message: String) {
        try {
            val notification = mapOf(
                "type" to "contact",
                "text" to "$senderName sent you a message: \"${message.take(100)}${if (message.length > 100) "..." else ""}\"",
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "read" to false,
                "senderName" to senderName,
                "hasEmail" to false,
                "fullMessage" to message
            )

            database.reference.child("notifications").child(recipientId).push().setValue(notification)
        } catch (e: Exception) {
            // Handle notification creation error silently
        }
    }

    /**
     * Change contact modal display state
     */
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

    /**
     * Animate contact modal entrance
     */
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

    /**
     * Animate contact modal exit
     */
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

    /**
     * Handle RSVP action from event card
     */
    private fun handleRsvp(event: Event) {
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid).get().addOnSuccessListener { snapshot ->
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: "A User"
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java) ?: ""

                val updates = hashMapOf<String, Any>(
                    "events/${event.id}/attendees/$uid/fullName" to fullName,
                    "events/${event.id}/attendees/$uid/profileImageUrl" to profileImageUrl,
                    "events/${event.id}/attendeesCount" to ServerValue.increment(1)
                )

                database.reference.updateChildren(updates)
                    .addOnSuccessListener {
                        Toast.makeText(this, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_SHORT).show()
                        event.organizer?.uid?.let { organizerId ->
                            createNotification(organizerId, "rsvp", "$fullName RSVP'd to ${event.title}.")
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to RSVP", Toast.LENGTH_SHORT).show()
                    }
            }.addOnFailureListener {
                Toast.makeText(this, "Could not retrieve your user data to RSVP.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Cancel user's RSVP for an event
     */
    private fun cancelRsvp(event: Event) {
        currentUserId?.let { uid ->
            val updates = hashMapOf<String, Any?>(
                "events/${event.id}/attendees/$uid" to null,
                "events/${event.id}/attendeesCount" to ServerValue.increment(-1)
            )

            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this@PublicProfileActivity, "RSVP cancelled", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this@PublicProfileActivity, "Failed to cancel RSVP", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * Show confirmation dialog before cancelling RSVP
     */
    private fun showCancelRsvpDialog(event: Event) {
        AlertDialog.Builder(this)
            .setTitle("Cancel RSVP")
            .setMessage("Are you sure you want to cancel your RSVP for \"${event.title}\"?")
            .setPositiveButton("Yes, Cancel RSVP") { _, _ -> cancelRsvp(event) }
            .setNegativeButton("No", null)
            .show()
    }

    /**
     * Create notification in Firebase for the specified user
     */
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
            // Handle notification creation error silently
        }
    }

    /**
     * Format event date and time for display
     */
    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }

    /**
     * Clean up Firebase listeners when activity is destroyed
     */
    override fun onDestroy() {
        userDataListener?.let { listener ->
            userDataRef?.removeEventListener(listener)
        }

        eventsListener?.let { listener ->
            eventsRef?.removeEventListener(listener)
        }

        super.onDestroy()
    }

    /**
     * Stop refresh indicator if activity is paused
     */
    override fun onPause() {
        super.onPause()
        if (isRefreshing) {
            binding.swipeRefreshLayout.isRefreshing = false
            isRefreshing = false
        }
    }
}