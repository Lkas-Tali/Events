package com.student.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.core.content.ContextCompat
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
import com.google.firebase.database.*
import com.google.firebase.database.ServerValue
import com.google.firebase.storage.FirebaseStorage
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityMainBinding
import com.student.events.models.Event
import com.student.events.models.Notification
import com.student.events.models.Organizer
import com.student.events.models.DateTime
import com.student.events.models.Attendee
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var eventsAdapter: EventsAdapter

    private val allEvents = mutableListOf<Event>()
    private val myEvents = mutableListOf<Event>()
    private val attendingEvents = mutableListOf<Event>()
    private val notifications = mutableListOf<Notification>()

    private val displayedEvents = mutableListOf<Event>()
    private var currentDisplayedCount = 0
    private val EVENTS_PER_PAGE = 8 // Increased to show more events at once
    private var isLoading = false
    private var hasMoreEvents = true
    private var isDataLoaded = false // Flag to track if initial data is loaded

    private var currentUserId: String? = null
    private var currentTab = "discover"

    // Filters
    private var searchQuery = ""
    private var locationFilter = ""
    private var startDateFilter: Date? = null
    private var endDateFilter: Date? = null

    // REAL-TIME LISTENERS
    private var userDataListener: ValueEventListener? = null
    private var userDataRef: DatabaseReference? = null
    private var eventsListener: ValueEventListener? = null
    private var eventsRef: DatabaseReference? = null
    private var notificationsListener: ValueEventListener? = null
    private var notificationsQuery: Query? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val CREATE_EVENT_REQUEST = 1001
        private const val EDIT_EVENT_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Configure system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentUserId = auth.currentUser?.uid

        Log.d(TAG, "onCreate - Current User ID: $currentUserId")

        if (currentUserId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        applySystemBarInsets()
        setupViews()

        // Show initial loading state
        binding.loadingMoreLayout.visibility = View.VISIBLE
        binding.emptyStateText.visibility = View.GONE

        // Start real-time listeners
        setupRealTimeListeners()
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

    // Setup all real-time listeners
    private fun setupRealTimeListeners() {
        Log.d(TAG, "Setting up real-time listeners")
        setupUserDataListener()
        setupEventsListener()
        setupNotificationsListener()
    }

    // Real-time user data listener
    private fun setupUserDataListener() {
        currentUserId?.let { uid ->
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
                        val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                        Log.d(TAG, "User data updated - Name: $fullName, Image: ${!profileImageUrl.isNullOrEmpty()}")

                        // Extract first name only
                        val firstName = fullName?.split(" ")?.firstOrNull()
                            ?: auth.currentUser?.displayName?.split(" ")?.firstOrNull()
                            ?: "User"

                        // Update header UI
                        binding.userNameText.text = firstName

                        // Load avatar
                        loadAvatarImage(profileImageUrl)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing user data: ${e.message}", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to load user data: ${error.message}")
                    // Fallback to auth user data
                    val fallbackName = auth.currentUser?.displayName?.split(" ")?.firstOrNull() ?: "User"
                    binding.userNameText.text = fallbackName
                    loadAvatarImage(null)
                }
            }

            userDataRef?.addValueEventListener(userDataListener!!)
        }
    }

    // Real-time events listener - FIXED FOR PROPER LOADING
    private fun setupEventsListener() {
        // Remove any existing listener first
        eventsListener?.let { listener ->
            eventsRef?.removeEventListener(listener)
        }

        Log.d(TAG, "Setting up events listener")
        eventsRef = database.reference.child("events")

        eventsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    Log.d(TAG, "Events data changed. Total events: ${snapshot.childrenCount}")

                    // Clear lists
                    allEvents.clear()
                    myEvents.clear()
                    attendingEvents.clear()

                    // Process each event
                    for (eventSnapshot in snapshot.children) {
                        try {
                            val eventId = eventSnapshot.key ?: continue
                            Log.d(TAG, "Processing event with ID: $eventId")

                            // Manual parsing to handle different data structures
                            val event = parseEventFromSnapshot(eventSnapshot, eventId)

                            if (event != null) {
                                allEvents.add(event)
                                Log.d(TAG, "Successfully added event: ${event.title}")

                                // Categorize events
                                if (event.organizer?.uid == currentUserId) {
                                    myEvents.add(event)
                                    Log.d(TAG, "Added to myEvents: ${event.title}")
                                }
                                if (event.attendees.containsKey(currentUserId)) {
                                    attendingEvents.add(event)
                                    Log.d(TAG, "Added to attendingEvents: ${event.title}")
                                }
                            } else {
                                Log.e(TAG, "Failed to parse event: $eventId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing event ${eventSnapshot.key}: ${e.message}", e)
                        }
                    }

                    Log.d(TAG, "Final counts - All: ${allEvents.size}, My: ${myEvents.size}, Attending: ${attendingEvents.size}")

                    // Sort events by date
                    sortEventsByDate(allEvents)
                    sortEventsByDate(myEvents)
                    sortEventsByDate(attendingEvents)

                    // Mark data as loaded
                    isDataLoaded = true

                    // Load events to display
                    resetAndLoadEvents()

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing events data: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "Error loading events: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.loadingMoreLayout.visibility = View.GONE
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to load events: ${error.code} - ${error.message}")
                binding.loadingMoreLayout.visibility = View.GONE

                val errorMessage = when (error.code) {
                    DatabaseError.PERMISSION_DENIED -> "Permission denied. Please check your authentication."
                    DatabaseError.NETWORK_ERROR -> "Network error. Please check your connection."
                    else -> "Failed to load events: ${error.message}"
                }
                Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
            }
        }

        eventsRef?.addValueEventListener(eventsListener!!)
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
                Organizer(
                    uid = organizerSnapshot.child("uid").getValue(String::class.java) ?: "",
                    fullName = organizerSnapshot.child("fullName").getValue(String::class.java) ?: ""
                )
            } else null

            // COMPATIBILITY: Parse dateTime (handle both old and new formats)
            val dateTime = parseDateTime(snapshot)

            // Parse attendees
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
            Log.e(TAG, "Error manually parsing event: ${e.message}", e)
            null
        }
    }

    private fun parseDateTime(snapshot: DataSnapshot): DateTime? {
        return try {
            // Try new format first
            val dateTimeSnapshot = snapshot.child("dateTime")
            if (dateTimeSnapshot.exists()) {
                DateTime(
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
                        DateTime(
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

    // Real-time notifications listener
    private fun setupNotificationsListener() {
        currentUserId?.let { uid ->
            // Remove any existing listener first
            notificationsListener?.let { listener ->
                notificationsQuery?.removeEventListener(listener)
            }

            Log.d(TAG, "Setting up notifications listener for UID: $uid")
            notificationsQuery = database.reference.child("notifications").child(uid)
                .orderByChild("timestamp")
                .limitToLast(20)

            notificationsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d(TAG, "Notifications data updated. Count: ${snapshot.childrenCount}")
                        notifications.clear()
                        for (notifSnapshot in snapshot.children) {
                            val notification = notifSnapshot.getValue(Notification::class.java)
                            notification?.let {
                                it.id = notifSnapshot.key ?: ""
                                notifications.add(0, it)
                            }
                        }
                        updateNotificationBadge()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing notifications data: ${e.message}", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to load notifications: ${error.message}")
                }
            }

            notificationsQuery?.addValueEventListener(notificationsListener!!)
        }
    }

    // Enhanced avatar loading function
    private fun loadAvatarImage(profileImageUrl: String?) {
        if (!profileImageUrl.isNullOrEmpty()) {
            Log.d(TAG, "Loading avatar image from URL: $profileImageUrl")
            Glide.with(this@MainActivity)
                .load(profileImageUrl)
                .placeholder(R.drawable.circular_avatar)
                .error(R.drawable.circular_avatar)
                .circleCrop()
                .skipMemoryCache(false)
                .into(binding.userAvatarImage)
        } else {
            Log.d(TAG, "Loading default avatar")
            binding.userAvatarImage.setBackgroundResource(R.drawable.circular_avatar)
            binding.userAvatarImage.setImageResource(R.drawable.ic_person)
            binding.userAvatarImage.scaleType = ImageView.ScaleType.CENTER
            binding.userAvatarImage.setPadding(8, 8, 8, 8)
        }
    }

    private fun setupViews() {
        eventsAdapter = EventsAdapter(
            events = displayedEvents,
            currentUserId = currentUserId ?: "",
            onEventClick = { event -> showCustomEventDetails(event) },
            onEditClick = { event -> showEditEventDialog(event) },
            onCancelClick = { event -> showCancelEventDialog(event) },
            onRsvpClick = { event -> handleRsvp(event) },
            onCancelRsvpClick = { event -> showCancelRsvpDialog(event) }
        )

        val layoutManager = GridLayoutManager(this@MainActivity, getSpanCount())
        binding.eventsRecyclerView.apply {
            this.layoutManager = layoutManager
            adapter = eventsAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }

        binding.nestedScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            if (v.getChildAt(v.childCount - 1) != null) {
                if ((scrollY >= (v.getChildAt(v.childCount - 1).measuredHeight - v.measuredHeight)) &&
                    scrollY > oldScrollY && isDataLoaded) {
                    loadMoreEvents()
                }
            }
        })

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = when (tab?.position) {
                    1 -> "myEvents"
                    2 -> "attending"
                    else -> "discover"
                }
                Log.d(TAG, "Tab selected: $currentTab")
                if (isDataLoaded) {
                    resetAndLoadEvents()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.searchEditText.addTextChangedListener { text ->
            searchQuery = text.toString()
            if (isDataLoaded) {
                resetAndLoadEvents()
            }
        }

        binding.locationFilterInput.addTextChangedListener { text ->
            locationFilter = text.toString()
            if (isDataLoaded) {
                resetAndLoadEvents()
            }
        }

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
                if (isDataLoaded) {
                    resetAndLoadEvents()
                }
            }
        }

        binding.endDateInput.setOnClickListener {
            showDatePicker { date ->
                endDateFilter = date
                binding.endDateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(date))
                if (isDataLoaded) {
                    resetAndLoadEvents()
                }
            }
        }

        binding.profileAvatarFrame.setOnClickListener {
            startActivity(ProfileActivity.newIntent(this))
        }
    }

    private fun resetAndLoadEvents() {
        if (!isDataLoaded) {
            Log.d(TAG, "Data not loaded yet, skipping resetAndLoadEvents")
            return
        }

        Log.d(TAG, "resetAndLoadEvents called for tab: $currentTab")
        displayedEvents.clear()
        currentDisplayedCount = 0
        hasMoreEvents = true
        eventsAdapter.notifyDataSetChanged()
        loadMoreEvents()
    }

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

        Log.d(TAG, "Loading events from $currentTab tab. Source list size: ${sourceList.size}")

        val filteredList = filterEvents(sourceList)
        Log.d(TAG, "After filtering: ${filteredList.size} events")

        val startIndex = currentDisplayedCount
        val endIndex = (startIndex + EVENTS_PER_PAGE).coerceAtMost(filteredList.size)

        if (startIndex < endIndex) {
            val newEvents = filteredList.subList(startIndex, endIndex)
            displayedEvents.addAll(newEvents)
            eventsAdapter.notifyItemRangeInserted(startIndex, newEvents.size)
            currentDisplayedCount += newEvents.size
            Log.d(TAG, "Added ${newEvents.size} events. Total displayed: $currentDisplayedCount")
        }

        hasMoreEvents = currentDisplayedCount < filteredList.size
        updateEmptyState(displayedEvents.isEmpty())
        binding.loadingMoreLayout.visibility = if (hasMoreEvents && filteredList.size > currentDisplayedCount) View.VISIBLE else View.GONE
        isLoading = false
    }

    private fun sortEventsByDate(events: MutableList<Event>) {
        events.sortBy { it.dateTime?.seconds ?: Long.MAX_VALUE }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateText.visibility = if (isEmpty && !isLoading && isDataLoaded) View.VISIBLE else View.GONE
        if(isEmpty && isDataLoaded) {
            binding.emptyStateText.text = when (currentTab) {
                "discover" -> "No events to discover yet."
                "myEvents" -> "You haven't created any events yet."
                "attending" -> "You're not attending any events yet."
                else -> "No events available."
            }
            Log.d(TAG, "Showing empty state: ${binding.emptyStateText.text}")
        }
    }

    private fun updateNotificationBadge() {
        val unreadCount = notifications.count { !it.read }
        binding.notificationBadge.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
        Log.d(TAG, "Notification badge updated: $unreadCount unread")
    }

    private fun filterEvents(events: List<Event>): List<Event> {
        return events.filter { event ->
            val matchesSearch = searchQuery.isEmpty() ||
                    event.title.contains(searchQuery, ignoreCase = true) ||
                    event.description.contains(searchQuery, ignoreCase = true)
            val matchesLocation = locationFilter.isEmpty() ||
                    event.location.contains(locationFilter, ignoreCase = true)
            val eventDate = event.dateTime?.seconds?.let { Date(it * 1000) }
            val matchesDateRange = (startDateFilter == null || eventDate?.after(startDateFilter) != false) &&
                    (endDateFilter == null || eventDate?.before(endDateFilter) != false)
            matchesSearch && matchesLocation && matchesDateRange
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

        // Handle organizer section
        val organizerText = view.findViewById<TextView>(R.id.organizerText)
        val organizerClickableSection = view.findViewById<LinearLayout>(R.id.organizerClickableSection)
        val organizerHintText = view.findViewById<TextView>(R.id.organizerHintText)
        val organizerArrow = view.findViewById<ImageView>(R.id.organizerArrow)

        val organizerName = event.organizer?.fullName ?: "Unknown"
        organizerText.text = organizerName

        val organizerUid = event.organizer?.uid
        if (organizerUid != null && organizerUid != currentUserId) {
            organizerClickableSection.setOnClickListener {
                animateDetailsOut(view)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val intent = PublicProfileActivity.newIntent(this@MainActivity, organizerUid, organizerName)
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

        // NEW: Handle invitation status and buttons
        currentUserId?.let { uid ->
            checkAndUpdateInvitationStatus(view, event, uid)
        }
    }

    private fun checkAndUpdateInvitationStatus(view: View, event: Event, userId: String) {
        // Check if user was invited to this event
        database.reference.child("events").child(event.id).child("invitations").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val actionButton = view.findViewById<Button>(R.id.actionButton)
                    val secondaryButton = view.findViewById<Button>(R.id.secondaryButton)

                    if (snapshot.exists()) {
                        // User was invited
                        val invitationStatus = snapshot.child("status").getValue(String::class.java) ?: "pending"

                        when (invitationStatus) {
                            "pending" -> {
                                // Show Accept/Decline buttons
                                actionButton.text = "Accept Invitation"
                                actionButton.setBackgroundColor(resources.getColor(R.color.app_success, null))
                                actionButton.setOnClickListener { acceptInvitation(event, userId) }
                                actionButton.visibility = View.VISIBLE

                                secondaryButton.text = "Decline"
                                secondaryButton.setBackgroundColor(resources.getColor(R.color.app_error, null))
                                secondaryButton.setOnClickListener { declineInvitation(event, userId) }
                                secondaryButton.visibility = View.VISIBLE
                            }
                            "accepted" -> {
                                // Show that invitation was accepted, allow to cancel RSVP
                                actionButton.text = "Cancel RSVP"
                                actionButton.setBackgroundColor(resources.getColor(R.color.app_error, null))
                                actionButton.setOnClickListener { showCancelRsvpDialog(event) }
                                actionButton.visibility = View.VISIBLE
                                secondaryButton.visibility = View.GONE
                            }
                            "declined" -> {
                                // Show option to accept invitation again
                                actionButton.text = "Accept Invitation"
                                actionButton.setBackgroundColor(resources.getColor(R.color.app_success, null))
                                actionButton.setOnClickListener { acceptInvitation(event, userId) }
                                actionButton.visibility = View.VISIBLE
                                secondaryButton.visibility = View.GONE
                            }
                        }
                    } else {
                        // User not invited, show regular RSVP/Cancel buttons
                        if (event.attendees.containsKey(userId)) {
                            actionButton.text = "Cancel RSVP"
                            actionButton.setBackgroundColor(resources.getColor(R.color.app_error, null))
                            actionButton.setOnClickListener { showCancelRsvpDialog(event) }
                        } else {
                            actionButton.text = "RSVP"
                            actionButton.setBackgroundColor(resources.getColor(R.color.app_primary_blue, null))
                            actionButton.setOnClickListener { handleRsvp(event) }
                        }
                        actionButton.visibility = View.VISIBLE
                        secondaryButton.visibility = View.GONE
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Handle error silently, show default RSVP button
                    val actionButton = view.findViewById<Button>(R.id.actionButton)
                    actionButton.text = "RSVP"
                    actionButton.setBackgroundColor(resources.getColor(R.color.app_primary_blue, null))
                    actionButton.setOnClickListener { handleRsvp(event) }
                    actionButton.visibility = View.VISIBLE
                }
            })
    }

    // NEW: Accept invitation
    private fun acceptInvitation(event: Event, userId: String) {
        // Get user data first
        database.reference.child("users").child(userId).get().addOnSuccessListener { snapshot ->
            val fullName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
            val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java) ?: ""

            val updates = hashMapOf<String, Any>(
                // Update invitation status
                "events/${event.id}/invitations/$userId/status" to "accepted",
                "events/${event.id}/invitations/$userId/respondedAt" to ServerValue.TIMESTAMP,

                // Add to attendees
                "events/${event.id}/attendees/$userId/fullName" to fullName,
                "events/${event.id}/attendees/$userId/profileImageUrl" to profileImageUrl,
                "events/${event.id}/attendeesCount" to event.attendeesCount + 1,

                // Update user's invitations
                "users/$userId/invitations/${event.id}/status" to "accepted",
                "users/$userId/invitations/${event.id}/respondedAt" to ServerValue.TIMESTAMP
            )

            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Invitation accepted! You're now attending \"${event.title}\"", Toast.LENGTH_SHORT).show()

                    // Notify organizer using existing notification structure
                    event.organizer?.uid?.let { organizerId ->
                        createInvitationNotification(
                            organizerId,
                            "invitation_accepted",
                            "$fullName accepted your invitation to ${event.title}.",
                            event.id,
                            event.title
                        )
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to accept invitation", Toast.LENGTH_SHORT).show()
                }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get user data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createInvitationNotification(userId: String, type: String, text: String, eventId: String? = null, eventTitle: String? = null) {
        val notification = mutableMapOf<String, Any>(
            "type" to type,
            "text" to text,
            "timestamp" to ServerValue.TIMESTAMP,
            "read" to false
        )

        // Add additional fields for invitation-related notifications
        eventId?.let { notification["eventId"] = it }
        eventTitle?.let { notification["eventTitle"] = it }

        database.reference.child("notifications").child(userId).push().setValue(notification)
    }

    // NEW: Decline invitation
    private fun declineInvitation(event: Event, userId: String) {
        database.reference.child("users").child(userId).get().addOnSuccessListener { snapshot ->
            val fullName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"

            val updates = hashMapOf<String, Any>(
                // Update invitation status
                "events/${event.id}/invitations/$userId/status" to "declined",
                "events/${event.id}/invitations/$userId/respondedAt" to ServerValue.TIMESTAMP,

                // Update user's invitations
                "users/$userId/invitations/${event.id}/status" to "declined",
                "users/$userId/invitations/${event.id}/respondedAt" to ServerValue.TIMESTAMP
            )

            database.reference.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Invitation declined", Toast.LENGTH_SHORT).show()

                    // Notify organizer using existing notification structure
                    event.organizer?.uid?.let { organizerId ->
                        createInvitationNotification(
                            organizerId,
                            "invitation_declined",
                            "$fullName declined your invitation to ${event.title}.",
                            event.id,
                            event.title
                        )
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to decline invitation", Toast.LENGTH_SHORT).show()
                }
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
        setViewAndChildrenEnabled(binding.mainContent, enabled)
    }

    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }

    private fun getSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val dpWidth = displayMetrics.widthPixels / displayMetrics.density
        val availableWidth = dpWidth - 32
        return (availableWidth / 296).toInt().coerceAtLeast(1)
    }

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

    private fun handleRsvp(event: Event) {
        currentUserId?.let { uid ->
            // Check if this is an invitation response
            database.reference.child("events").child(event.id).child("invitations").child(uid)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            // This is responding to an invitation
                            acceptInvitation(event, uid)
                        } else {
                            // Regular RSVP
                            database.reference.child("users").child(uid).get().addOnSuccessListener { userSnapshot ->
                                val fullName = userSnapshot.child("fullName").getValue(String::class.java) ?: "Unknown User"
                                val profileImageUrl = userSnapshot.child("profileImageUrl").getValue(String::class.java) ?: ""

                                val updates = hashMapOf<String, Any>(
                                    "events/${event.id}/attendees/$uid/fullName" to fullName,
                                    "events/${event.id}/attendees/$uid/profileImageUrl" to profileImageUrl,
                                    "events/${event.id}/attendeesCount" to event.attendeesCount + 1
                                )

                                database.reference.updateChildren(updates)
                                    .addOnSuccessListener {
                                        Toast.makeText(this@MainActivity, "You have successfully RSVP'd to \"${event.title}\"!", Toast.LENGTH_SHORT).show()

                                        // Notify organizer using enhanced notification
                                        event.organizer?.uid?.let { organizerId ->
                                            createInvitationNotification(
                                                organizerId,
                                                "rsvp",
                                                "$fullName accepted your invite to ${event.title}.",
                                                event.id,
                                                event.title
                                            )
                                        }
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(this@MainActivity, "Failed to RSVP", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(this@MainActivity, "Error processing RSVP", Toast.LENGTH_SHORT).show()
                    }
                })
        }
    }

    private fun showEditEventDialog(event: Event) {
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
        startActivityForResult(intent, EDIT_EVENT_REQUEST)
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
        if (!event.imageUrl.isNullOrEmpty()) {
            val photoRef = FirebaseStorage.getInstance().getReferenceFromUrl(event.imageUrl)
            photoRef.delete().addOnSuccessListener {
                deleteEventFromDatabase(event.id)
            }.addOnFailureListener {
                Toast.makeText(this, "Failed to delete event image. Please try again.", Toast.LENGTH_SHORT).show()
            }
        } else {
            deleteEventFromDatabase(event.id)
        }
    }

    private fun deleteEventFromDatabase(eventId: String) {
        database.reference.child("events").child(eventId).removeValue()
            .addOnSuccessListener {
                Toast.makeText(this, "Event deleted successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to delete event data", Toast.LENGTH_SHORT).show()
            }
    }

    private fun cancelRsvp(event: Event) {
        currentUserId?.let { uid ->
            val updates = hashMapOf<String, Any?>(
                "events/${event.id}/attendees/$uid" to null,
                "events/${event.id}/attendeesCount" to (event.attendeesCount - 1).coerceAtLeast(0)
            )
            database.reference.updateChildren(updates)
                .addOnSuccessListener { Toast.makeText(this, "RSVP cancelled", Toast.LENGTH_SHORT).show() }
                .addOnFailureListener { Toast.makeText(this, "Failed to cancel RSVP", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showDatePicker(onDateSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance()
        val dialog = android.app.DatePickerDialog(this, { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            onDateSelected(calendar.time)
        },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.show()
    }

    private fun showNotificationsBottomSheet() {
        val bottomSheet = NotificationsBottomSheet(this, notifications) {
            markAllNotificationsAsRead()
        }
        bottomSheet.show()
    }

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

    private fun createNotification(userId: String, type: String, text: String) {
        createInvitationNotification(userId, type, text)
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                // Clean up listeners before logout
                cleanupListeners()
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Clean up all listeners to prevent memory leaks
    private fun cleanupListeners() {
        Log.d(TAG, "Cleaning up Firebase listeners")

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

        notificationsListener?.let { listener ->
            notificationsQuery?.removeEventListener(listener)
            notificationsListener = null
            notificationsQuery = null
        }
    }

    // Proper lifecycle management
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called - cleaning up listeners")
        cleanupListeners()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                CREATE_EVENT_REQUEST -> {
                    Toast.makeText(this, "Event created successfully!", Toast.LENGTH_SHORT).show()
                    binding.tabLayout.getTabAt(1)?.select()
                }
                EDIT_EVENT_REQUEST -> Toast.makeText(this, "Event updated successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}