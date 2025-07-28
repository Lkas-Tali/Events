package com.student.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
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
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityMainBinding
import com.student.events.models.Attendee
import com.student.events.models.DateTime
import com.student.events.models.Event
import com.student.events.models.Notification
import com.student.events.models.Organizer
import com.student.events.services.AuthStateManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity serves as the main hub for the Events application.
 * Features include event discovery, RSVP management, notifications, and user profile access.
 * Implements real-time data synchronization with Firebase Realtime Database.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var eventsAdapter: EventsAdapter

    // Session management
    private lateinit var sessionPrefs: SharedPreferences
    private lateinit var authStateListener: FirebaseAuth.AuthStateListener

    // Event data collections organized by user relationship
    private val allEvents = mutableListOf<Event>()
    private val myEvents = mutableListOf<Event>()
    private val attendingEvents = mutableListOf<Event>()
    private val notifications = mutableListOf<Notification>()

    // Display and pagination state for efficient loading
    private val displayedEvents = mutableListOf<Event>()
    private var currentDisplayedCount = 0
    private val EVENTS_PER_PAGE = 8
    private var isLoading = false
    private var hasMoreEvents = true
    private var isDataLoaded = false

    // User and application state
    private var currentUserId: String? = null
    private var currentTab = "discover"
    private var isAuthenticating = false

    // Filter and search state
    private var searchQuery = ""
    private var locationFilter = ""
    private var startDateFilter: Date? = null
    private var endDateFilter: Date? = null

    // Firebase listeners for proper cleanup
    private var userDataListener: ValueEventListener? = null
    private var userDataRef: DatabaseReference? = null
    private var eventsListener: ValueEventListener? = null
    private var eventsRef: DatabaseReference? = null
    private var notificationsListener: ValueEventListener? = null
    private var notificationsQuery: Query? = null

    companion object {
        private const val CREATE_EVENT_REQUEST = 1001
        private const val EDIT_EVENT_REQUEST = 1002
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure window for edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Verify user authentication before proceeding
        if (!checkUserAuthentication()) {
            return
        }

        initializeFirebaseMessaging()
        handleNotificationIntent(intent)
        configureSystemInsets()
        setupUserInterface()
        setupSwipeRefresh()

        // Show initial loading state
        binding.loadingMoreLayout.visibility = View.VISIBLE
        binding.emptyStateText.visibility = View.GONE

        setupAuthenticationMonitoring()
        initializeDataListeners()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Handle notification taps to navigate to specific events
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val eventId = intent?.getStringExtra("eventId")
        if (eventId != null) {
            database.reference.child("events").child(eventId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            val event = parseEventFromSnapshot(snapshot, eventId)
                            if (event != null) {
                                showCustomEventDetails(event, fromInviteNotification = true)
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        // Event loading failed - handle gracefully
                    }
                })
        }
    }

    /**
     * Initialize Firebase Cloud Messaging for push notifications
     */
    private fun initializeFirebaseMessaging() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                sendTokenToDatabase(token)
            }
        }
    }

    /**
     * Save FCM token to user's profile for targeted notifications
     */
    private fun sendTokenToDatabase(token: String) {
        if (currentUserId != null) {
            database.reference.child("users")
                .child(currentUserId!!)
                .child("fcmToken")
                .setValue(token)
        }
    }

    /**
     * Validate user session and redirect to login if invalid
     */
    private fun checkUserAuthentication(): Boolean {
        val authStateManager = AuthStateManager.getInstance(this)

        if (!authStateManager.validateSession()) {
            navigateToLogin()
            return false
        }

        currentUserId = sessionPrefs.getString(KEY_USER_ID, null)

        if (currentUserId.isNullOrEmpty()) {
            navigateToLogin()
            return false
        }
        return true
    }

    /**
     * Setup authentication state monitoring for session management
     */
    private fun setupAuthenticationMonitoring() {
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null && !isAuthenticating) {
                navigateToLogin()
            } else if (user != null) {
                currentUserId = user.uid
                updateSessionData(user.uid)
            }
        }
        auth.addAuthStateListener(authStateListener)
    }

    /**
     * Update session data with current timestamp
     */
    private fun updateSessionData(userId: String) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }
    }

    /**
     * Clear all stored session data
     */
    private fun clearSessionData() {
        sessionPrefs.edit().clear().apply()
    }

    /**
     * Navigate to login screen and clear activity stack
     */
    private fun navigateToLogin() {
        if (!isAuthenticating) {
            isAuthenticating = true
            cleanupListeners()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    /**
     * Configure system bar insets for edge-to-edge layout
     */
    private fun configureSystemInsets() {
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
     * Initialize all Firebase real-time data listeners
     */
    private fun initializeDataListeners() {
        setupUserDataListener()
        setupEventsListener()
        setupNotificationsListener()
    }

    /**
     * Listen for changes to current user's profile information
     */
    private fun setupUserDataListener() {
        currentUserId?.let { uid ->
            userDataListener?.let { listener ->
                userDataRef?.removeEventListener(listener)
            }

            userDataRef = database.reference.child("users").child(uid)
            userDataListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        // User data removed - redirect to login
                        clearSessionData()
                        navigateToLogin()
                        return
                    }

                    val fullName = snapshot.child("fullName").getValue(String::class.java)
                    val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                    val firstName = fullName?.split(" ")?.firstOrNull() ?: "User"
                    binding.userNameText.text = firstName
                    loadAvatarImage(profileImageUrl)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (error.code == DatabaseError.PERMISSION_DENIED) {
                        clearSessionData()
                        navigateToLogin()
                    }
                }
            }
            userDataRef?.addValueEventListener(userDataListener!!)
        }
    }

    /**
     * Listen for changes to all events in the database
     */
    private fun setupEventsListener() {
        eventsListener?.let { listener ->
            eventsRef?.removeEventListener(listener)
        }

        eventsRef = database.reference.child("events")
        eventsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                allEvents.clear()
                myEvents.clear()
                attendingEvents.clear()

                for (eventSnapshot in snapshot.children) {
                    val eventId = eventSnapshot.key ?: continue
                    val event = parseEventFromSnapshot(eventSnapshot, eventId)

                    if (event != null) {
                        allEvents.add(event)
                        if (event.organizer?.uid == currentUserId) {
                            myEvents.add(event)
                        }
                        if (event.attendees.containsKey(currentUserId)) {
                            attendingEvents.add(event)
                        }
                    }
                }

                // Sort events to prioritize upcoming events
                sortEventsByDate(allEvents)
                sortEventsByDate(myEvents)
                sortEventsByDate(attendingEvents)

                isDataLoaded = true
                resetAndLoadEvents()
                binding.swipeRefreshLayout.isRefreshing = false
            }

            override fun onCancelled(error: DatabaseError) {
                binding.loadingMoreLayout.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
                if (error.code == DatabaseError.PERMISSION_DENIED) {
                    clearSessionData()
                    navigateToLogin()
                }
            }
        }
        eventsRef?.addValueEventListener(eventsListener!!)
    }

    /**
     * Parse Firebase event data into Event object with error handling
     */
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
                Organizer(
                    uid = organizerSnapshot.child("uid").getValue(String::class.java) ?: "",
                    fullName = organizerSnapshot.child("fullName").getValue(String::class.java) ?: ""
                )
            } else null

            val dateTime = parseDateTime(snapshot)

            // Parse attendees map
            val attendeesMap = mutableMapOf<String, Attendee>()
            val attendeesSnapshot = snapshot.child("attendees")
            for (attendeeSnapshot in attendeesSnapshot.children) {
                val attendeeId = attendeeSnapshot.key ?: continue
                val attendee = Attendee(
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
     * Parse date and time from Firebase data with fallback support
     */
    private fun parseDateTime(snapshot: DataSnapshot): DateTime? {
        return try {
            val dateTimeSnapshot = snapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                val seconds = dateTimeSnapshot.child("_seconds").getValue(Long::class.java)
                    ?: dateTimeSnapshot.child("seconds").getValue(Long::class.java)
                    ?: 0L
                val nanoseconds = dateTimeSnapshot.child("_nanoseconds").getValue(Long::class.java)
                    ?: dateTimeSnapshot.child("nanoseconds").getValue(Long::class.java)
                    ?: 0L
                DateTime(seconds = seconds, nanoseconds = nanoseconds)
            } else {
                // Support legacy date/time format
                val dateString = snapshot.child("date").getValue(String::class.java)
                val timeString = snapshot.child("time").getValue(String::class.java)
                if (!dateString.isNullOrEmpty() && !timeString.isNullOrEmpty()) {
                    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val date = format.parse("$dateString $timeString")
                    date?.let { DateTime(seconds = it.time / 1000, nanoseconds = 0L) }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Listen for user notifications and update badge
     */
    private fun setupNotificationsListener() {
        currentUserId?.let { uid ->
            notificationsListener?.let { listener ->
                notificationsQuery?.removeEventListener(listener)
            }

            notificationsQuery = database.reference.child("notifications").child(uid)
                .orderByChild("timestamp")
                .limitToLast(20)

            notificationsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    notifications.clear()
                    for (notifSnapshot in snapshot.children) {
                        val notification = notifSnapshot.getValue(Notification::class.java)
                        notification?.let {
                            it.id = notifSnapshot.key ?: ""
                            notifications.add(0, it)
                        }
                    }
                    updateNotificationBadge()
                }

                override fun onCancelled(error: DatabaseError) {
                    // Notification loading failed - continue without notifications
                }
            }
            notificationsQuery?.addValueEventListener(notificationsListener!!)
        }
    }

    /**
     * Load user avatar image with Glide
     */
    private fun loadAvatarImage(profileImageUrl: String?) {
        if (!profileImageUrl.isNullOrEmpty()) {
            Glide.with(this@MainActivity)
                .load(profileImageUrl)
                .placeholder(R.drawable.circular_avatar)
                .error(R.drawable.circular_avatar)
                .circleCrop()
                .into(binding.userAvatarImage)
        } else {
            // Set default avatar placeholder
            binding.userAvatarImage.setBackgroundResource(R.drawable.circular_avatar)
            binding.userAvatarImage.setImageResource(R.drawable.ic_person)
            binding.userAvatarImage.scaleType = ImageView.ScaleType.CENTER
        }
    }

    /**
     * Initialize UI components and set up event handlers
     */
    private fun setupUserInterface() {
        eventsAdapter = EventsAdapter(
            events = displayedEvents,
            currentUserId = currentUserId ?: "",
            onEventClick = { event -> showCustomEventDetails(event, fromInviteNotification = false) },
            onEditClick = { event -> showEditEventDialog(event) },
            onCancelClick = { event -> showCancelEventDialog(event) },
            onRsvpClick = { event -> handleRsvp(event) },
            onCancelRsvpClick = { event -> showCancelRsvpDialog(event) }
        )

        binding.eventsRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, getSpanCount())
            adapter = eventsAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }

        // Implement infinite scrolling
        binding.nestedScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            if (v.getChildAt(v.childCount - 1) != null) {
                if ((scrollY >= (v.getChildAt(v.childCount - 1).measuredHeight - v.measuredHeight)) &&
                    scrollY > oldScrollY && isDataLoaded
                ) {
                    loadMoreEvents()
                }
            }
        })

        // Tab selection for filtering events
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = when (tab?.position) {
                    1 -> "myEvents"
                    2 -> "attending"
                    else -> "discover"
                }
                if (isDataLoaded) {
                    resetAndLoadEvents()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Search and filter functionality
        binding.searchEditText.addTextChangedListener { text ->
            searchQuery = text.toString()
            if (isDataLoaded) resetAndLoadEvents()
        }
        binding.locationFilterInput.addTextChangedListener { text ->
            locationFilter = text.toString()
            if (isDataLoaded) resetAndLoadEvents()
        }

        setupButtonListeners()
    }

    /**
     * Setup click listeners for all buttons
     */
    private fun setupButtonListeners() {
        binding.filterButton.setOnClickListener {
            binding.filterPanel.visibility = if (binding.filterPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.createEventButton.setOnClickListener {
            startActivityForResult(Intent(this, CreateEventActivity::class.java), CREATE_EVENT_REQUEST)
        }

        binding.logoutButton.setOnClickListener { showLogoutConfirmation() }
        binding.notificationBell.setOnClickListener { showNotificationsBottomSheet() }
        binding.clearFiltersButton.setOnClickListener { clearFilters() }

        binding.startDateInput.setOnClickListener {
            showDatePicker { date ->
                startDateFilter = date
                binding.startDateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(date))
                if (isDataLoaded) resetAndLoadEvents()
            }
        }

        binding.endDateInput.setOnClickListener {
            showDatePicker { date ->
                endDateFilter = date
                binding.endDateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(date))
                if (isDataLoaded) resetAndLoadEvents()
            }
        }

        binding.profileAvatarFrame.setOnClickListener {
            startActivity(ProfileActivity.newIntent(this))
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
            refreshAllData()
        }
    }

    /**
     * Refresh all data from Firebase
     */
    private fun refreshAllData() {
        isDataLoaded = false
        allEvents.clear()
        myEvents.clear()
        attendingEvents.clear()
        notifications.clear()
        displayedEvents.clear()
        currentDisplayedCount = 0
        hasMoreEvents = true
        eventsAdapter.notifyDataSetChanged()
        initializeDataListeners()
    }

    /**
     * Reset displayed events and reload first page
     */
    private fun resetAndLoadEvents() {
        if (!isDataLoaded) return

        displayedEvents.clear()
        currentDisplayedCount = 0
        hasMoreEvents = true
        eventsAdapter.notifyDataSetChanged()
        loadMoreEvents()
    }

    /**
     * Load next batch of events for infinite scrolling
     */
    private fun loadMoreEvents() {
        if (isLoading || !hasMoreEvents || !isDataLoaded) return

        isLoading = true
        binding.loadingMoreLayout.visibility = View.VISIBLE

        val sourceList = when (currentTab) {
            "discover" -> allEvents
            "myEvents" -> myEvents
            "attending" -> attendingEvents
            else -> emptyList()
        }

        val filteredList = filterEvents(sourceList)
        val startIndex = currentDisplayedCount
        val endIndex = (startIndex + EVENTS_PER_PAGE).coerceAtMost(filteredList.size)

        if (startIndex < endIndex) {
            val newEvents = filteredList.subList(startIndex, endIndex)
            displayedEvents.addAll(newEvents)
            eventsAdapter.notifyItemRangeInserted(startIndex, newEvents.size)
            currentDisplayedCount += newEvents.size
        }

        hasMoreEvents = currentDisplayedCount < filteredList.size
        updateEmptyState(displayedEvents.isEmpty())
        binding.loadingMoreLayout.visibility = if (hasMoreEvents) View.VISIBLE else View.GONE
        isLoading = false
    }

    /**
     * Sort events by date with past events at the bottom
     */
    private fun sortEventsByDate(events: MutableList<Event>) {
        val currentTime = System.currentTimeMillis() / 1000
        events.sortWith(compareBy<Event> { (it.dateTime?.seconds ?: Long.MAX_VALUE) < currentTime }
            .thenBy { it.dateTime?.seconds ?: Long.MAX_VALUE })
    }

    /**
     * Show or hide empty state message
     */
    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateText.visibility = if (isEmpty && !isLoading && isDataLoaded) View.VISIBLE else View.GONE
        if (isEmpty && isDataLoaded) {
            binding.emptyStateText.text = when (currentTab) {
                "discover" -> "No events to discover yet."
                "myEvents" -> "You haven't created any events yet."
                "attending" -> "You're not attending any events yet."
                else -> "No events available."
            }
        }
    }

    /**
     * Update notification badge visibility
     */
    private fun updateNotificationBadge() {
        val unreadCount = notifications.count { !it.read }
        binding.notificationBadge.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * Apply search and filter criteria to events list
     */
    private fun filterEvents(events: List<Event>): List<Event> {
        val currentTime = System.currentTimeMillis() / 1000
        return events.filter { event ->
            val matchesSearch = searchQuery.isEmpty() ||
                    event.title.contains(searchQuery, ignoreCase = true) ||
                    event.description.contains(searchQuery, ignoreCase = true)
            val matchesLocation = locationFilter.isEmpty() ||
                    event.location.contains(locationFilter, ignoreCase = true)
            val eventDate = event.dateTime?.seconds?.let { Date(it * 1000) }
            val matchesDateRange =
                (startDateFilter == null || eventDate?.after(startDateFilter) != false) &&
                        (endDateFilter == null || eventDate?.before(endDateFilter) != false)
            // Discover tab shows only upcoming events
            val isFutureEventForDiscover = if (currentTab == "discover") {
                event.dateTime?.seconds ?: 0 > currentTime
            } else {
                true
            }
            matchesSearch && matchesLocation && matchesDateRange && isFutureEventForDiscover
        }
    }

    /**
     * Display custom animated event details dialog
     */
    private fun showCustomEventDetails(event: Event, fromInviteNotification: Boolean = false) {
        setMainContentInteraction(false)
        val detailsView = LayoutInflater.from(this).inflate(R.layout.dialog_event_details, binding.eventDetailsContainer, false)

        // Ensure the view is added and visible before populating with data
        binding.eventDetailsContainer.addView(detailsView)
        detailsView.visibility = View.VISIBLE

        // Populate all text and content immediately
        populateDetailsView(detailsView, event, fromInviteNotification)

        // Show background and start animation
        binding.darkScrim.visibility = View.VISIBLE
        animateDetailsIn(detailsView)
    }

    /**
     * Populate event details view with event data
     */
    private fun populateDetailsView(view: View, event: Event, fromInviteNotification: Boolean) {
        view.findViewById<TextView>(R.id.eventTitle).text = event.title
        view.findViewById<ImageView>(R.id.closeButton).setOnClickListener { animateDetailsOut(view) }

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
        val organizerName = event.organizer?.fullName ?: "Unknown"
        val organizerUid = event.organizer?.uid
        val organizerSection = view.findViewById<LinearLayout>(R.id.organizerClickableSection)
        val organizerHintText = view.findViewById<TextView>(R.id.organizerHintText)
        val organizerArrow = view.findViewById<ImageView>(R.id.organizerArrow)

        if (organizerUid != null && organizerUid == currentUserId) {
            // Current user is the organizer
            organizerText.text = "$organizerName (You)"
            organizerText.setTextColor(resources.getColor(R.color.app_text_secondary, null))
            organizerSection.isClickable = false
            organizerHintText.visibility = View.GONE
            organizerArrow.visibility = View.GONE
        } else {
            // Another user is the organizer
            organizerText.text = organizerName
            organizerText.setTextColor(resources.getColor(R.color.app_primary_blue, null))
            organizerHintText.visibility = View.VISIBLE
            organizerArrow.visibility = View.VISIBLE
            organizerSection.setOnClickListener {
                event.organizer?.let { organizer ->
                    animateDetailsOut(view)
                    Handler(Looper.getMainLooper()).postDelayed({
                        startActivity(PublicProfileActivity.newIntent(this@MainActivity, organizer.uid, organizer.fullName))
                    }, 300)
                }
            }
        }

        configureActionButtons(view, event, fromInviteNotification)
    }

    /**
     * Configure action buttons for event details view
     */
    private fun configureActionButtons(view: View, event: Event, fromInviteNotification: Boolean) {
        val actionButtonsContainer = view.findViewById<LinearLayout>(R.id.actionButtonsContainer)
        val invitationContextText = view.findViewById<TextView>(R.id.invitationContextText)
        val actionButton = view.findViewById<Button>(R.id.actionButton)
        val secondaryButton = view.findViewById<Button>(R.id.secondaryButton)

        if (!fromInviteNotification) {
            actionButtonsContainer.visibility = View.GONE
            return
        }

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
                    Toast.makeText(this@MainActivity, "Invitation declined", Toast.LENGTH_SHORT).show()
                }
            }
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
                    "events/${event.id}/attendeesCount" to event.attendeesCount + 1
                )

                database.reference.updateChildren(updates).addOnSuccessListener {
                    Toast.makeText(this, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_LONG).show()
                    event.organizer?.uid?.let { organizerId ->
                        createNotification(organizerId, "rsvp", "$fullName RSVP'd to ${event.title}.")
                    }
                    animateDetailsOut(detailsView)
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
                "events/${event.id}/attendeesCount" to (event.attendeesCount - 1).coerceAtLeast(0)
            )
            database.reference.updateChildren(updates).addOnSuccessListener {
                Toast.makeText(this, "RSVP cancelled for \"${event.title}\"", Toast.LENGTH_SHORT).show()
                animateDetailsOut(detailsView)
            }
        }
    }

    /**
     * Animate event details view entrance
     */
    private fun animateDetailsIn(detailsView: View) {
        // Ensure container is visible first
        binding.eventDetailsContainer.visibility = View.VISIBLE

        // Background scrim fade in
        val scrimFadeIn = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 1f)
        scrimFadeIn.duration = 400

        // Modal card animation
        detailsView.alpha = 0f
        detailsView.scaleX = 0.8f
        detailsView.scaleY = 0.8f
        detailsView.visibility = View.VISIBLE

        val cardFadeIn = ObjectAnimator.ofFloat(detailsView, "alpha", 1f)
        val cardScaleX = ObjectAnimator.ofFloat(detailsView, "scaleX", 1f)
        val cardScaleY = ObjectAnimator.ofFloat(detailsView, "scaleY", 1f)

        val cardAnimatorSet = AnimatorSet()
        cardAnimatorSet.playTogether(cardFadeIn, cardScaleX, cardScaleY)
        cardAnimatorSet.interpolator = OvershootInterpolator(1.1f)
        cardAnimatorSet.duration = 500

        // Content staggered animation - THIS IS THE KEY PART!
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
     * Animate event details view exit
     */
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

    /**
     * Enable or disable interaction with main content
     */
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
        val mainContent = findViewById<View>(R.id.mainContent) ?: binding.mainContent
        setViewAndChildrenEnabled(mainContent, enabled)
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
     * Calculate grid span count based on screen width
     */
    private fun getSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val dpWidth = displayMetrics.widthPixels / displayMetrics.density
        return (dpWidth / 296).toInt().coerceAtLeast(1)
    }

    /**
     * Clear all active filters and reset view
     */
    private fun clearFilters() {
        searchQuery = ""
        locationFilter = ""
        startDateFilter = null
        endDateFilter = null
        binding.searchEditText.setText("")
        binding.locationFilterInput.setText("")
        binding.startDateInput.setText("")
        binding.endDateInput.setText("")
        if (isDataLoaded) {
            resetAndLoadEvents()
        }
    }

    /**
     * Handle RSVP action from event card
     */
    private fun handleRsvp(event: Event) {
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid).get().addOnSuccessListener { userSnapshot ->
                val fullName = userSnapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                val profileImageUrl = userSnapshot.child("profileImageUrl").getValue(String::class.java) ?: ""
                val updates = hashMapOf<String, Any>(
                    "events/${event.id}/attendees/$uid/fullName" to fullName,
                    "events/${event.id}/attendees/$uid/profileImageUrl" to profileImageUrl,
                    "events/${event.id}/attendeesCount" to ServerValue.increment(1)
                )
                database.reference.updateChildren(updates).addOnSuccessListener {
                    Toast.makeText(this@MainActivity, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_SHORT).show()
                    event.organizer?.uid?.let { organizerId ->
                        createNotification(organizerId, "rsvp", "$fullName RSVP'd to ${event.title}.")
                    }
                }
            }
        }
    }

    /**
     * Show edit event dialog
     */
    private fun showEditEventDialog(event: Event) {
        val intent = Intent(this, CreateEventActivity::class.java).apply {
            putExtra("editMode", true)
            putExtra("eventId", event.id)
            putExtra("eventTitle", event.title)
            event.dateTime?.seconds?.let { seconds ->
                val date = Date(seconds * 1000)
                putExtra("eventDate", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date))
                putExtra("eventTime", SimpleDateFormat("HH:mm", Locale.US).format(date))
            }
            putExtra("eventLocation", event.location)
            putExtra("eventDescription", event.description)
            putExtra("eventImage", event.imageUrl)
        }
        startActivityForResult(intent, EDIT_EVENT_REQUEST)
    }

    /**
     * Show cancel event confirmation dialog
     */
    private fun showCancelEventDialog(event: Event) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Event")
            .setMessage("Are you sure you want to permanently cancel and delete \"${event.title}\"? This action cannot be undone.")
            .setPositiveButton("Yes, Cancel Event") { _, _ -> deleteEvent(event) }
            .setNegativeButton("No", null)
            .show()
    }

    /**
     * Show cancel RSVP confirmation dialog
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
     * Delete event and associated image from storage
     */
    private fun deleteEvent(event: Event) {
        if (!event.imageUrl.isNullOrEmpty()) {
            val photoRef = FirebaseStorage.getInstance().getReferenceFromUrl(event.imageUrl)
            photoRef.delete().addOnSuccessListener {
                deleteEventFromDatabase(event.id)
            }
        } else {
            deleteEventFromDatabase(event.id)
        }
    }

    /**
     * Remove event from Firebase database
     */
    private fun deleteEventFromDatabase(eventId: String) {
        database.reference.child("events").child(eventId).removeValue()
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
        }
    }

    /**
     * Show date picker dialog
     */
    private fun showDatePicker(onDateSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance()
        android.app.DatePickerDialog(
            this, { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                onDateSelected(calendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * Show notifications bottom sheet
     */
    private fun showNotificationsBottomSheet() {
        val bottomSheet = NotificationsBottomSheet(
            context = this,
            notifications = notifications,
            onMarkAllAsRead = { markAllNotificationsAsRead() },
            onShowEventDetails = { event, isInvitation -> showCustomEventDetails(event, fromInviteNotification = isInvitation) },
            onNavigateToProfile = { userId, userName ->
                startActivity(PublicProfileActivity.newIntent(this, userId, userName))
            }
        )
        bottomSheet.show()
    }

    /**
     * Mark all notifications as read in database
     */
    private fun markAllNotificationsAsRead() {
        currentUserId?.let { uid ->
            val updates = mutableMapOf<String, Any>()
            notifications.forEach { notification ->
                if (!notification.read) {
                    updates["notifications/$uid/${notification.id}/read"] = true
                }
            }
            if (updates.isNotEmpty()) {
                database.reference.updateChildren(updates)
            }
        }
    }

    /**
     * Create notification for a user
     */
    private fun createNotification(userId: String, type: String, text: String) {
        val notification = mapOf(
            "type" to type,
            "text" to text,
            "timestamp" to ServerValue.TIMESTAMP,
            "read" to false
        )
        database.reference.child("notifications").child(userId).push().setValue(notification)
    }

    /**
     * Show logout confirmation dialog
     */
    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Perform user logout and navigate to login screen
     */
    private fun performLogout() {
        clearSessionData()
        cleanupListeners()
        auth.signOut()
        navigateToLogin()
    }

    /**
     * Clean up all Firebase listeners to prevent memory leaks
     */
    private fun cleanupListeners() {
        if (::authStateListener.isInitialized) {
            auth.removeAuthStateListener(authStateListener)
        }
        userDataListener?.let { listener -> userDataRef?.removeEventListener(listener) }
        eventsListener?.let { listener -> eventsRef?.removeEventListener(listener) }
        notificationsListener?.let { listener -> notificationsQuery?.removeEventListener(listener) }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupListeners()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                CREATE_EVENT_REQUEST -> {
                    Toast.makeText(this, "Event created successfully!", Toast.LENGTH_SHORT).show()
                    binding.tabLayout.getTabAt(1)?.select()
                }
                EDIT_EVENT_REQUEST -> {
                    Toast.makeText(this, "Event updated successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}