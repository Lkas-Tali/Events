package com.student.events

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.student.events.models.Notification
import java.util.concurrent.TimeUnit

class NotificationsBottomSheet(
    context: Context,
    private val notifications: List<Notification>,
    private val onMarkAllRead: () -> Unit
) : BottomSheetDialog(context) {

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_notifications, null)
        setContentView(view)

        setupViews(view)
    }

    private fun setupViews(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.notificationsRecyclerView)
        val emptyStateText = view.findViewById<TextView>(R.id.emptyStateText)
        val markAllReadButton = view.findViewById<TextView>(R.id.markAllReadButton)

        if (notifications.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
            markAllReadButton.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE

            // Check if there are unread notifications
            val hasUnread = notifications.any { !it.read }
            markAllReadButton.visibility = if (hasUnread) View.VISIBLE else View.GONE

            recyclerView.layoutManager = LinearLayoutManager(context)
            recyclerView.adapter = NotificationsAdapter(notifications)

            markAllReadButton.setOnClickListener {
                onMarkAllRead()
                dismiss()
            }
        }
    }

    inner class NotificationsAdapter(
        private val notifications: List<Notification>
    ) : RecyclerView.Adapter<NotificationsAdapter.NotificationViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            return NotificationViewHolder(view)
        }

        override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
            holder.bind(notifications[position])
        }

        override fun getItemCount() = notifications.size

        inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val iconContainer: View = itemView.findViewById(R.id.notificationIconContainer)
            private val icon: ImageView = itemView.findViewById(R.id.notificationIcon)
            private val text: TextView = itemView.findViewById(R.id.notificationText)
            private val timestamp: TextView = itemView.findViewById(R.id.notificationTimestamp)

            fun bind(notification: Notification) {
                // Set background color based on read status
                itemView.setBackgroundColor(
                    if (notification.read) {
                        ContextCompat.getColor(itemView.context, android.R.color.white)
                    } else {
                        ContextCompat.getColor(itemView.context, R.color.notification_unread_bg)
                    }
                )

                // Set icon and background based on type
                when (notification.type) {
                    "invite" -> {
                        iconContainer.setBackgroundResource(R.drawable.notification_invite_bg)
                        icon.setImageResource(R.drawable.ic_mail)
                        icon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.notification_invite_color))
                    }
                    "update" -> {
                        iconContainer.setBackgroundResource(R.drawable.notification_update_bg)
                        icon.setImageResource(R.drawable.ic_edit)
                        icon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.notification_update_color))
                    }
                    "rsvp" -> {
                        iconContainer.setBackgroundResource(R.drawable.notification_rsvp_bg)
                        icon.setImageResource(R.drawable.ic_check_circle)
                        icon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.notification_rsvp_color))
                    }
                }

                text.text = notification.text
                timestamp.text = getTimeAgo(notification.timestamp)
            }

            private fun getTimeAgo(timestamp: Long): String {
                val now = System.currentTimeMillis()
                val diff = now - timestamp

                return when {
                    diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                    diff < TimeUnit.HOURS.toMillis(1) -> {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                        "$minutes minute${if (minutes > 1) "s" else ""} ago"
                    }
                    diff < TimeUnit.DAYS.toMillis(1) -> {
                        val hours = TimeUnit.MILLISECONDS.toHours(diff)
                        "$hours hour${if (hours > 1) "s" else ""} ago"
                    }
                    diff < TimeUnit.DAYS.toMillis(7) -> {
                        val days = TimeUnit.MILLISECONDS.toDays(diff)
                        "$days day${if (days > 1) "s" else ""} ago"
                    }
                    else -> {
                        val weeks = TimeUnit.MILLISECONDS.toDays(diff) / 7
                        "$weeks week${if (weeks > 1) "s" else ""} ago"
                    }
                }
            }
        }
    }
}