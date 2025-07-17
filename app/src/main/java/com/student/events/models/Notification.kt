package com.student.events.models

/**
 * Notification model that handles both regular notifications and invitation-specific notifications
 * Compatible with your existing Firebase notification structure
 */
data class Notification(
    var id: String = "",
    var type: String = "",
    var text: String = "",
    var timestamp: Long = 0,
    var read: Boolean = false,

    // NEW: Invitation-specific fields (optional, only present for invitation notifications)
    val eventId: String? = null,
    val eventTitle: String? = null,
    val organizerName: String? = null
) {

    /**
     * Constructor for Firebase deserialization
     * Firebase will automatically map fields from the database
     */
    constructor() : this("", "", "", 0, false, null, null, null)

    /**
     * Check if this is an invitation notification
     */
    fun isInvitation(): Boolean {
        return type == "invitation" && !eventId.isNullOrEmpty()
    }

    /**
     * Check if this is an invitation response notification
     */
    fun isInvitationResponse(): Boolean {
        return type == "invitation_accepted" || type == "invitation_declined"
    }

    /**
     * Check if this notification has event-related information
     */
    fun hasEventInfo(): Boolean {
        return !eventId.isNullOrEmpty()
    }

    /**
     * Get a user-friendly description of the notification type
     */
    fun getTypeDescription(): String {
        return when (type) {
            "invitation" -> "Event Invitation"
            "invitation_accepted" -> "Invitation Accepted"
            "invitation_declined" -> "Invitation Declined"
            "rsvp" -> "RSVP Confirmation"
            "contact" -> "Message"
            else -> "Notification"
        }
    }
}