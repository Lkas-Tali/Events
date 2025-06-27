package com.student.events

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
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

    // --- FIX: Pagination variables restored ---
    private val displayedEvents = mutableListOf<Event>()
    private var currentDisplayedCount = 0
    private val EVENTS_PER_PAGE = 4
    private var isLoading = false
    private var hasMoreEvents = true
    // --- End of Fix ---

    private var currentUserId: String? = null
    private var currentTab = "discover"

    // Filters
    private var searchQuery = ""
    private var locationFilter = ""
    private var startDateFilter: Date? = null
    private var endDateFilter: Date? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentUserId = auth.currentUser?.uid

        if (currentUserId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupViews()
        loadUserData()
        loadEvents()
        loadNotifications()
    }

    private fun setupViews() {
        auth.currentUser?.let { user ->
            binding.userNameText.text = user.displayName ?: "User"
            binding.userEmailText.text = user.email
        }

        // --- FIX: Adapter now uses the displayedEvents list for pagination ---
        eventsAdapter = EventsAdapter(
            events = displayedEvents,
            currentUserId = currentUserId ?: "",
            onEventClick = { event -> showEventDetails(event) },
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

        // --- FIX: Scroll listener now correctly references the binding property ---
        binding.nestedScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            if (v.getChildAt(v.childCount - 1) != null) {
                if ((scrollY >= (v.getChildAt(v.childCount - 1).measuredHeight - v.measuredHeight)) &&
                    scrollY > oldScrollY) {
                    // Scrolled to bottom
                    loadMoreEvents()
                }
            }
        })
        // --- End of Fix ---

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
    }

    private fun getSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val dpWidth = displayMetrics.widthPixels / displayMetrics.density
        val availableWidth = dpWidth - 32
        return (availableWidth / 296).toInt().coerceAtLeast(1)
    }

    private fun loadUserData() {
        currentUserId?.let { uid ->
            database.reference.child("users").child(uid)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val fullName = snapshot.child("fullName").getValue(String::class.java)
                        binding.userNameText.text = fullName ?: auth.currentUser?.displayName ?: "User"
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    private fun loadEvents() {
        binding.loadingMoreLayout.visibility = View.VISIBLE
        database.reference.child("events")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
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
                }
                override fun onCancelled(error: DatabaseError) {
                    binding.loadingMoreLayout.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Failed to load events: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // --- FIX: Pagination logic functions are restored ---
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
    // --- End of Fix ---

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

    private fun loadNotifications() {
        currentUserId?.let { uid ->
            database.reference.child("notifications").child(uid)
                .orderByChild("timestamp")
                .limitToLast(20)
                .addValueEventListener(object : ValueEventListener {
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
                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    private fun updateNotificationBadge() {
        val unreadCount = notifications.count { !it.read }
        binding.notificationBadge.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
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

    private fun showEventDetails(event: Event) {
        val dialog = EventDetailsDialog(this, event)
        dialog.show()
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
        database.reference.child("events").child(event.id).removeValue()
            .addOnSuccessListener { Toast.makeText(this, "Event deleted successfully", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { Toast.makeText(this, "Failed to delete event", Toast.LENGTH_SHORT).show() }
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
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val CREATE_EVENT_REQUEST = 1001
        private const val EDIT_EVENT_REQUEST = 1002
    }
}
