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
import com.student.events.models.Notification
import java.text.SimpleDateFormat
import java.util.*

class NotificationsBottomSheet(
    private val context: Context,
    private val notifications: List<Notification>,
    private val onMarkAllAsRead: () -> Unit
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var adapter: NotificationAdapter
    private val database = FirebaseDatabase.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

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

        // Set title with count
        val unreadCount = notifications.count { !it.read }
        titleText.text = if (unreadCount > 0) {
            "Notifications ($unreadCount unread)"
        } else {
            "Notifications"
        }

        // Setup mark all as read button
        markAllReadButton.setOnClickListener {
            onMarkAllAsRead()
            dialog.dismiss()
        }

        // Show/hide mark all read button
        markAllReadButton.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE

        // Setup close button (find it properly)
        view.findViewById<ImageView>(R.id.closeButton)?.setOnClickListener {
            dialog.dismiss()
        }

        // Setup RecyclerView
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
        // Mark notification as read if it isn't already
        if (!notification.read) {
            markNotificationAsRead(notification)
        }

        // Simple click handling - just dismiss for now
        // You can add more sophisticated handling later
        dialog.dismiss()
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

    // Simplified adapter class
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

            // Optional - these might not be used in simplified version
            val acceptButton: Button? = view.findViewById(R.id.acceptButton)
            val declineButton: Button? = view.findViewById(R.id.declineButton)
            val viewEventButton: Button? = view.findViewById(R.id.viewEventButton)
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

            // Set unread indicator
            holder.unreadIndicator.visibility = if (notification.read) View.GONE else View.VISIBLE

            // Set different icons and colors based on notification type
            when (notification.type) {
                "invitation" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_person_add)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_primary_blue))
                    // For now, hide action buttons - we'll add invitation handling later
                    holder.actionButtonsLayout.visibility = View.GONE
                }
                "invitation_accepted" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_check_circle)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_success))
                    holder.actionButtonsLayout.visibility = View.GONE
                }
                "invitation_declined" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_error)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_error))
                    holder.actionButtonsLayout.visibility = View.GONE
                }
                "rsvp" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_event)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_success))
                    holder.actionButtonsLayout.visibility = View.GONE
                }
                "contact" -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_message)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_accent))
                    holder.actionButtonsLayout.visibility = View.GONE
                }
                else -> {
                    holder.notificationIcon.setImageResource(R.drawable.ic_notifications)
                    holder.notificationIcon.setColorFilter(ContextCompat.getColor(context, R.color.app_text_secondary))
                    holder.actionButtonsLayout.visibility = View.GONE
                }
            }

            // Set read/unread styling
            if (notification.read) {
                holder.itemView.alpha = 0.7f
                holder.notificationText.setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            } else {
                holder.itemView.alpha = 1.0f
                holder.notificationText.setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
            }

            // Set click listener
            holder.itemView.setOnClickListener {
                onNotificationClick(notification)
            }
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
}