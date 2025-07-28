package com.student.events

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.student.events.models.Event
import com.student.events.models.Notification
import java.text.SimpleDateFormat
import java.util.*

/**
 * Bottom sheet dialog for displaying and managing user notifications.
 * Supports different notification types including event invitations, RSVP confirmations,
 * and contact messages. Provides interactive functionality for viewing related events
 * and user profiles.
 */
class NotificationsBottomSheet(
    private val context: Context,
    private val notifications: List<Notification>,
    private val onMarkAllAsRead: () -> Unit,
    private val onShowEventDetails: (Event, Boolean) -> Unit,
    private val onNavigateToProfile: (String, String) -> Unit
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var adapter: NotificationAdapter
    private val database = FirebaseDatabase.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

    /**
     * RecyclerView adapter for displaying notifications with appropriate styling and interactions
     */
    private inner class NotificationAdapter(
        private val notifications: MutableList<Notification>,
        private val onNotificationClick: (Notification) -> Unit
    ) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

        /**
         * ViewHolder for notification list items
         */
        inner class NotificationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val notificationIcon: ImageView = view.findViewById(R.id.notificationIcon)
            val notificationText: TextView = view.findViewById(R.id.notificationText)
            val timestampText: TextView = view.findViewById(R.id.timestampText)
            val unreadIndicator: View = view.findViewById(R.id.unreadIndicator)
            val actionButtonsLayout: LinearLayout = view.findViewById(R.id.actionButtonsLayout)
            val emailIndicator: TextView? = view.findViewById(R.id.emailIndicator)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            return NotificationViewHolder(view)
        }

        override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
            val notification = notifications[position]

            populateNotificationContent(holder, notification)
            configureNotificationIcon(holder, notification)
            configureNotificationInteraction(holder, notification)
        }

        /**
         * Populate notification content and visual indicators
         */
        private fun populateNotificationContent(holder: NotificationViewHolder, notification: Notification) {
            holder.notificationText.text = notification.text
            holder.timestampText.text = formatTimestamp(notification.timestamp)
            holder.unreadIndicator.visibility = if (notification.read) View.GONE else View.VISIBLE

            configureEmailIndicator(holder, notification)
            configureReadState(holder, notification)
        }

        /**
         * Configure notification icon based on type
         */
        private fun configureNotificationIcon(holder: NotificationViewHolder, notification: Notification) {
            when (notification.type) {
                "invitation" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_person_add)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_primary_blue))
                }
                "invitation_accepted" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_check_circle)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_success))
                }
                "invitation_declined" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_error)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_error))
                }
                "rsvp" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_event)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_success))
                }
                "contact" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_message)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_accent))
                }
                else -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_notifications)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_text_secondary))
                }
            }
        }

        /**
         * Configure notification interaction based on type
         */
        private fun configureNotificationInteraction(holder: NotificationViewHolder, notification: Notification) {
            holder.actionButtonsLayout.visibility = View.GONE

            val isClickable = notification.type in listOf("invitation", "invitation_accepted", "invitation_declined", "rsvp", "contact")

            if (isClickable) {
                holder.itemView.background = ContextCompat.getDrawable(context, R.drawable.tertiary_action_bg)
                holder.itemView.isClickable = true
                holder.itemView.isFocusable = true
                holder.itemView.setOnClickListener { onNotificationClick(notification) }
            } else {
                holder.itemView.background = null
                holder.itemView.isClickable = false
                holder.itemView.isFocusable = false
                holder.itemView.setOnClickListener(null)
            }
        }

        /**
         * Configure email delivery indicator for relevant notification types
         */
        private fun configureEmailIndicator(holder: NotificationViewHolder, notification: Notification) {
            holder.emailIndicator?.let { indicator ->
                val hasEmail = detectEmailDelivery(notification)

                when {
                    hasEmail && (notification.type == "contact" || notification.type == "invitation") -> {
                        indicator.visibility = View.VISIBLE
                        indicator.text = "📧 Email sent"
                        indicator.setTextColor(ContextCompat.getColor(context, R.color.app_success))
                        indicator.setBackgroundResource(R.drawable.tertiary_action_bg)
                    }
                    notification.type == "contact" || notification.type == "invitation" -> {
                        indicator.visibility = View.VISIBLE
                        indicator.text = "📱 App only"
                        indicator.setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                        indicator.setBackgroundResource(R.drawable.filter_button_bg)
                    }
                    else -> {
                        indicator.visibility = View.GONE
                    }
                }
            }
        }

        /**
         * Configure visual state based on read/unread status
         */
        private fun configureReadState(holder: NotificationViewHolder, notification: Notification) {
            if (notification.read) {
                holder.itemView.alpha = 0.7f
                holder.notificationText.setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            } else {
                holder.itemView.alpha = 1.0f
                holder.notificationText.setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
            }
        }

        /**
         * Detect if notification indicates email delivery
         */
        private fun detectEmailDelivery(notification: Notification): Boolean {
            val text = notification.text.lowercase()
            return text.contains("check your email") ||
                    text.contains("email (") ||
                    text.contains("@") ||
                    text.contains("full details") ||
                    text.contains("email sent")
        }

        override fun getItemCount(): Int = notifications.size

        /**
         * Format timestamp for human-readable display
         */
        private fun formatTimestamp(timestamp: Long): String {
            return try {
                val now = System.currentTimeMillis()
                val diff = now - timestamp

                when {
                    diff < 60_000 -> "Just now"
                    diff < 3600_000 -> "${diff / 60_000}m ago"
                    diff < 86400_000 -> "${diff / 3600_000}h ago"
                    diff < 604800_000 -> "${diff / 86400_000}d ago"
                    else -> {
                        val date = Date(timestamp)
                        SimpleDateFormat("MMM dd", Locale.getDefault()).format(date)
                    }
                }
            } catch (e: Exception) {
                "Unknown"
            }
        }
    }

    /**
     * Display the notifications bottom sheet
     */
    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_notifications, null)
        dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        setupBottomSheetContent(view)
        dialog.show()
    }

    /**
     * Setup bottom sheet content and interactions
     */
    private fun setupBottomSheetContent(view: View) {
        configureHeader(view)
        configureNotificationsList(view)
    }

    /**
     * Configure bottom sheet header with title and actions
     */
    private fun configureHeader(view: View) {
        val titleText = view.findViewById<TextView>(R.id.notificationsTitle)
        val markAllReadButton = view.findViewById<TextView>(R.id.markAllReadButton)
        val closeButton = view.findViewById<ImageView>(R.id.closeButton)

        val unreadCount = notifications.count { !it.read }
        titleText.text = if (unreadCount > 0) {
            "Notifications - $unreadCount"
        } else {
            "Notifications"
        }

        markAllReadButton.setOnClickListener {
            onMarkAllAsRead()
            dialog.dismiss()
        }

        markAllReadButton.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE

        closeButton?.setOnClickListener {
            dialog.dismiss()
        }
    }

    /**
     * Configure notifications list or empty state
     */
    private fun configureNotificationsList(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.notificationsRecyclerView)
        val emptyStateLayout = view.findViewById<LinearLayout>(R.id.emptyStateLayout)

        if (notifications.isEmpty()) {
            showEmptyState(recyclerView, emptyStateLayout)
        } else {
            showNotificationsList(recyclerView, emptyStateLayout)
        }
    }

    /**
     * Show empty state when no notifications exist
     */
    private fun showEmptyState(recyclerView: RecyclerView, emptyStateLayout: LinearLayout) {
        recyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.VISIBLE
    }

    /**
     * Show notifications list with data
     */
    private fun showNotificationsList(recyclerView: RecyclerView, emptyStateLayout: LinearLayout) {
        recyclerView.visibility = View.VISIBLE
        emptyStateLayout.visibility = View.GONE

        adapter = NotificationAdapter(notifications.toMutableList()) { notification ->
            handleNotificationClick(notification)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
    }

    /**
     * Handle notification item click based on type
     */
    private fun handleNotificationClick(notification: Notification) {
        if (!notification.read) {
            markNotificationAsRead(notification)
        }

        when (notification.type) {
            "invitation", "invitation_accepted", "invitation_declined", "rsvp" -> {
                handleEventNotification(notification)
            }
            "contact" -> {
                handleContactNotification(notification)
            }
            else -> {
                dialog.dismiss()
            }
        }
    }

    /**
     * Handle event-related notifications by loading and displaying event details
     */
    private fun handleEventNotification(notification: Notification) {
        val eventId = notification.eventId
        if (eventId.isNullOrEmpty()) {
            showMessage("Event information not available")
            dialog.dismiss()
            return
        }

        showMessage("Loading event details...")

        database.reference.child("events").child(eventId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        try {
                            val event = parseEventFromSnapshot(snapshot, eventId)
                            if (event != null) {
                                dialog.dismiss()
                                val isInvitation = notification.type == "invitation"
                                onShowEventDetails(event, isInvitation)
                            } else {
                                handleEventNotFound(notification)
                            }
                        } catch (e: Exception) {
                            handleEventNotFound(notification)
                        }
                    } else {
                        handleEventNotFound(notification)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    showMessage("Failed to load event details")
                    dialog.dismiss()
                }
            })
    }

    /**
     * Handle contact notifications by finding sender and navigating to profile
     */
    private fun handleContactNotification(notification: Notification) {
        val senderName = extractSenderNameFromContactMessage(notification.text)

        if (senderName != null) {
            findUserByName(senderName) { userId ->
                if (userId != null) {
                    dialog.dismiss()
                    onNavigateToProfile(userId, senderName)
                } else {
                    showMessage("User not found")
                    dialog.dismiss()
                }
            }
        } else {
            showMessage("Sender information not available")
            dialog.dismiss()
        }
    }

    /**
     * Extract sender name from contact notification text
     */
    private fun extractSenderNameFromContactMessage(text: String): String? {
        return try {
            val parts = text.split(" sent you a message")
            if (parts.isNotEmpty()) {
                parts[0].trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find user ID by full name in database
     */
    private fun findUserByName(name: String, callback: (String?) -> Unit) {
        database.reference.child("users")
            .orderByChild("fullName")
            .equalTo(name)
            .limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val userId = snapshot.children.firstOrNull()?.key
                    callback(userId)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback(null)
                }
            })
    }

    /**
     * Handle case when event is no longer available
     */
    private fun handleEventNotFound(notification: Notification) {
        showMessage("This event is no longer available")
        deleteNotification(notification)
        dialog.dismiss()
    }

    /**
     * Delete notification from database
     */
    private fun deleteNotification(notification: Notification) {
        currentUserId?.let { uid ->
            database.reference
                .child("notifications")
                .child(uid)
                .child(notification.id)
                .removeValue()
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
            null
        }
    }

    /**
     * Parse datetime from Firebase snapshot with fallback support
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
                com.student.events.models.DateTime(
                    seconds = seconds,
                    nanoseconds = nanoseconds
                )
            } else {
                // Fallback to legacy date/time format
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
     * Mark notification as read in database
     */
    private fun markNotificationAsRead(notification: Notification) {
        currentUserId?.let { uid ->
            database.reference
                .child("notifications")
                .child(uid)
                .child(notification.id)
                .child("read")
                .setValue(true)
                .addOnSuccessListener {
                    notification.read = true
                    adapter.notifyDataSetChanged()
                }
        }
    }

    /**
     * Show toast message to user
     */
    private fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}