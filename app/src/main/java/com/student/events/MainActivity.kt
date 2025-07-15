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
import android.widget.ImageView
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
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityMainBinding
import com.student.events.models.Event
import com.student.events.models.Notification
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
    private val EVENTS_PER_PAGE = 4
    private var isLoading = false
    private var hasMoreEvents = true

    private var currentUserId: String? = null
    private var currentTab = "discover"

    // Filters
    private var searchQuery = ""
    private var locationFilter = ""
    private var startDateFilter: Date? = null
    private var endDateFilter: Date? = null

    // REAL-TIME LISTENERS - Fixed type declarations
    private var userDataListener: ValueEventListener? = null
    private var userDataRef: DatabaseReference? = null
    private var eventsListener: ValueEventListener? = null
    private var eventsRef: DatabaseReference? = null
    private var notificationsListener: ValueEventListener? = null
    private var notificationsQuery: Query? = null // Changed from DatabaseReference to Query

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

        // ADD: Programmatically control the system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // This tells the system that the content behind the status bar is light, so icons should be dark
        insetsController.isAppearanceLightStatusBars = true
        // This tells the system that the content behind the navigation bar is light, so the handle should be dark
        insetsController.isAppearanceLightNavigationBars = true

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentUserId = auth.currentUser?.uid

        if (currentUserId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        applySystemBarInsets()
        setupViews()

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

    // NEW: Setup all real-time listeners
    private fun setupRealTimeListeners() {
        Log.d(TAG, "Setting up real-time listeners")
        setupUserDataListener()
        setupEventsListener()
        setupNotificationsListener()
    }

    // NEW: Real-time user data listener
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

                        // Update header UI - ONLY first name, NO email
                        binding.userNameText.text = firstName

                        // Load modern circular avatar with real-time updates
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
                    loadAvatarImage(null) // Load default avatar
                }
            }

            // Attach the listener
            userDataRef?.addValueEventListener(userDataListener!!)
        }
    }

    // NEW: Real-time events listener
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
                    Log.d(TAG, "Events data updated")
                    allEvents.clear()
                    myEvents.clear()
                    attendingEvents.clear()

                    for (eventSnapshot in snapshot.children) {
                        val event = eventSnapshot.getValue(Event::class.java)
                        event?.let {
                            it.id = eventSnapshot.key ?: ""
                            allEvents.add(it)
                            if (it.organizer?.uid == currentUserId) myEvents.add(it)
                            if (it.attendees.containsKey(currentUserId)) attendingEvents.add(it)
                        }
                    }

                    sortEventsByDate(allEvents)
                    sortEventsByDate(myEvents)
                    sortEventsByDate(attendingEvents)
                    resetAndLoadEvents()

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing events data: ${e.message}", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to load events: ${error.message}")
                binding.loadingMoreLayout.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Failed to load events: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Attach the listener
        eventsRef?.addValueEventListener(eventsListener!!)
    }

    // NEW: Real-time notifications listener - FIXED TYPE ISSUE
    private fun setupNotificationsListener() {
        currentUserId?.let { uid ->
            // Remove any existing listener first
            notificationsListener?.let { listener ->
                notificationsQuery?.removeEventListener(listener)
            }

            Log.d(TAG, "Setting up notifications listener for UID: $uid")
            // Create the query and store it separately
            notificationsQuery = database.reference.child("notifications").child(uid)
                .orderByChild("timestamp")
                .limitToLast(20)

            notificationsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d(TAG, "Notifications data updated")
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

            // Attach the listener to the query
            notificationsQuery?.addValueEventListener(notificationsListener!!)
        }
    }

    // NEW: Enhanced avatar loading function
    private fun loadAvatarImage(profileImageUrl: String?) {
        if (!profileImageUrl.isNullOrEmpty()) {
            Log.d(TAG, "Loading avatar image from URL: $profileImageUrl")
            Glide.with(this@MainActivity)
                .load(profileImageUrl)
                .placeholder(R.drawable.circular_avatar)
                .error(R.drawable.circular_avatar)
                .circleCrop()
                .skipMemoryCache(false) // Allow caching for performance
                .into(binding.userAvatarImage)
        } else {
            Log.d(TAG, "Loading default avatar")
            // Set circular background and default icon
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
        }

        binding.nestedScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            if (v.getChildAt(v.childCount - 1) != null) {
                if ((scrollY >= (v.getChildAt(v.childCount - 1).measuredHeight - v.measuredHeight)) &&
                    scrollY > oldScrollY) {
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
                resetAndLoadEvents()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.searchEditText.addTextChangedListener { text ->
            searchQuery = text.toString()
            resetAndLoadEvents()
        }

        binding.locationFilterInput.addTextChangedListener { text ->
            locationFilter = text.toString()
            resetAndLoadEvents()
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
                resetAndLoadEvents()
            }
        }

        binding.endDateInput.setOnClickListener {
            showDatePicker { date ->
                endDateFilter = date
                binding.endDateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(date))
                resetAndLoadEvents()
            }
        }

        binding.profileAvatarFrame.setOnClickListener {
            startActivity(ProfileActivity.newIntent(this))
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
        setViewAndChildrenEnabled(binding.mainContent, enabled)
    }

    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM HH:mm", Locale.UK)
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

    private fun resetAndLoadEvents() {
        displayedEvents.clear()
        currentDisplayedCount = 0
        hasMoreEvents = true
        eventsAdapter.notifyDataSetChanged()
        loadMoreEvents()
    }

    private fun loadMoreEvents() {
        if (isLoading || !hasMoreEvents) return
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

    private fun sortEventsByDate(events: MutableList<Event>) {
        events.sortBy { it.dateTime?.seconds ?: Long.MAX_VALUE }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateText.visibility = if (isEmpty && !isLoading) View.VISIBLE else View.GONE
        if(isEmpty) {
            binding.emptyStateText.text = when (currentTab) {
                "discover" -> "No events match your criteria."
                "myEvents" -> "You haven't created any events."
                "attending" -> "You are not attending any events."
                else -> "No events available."
            }
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

    private fun clearFilters() {
        searchQuery = ""
        locationFilter = ""
        startDateFilter = null
        endDateFilter = null
        binding.searchEditText.setText("")
        binding.locationFilterInput.setText("")
        binding.startDateInput.setText("")
        binding.endDateInput.setText("")
        resetAndLoadEvents()
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
                    event.organizer?.uid?.let { organizerId ->
                        createNotification(
                            organizerId,
                            "rsvp",
                            "${auth.currentUser?.displayName ?: "Someone"} accepted your invite to ${event.title}."
                        )
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to RSVP", Toast.LENGTH_SHORT).show()
                }
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
        val notification = mapOf("type" to type, "text" to text, "timestamp" to ServerValue.TIMESTAMP, "read" to false)
        database.reference.child("notifications").child(userId).push().setValue(notification)
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

    // NEW: Clean up all listeners to prevent memory leaks - FIXED
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
            notificationsQuery?.removeEventListener(listener) // Fixed: use query instead of ref
            notificationsListener = null
            notificationsQuery = null
        }
    }

    // NEW: Proper lifecycle management
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