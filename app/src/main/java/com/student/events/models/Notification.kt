package com.student.events.models

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a notification in the events application.
 * Supports various notification types including invitations, RSVP confirmations, and messages.
 * Compatible with Firebase Realtime Database structure for real-time updates.
 */
data class Notification(
    var id: String = "",
    var type: String = "",
    var text: String = "",
    var timestamp: Long = 0,
    var read: Boolean = false,

    // Event-specific fields for invitation and event-related notifications
    val eventId: String? = null,
    val eventTitle: String? = null,
    val organizerName: String? = null
) {

    /**
     * Default constructor required for Firebase deserialization.
     * Firebase automatically maps database fields to object properties.
     */
    constructor() : this("", "", "", 0, false, null, null, null)

    companion object {
        // Notification type constants for consistent usage throughout the app
        const val TYPE_INVITATION = "invitation"
        const val TYPE_INVITATION_ACCEPTED = "invitation_accepted"
        const val TYPE_INVITATION_DECLINED = "invitation_declined"
        const val TYPE_RSVP = "rsvp"
        const val TYPE_CONTACT = "contact"
        const val TYPE_EVENT_UPDATE = "event_update"
        const val TYPE_EVENT_CANCELLED = "event_cancelled"
        const val TYPE_REMINDER = "reminder"
    }

    /**
     * Check if this notification is an event invitation
     */
    fun isInvitation(): Boolean {
        return type == TYPE_INVITATION && !eventId.isNullOrEmpty()
    }

    /**
     * Check if this notification is a response to an invitation
     */
    fun isInvitationResponse(): Boolean {
        return type == TYPE_INVITATION_ACCEPTED || type == TYPE_INVITATION_DECLINED
    }

    /**
     * Check if this notification contains event-related information
     */
    fun hasEventInfo(): Boolean {
        return !eventId.isNullOrEmpty()
    }

    /**
     * Check if this notification requires user action (like RSVP)
     */
    fun requiresAction(): Boolean {
        return type == TYPE_INVITATION
    }

    /**
     * Get a user-friendly description of the notification type
     */
    fun getTypeDescription(): String {
        return when (type) {
            TYPE_INVITATION -> "Event Invitation"
            TYPE_INVITATION_ACCEPTED -> "Invitation Accepted"
            TYPE_INVITATION_DECLINED -> "Invitation Declined"
            TYPE_RSVP -> "RSVP Confirmation"
            TYPE_CONTACT -> "Message"
            TYPE_EVENT_UPDATE -> "Event Update"
            TYPE_EVENT_CANCELLED -> "Event Cancelled"
            TYPE_REMINDER -> "Event Reminder"
            else -> "Notification"
        }
    }

    /**
     * Get the notification timestamp as a Date object
     */
    fun getTimestampAsDate(): Date {
        return Date(timestamp)
    }

    /**
     * Format the notification timestamp for display
     * Returns relative time for recent notifications, absolute time for older ones
     */
    fun getFormattedTime(): String {
        val now = System.currentTimeMillis()
        val timeDiff = now - timestamp

        return when {
            timeDiff < 60_000 -> "Just now" // Less than 1 minute
            timeDiff < 3600_000 -> "${timeDiff / 60_000} minutes ago" // Less than 1 hour
            timeDiff < 86400_000 -> "${timeDiff / 3600_000} hours ago" // Less than 1 day
            timeDiff < 604800_000 -> "${timeDiff / 86400_000} days ago" // Less than 1 week
            else -> {
                // More than a week ago, show actual date
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    /**
     * Get an icon resource identifier based on notification type
     * Returns appropriate drawable resource for display in UI
     */
    fun getIconResource(): String {
        return when (type) {
            TYPE_INVITATION -> "ic_person_add"
            TYPE_INVITATION_ACCEPTED, TYPE_RSVP -> "ic_check_circle"
            TYPE_INVITATION_DECLINED -> "ic_cancel"
            TYPE_CONTACT -> "ic_message"
            TYPE_EVENT_UPDATE -> "ic_info"
            TYPE_EVENT_CANCELLED -> "ic_error"
            TYPE_REMINDER -> "ic_notifications"
            else -> "ic_notifications"
        }
    }

    /**
     * Get the priority level of this notification for sorting and display
     * Higher numbers indicate higher priority
     */
    fun getPriority(): Int {
        return when (type) {
            TYPE_INVITATION -> 5 // Highest priority - requires action
            TYPE_EVENT_CANCELLED -> 4 // High priority - important information
            TYPE_REMINDER -> 3 // Medium-high priority - time sensitive
            TYPE_INVITATION_ACCEPTED, TYPE_INVITATION_DECLINED -> 3 // Medium-high priority
            TYPE_RSVP -> 2 // Medium priority
            TYPE_EVENT_UPDATE -> 2 // Medium priority
            TYPE_CONTACT -> 1 // Lower priority
            else -> 0 // Default priority
        }
    }

    /**
     * Check if this notification is recent (within the last 24 hours)
     */
    fun isRecent(): Boolean {
        val twentyFourHoursAgo = System.currentTimeMillis() - 86400_000
        return timestamp > twentyFourHoursAgo
    }

    /**
     * Create a summary suitable for notification display or logging
     */
    fun getSummary(): String {
        val readStatus = if (read) "Read" else "Unread"
        val timeStr = getFormattedTime()
        val typeStr = getTypeDescription()

        return "$typeStr • $readStatus • $timeStr"
    }

    /**
     * Mark this notification as read
     * Note: This only updates the local object, database update should be handled separately
     */
    fun markAsRead() {
        read = true
    }

    /**
     * Check if this notification should be shown as urgent (unread and high priority)
     */
    fun isUrgent(): Boolean {
        return !read && getPriority() >= 4
    }
}