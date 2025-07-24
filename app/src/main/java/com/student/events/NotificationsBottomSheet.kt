package com.student.events

import android.content.Context
import android.content.Intent
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

class NotificationsBottomSheet(
    private val context: Context,
    private val notifications: List<Notification>,
    private val onMarkAllAsRead: () -> Unit,
    // --- FIX STARTS HERE ---
    // The callback now includes a Boolean to indicate if it's from an invitation.
    private val onShowEventDetails: (Event, Boolean) -> Unit,
    // --- FIX ENDS HERE ---
    private val onNavigateToProfile: (String, String) -> Unit
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var adapter: NotificationAdapter
    private val database = FirebaseDatabase.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

    private inner class NotificationAdapter(
        private val notifications: MutableList<Notification>,
        private val onNotificationClick: (Notification) -> Unit
    ) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

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

            holder.notificationText.text = notification.text
            holder.timestampText.text = formatTimestamp(notification.timestamp)
            holder.unreadIndicator.visibility = if (notification.read) View.GONE else View.VISIBLE

            handleEmailIndicator(holder, notification)

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

            holder.actionButtonsLayout.visibility = View.GONE

            if (notification.read) {
                holder.itemView.alpha = 0.7f
                holder.notificationText.setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            } else {
                holder.itemView.alpha = 1.0f
                holder.notificationText.setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
            }

            val isClickable = notification.type in listOf("invitation", "invitation_accepted", "invitation_declined", "rsvp", "contact")
            if (isClickable) {
                holder.itemView.background = ContextCompat.getDrawable(context, R.drawable.tertiary_action_bg)
                holder.itemView.isClickable = true
                holder.itemView.isFocusable = true
            } else {
                holder.itemView.background = null
                holder.itemView.isClickable = false
                holder.itemView.isFocusable = false
            }

            holder.itemView.setOnClickListener {
                if (isClickable) {
                    onNotificationClick(notification)
                }
            }
        }

        private fun handleEmailIndicator(holder: NotificationViewHolder, notification: Notification) {
            holder.emailIndicator?.let { indicator ->
                val hasEmail = checkNotificationHasEmail(notification)

                if (hasEmail && (notification.type == "contact" || notification.type == "invitation")) {
                    indicator.visibility = View.VISIBLE
                    indicator.text = "📧 Email sent"
                    indicator.setTextColor(ContextCompat.getColor(context, R.color.app_success))
                    indicator.setBackgroundResource(R.drawable.tertiary_action_bg)
                } else if (notification.type == "contact" || notification.type == "invitation") {
                    indicator.visibility = View.VISIBLE
                    indicator.text = "📱 App only"
                    indicator.setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                    indicator.setBackgroundResource(R.drawable.filter_button_bg)
                } else {
                    indicator.visibility = View.GONE
                }
            }
        }

        private fun checkNotificationHasEmail(notification: Notification): Boolean {
            val text = notification.text.lowercase()
            return text.contains("check your email") ||
                    text.contains("email (") ||
                    text.contains("@") ||
                    text.contains("full details") ||
                    text.contains("email sent")
        }

        override fun getItemCount(): Int = notifications.size

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

    companion object {
        private const val TAG = "NotificationsBottomSheet"
    }

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_notifications, null)
        dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        setupViews(view)
        dialog.show()
    }

    private fun setupViews(view: View) {
        val titleText = view.findViewById<TextView>(R.id.notificationsTitle)
        val markAllReadButton = view.findViewById<TextView>(R.id.markAllReadButton)
        val recyclerView = view.findViewById<RecyclerView>(R.id.notificationsRecyclerView)
        val emptyStateLayout = view.findViewById<LinearLayout>(R.id.emptyStateLayout)

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

        view.findViewById<ImageView>(R.id.closeButton)?.setOnClickListener {
            dialog.dismiss()
        }

        if (notifications.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateLayout.visibility = View.GONE

            adapter = NotificationAdapter(notifications.toMutableList()) { notification ->
                handleNotificationClick(notification)
            }
            recyclerView.layoutManager = LinearLayoutManager(context)
            recyclerView.adapter = adapter
        }
    }

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

    private fun handleEventNotification(notification: Notification) {
        val eventId = notification.eventId
        if (eventId.isNullOrEmpty()) {
            Toast.makeText(context, "Event information not available", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            return
        }

        showToast("Loading event details...")

        database.reference.child("events").child(eventId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        try {
                            val event = parseEventFromSnapshot(snapshot, eventId)
                            if (event != null) {
                                dialog.dismiss()
                                // --- FIX STARTS HERE ---
                                // We check if the notification type is 'invitation' and pass the result.
                                val isInvitation = notification.type == "invitation"
                                onShowEventDetails(event, isInvitation)
                                // --- FIX ENDS HERE ---
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
                    Toast.makeText(context, "Failed to load event details", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            })
    }

    private fun handleContactNotification(notification: Notification) {
        val senderName = extractSenderNameFromContactNotification(notification.text)

        if (senderName != null) {
            findUserByName(senderName) { userId ->
                if (userId != null) {
                    dialog.dismiss()
                    onNavigateToProfile(userId, senderName)
                } else {
                    Toast.makeText(context, "User not found", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        } else {
            Toast.makeText(context, "Sender information not available", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    private fun extractSenderNameFromContactNotification(text: String): String? {
        return try {
            val parts = text.split(" sent you a message")
            if (parts.isNotEmpty()) {
                parts[0].trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }

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

    private fun handleEventNotFound(notification: Notification) {
        Toast.makeText(context, "This event is no longer available", Toast.LENGTH_SHORT).show()
        deleteNotification(notification)
        dialog.dismiss()
    }

    private fun deleteNotification(notification: Notification) {
        currentUserId?.let { uid ->
            database.reference
                .child("notifications")
                .child(uid)
                .child(notification.id)
                .removeValue()
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
                com.student.events.models.DateTime(
                    seconds = seconds,
                    nanoseconds = nanoseconds
                )
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
            null
        }
    }

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

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}