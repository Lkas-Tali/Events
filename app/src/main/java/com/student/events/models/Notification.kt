package com.student.events.models

data class Notification(
    var id: String = "",
    val type: String = "", // invite, update, rsvp
    val text: String = "",
    val timestamp: Long = 0,
    val read: Boolean = false,
    val eventId: String? = null,
    val fromUserId: String? = null
)