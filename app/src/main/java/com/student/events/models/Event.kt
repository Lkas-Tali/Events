package com.student.events.models

import com.google.firebase.database.PropertyName
import java.io.Serializable

// Nested data class to match the 'organizer' object in Firebase
data class Organizer(
    val uid: String = "",
    val fullName: String = ""
) : Serializable

// Nested data class to match the 'dateTime' object in Firebase
data class DateTime(
    @get:PropertyName("_seconds") @set:PropertyName("_seconds") var seconds: Long = 0,
    @get:PropertyName("_nanoseconds") @set:PropertyName("_nanoseconds") var nanoseconds: Long = 0
) : Serializable

// Nested data class to represent an attendee within the 'attendees' map
data class Attendee(
    val fullName: String = "",
    val profileImageUrl: String = ""
): Serializable

// The main Event data class, updated to match the Firebase JSON structure
data class Event(
    var id: String = "",
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val organizer: Organizer? = null,
    val attendees: Map<String, Attendee> = mapOf(),
    val attendeesCount: Int = 0,
    val status: String = "upcoming",
    val dateTime: DateTime? = null,
    val imageUrl: String? = null
) : Serializable
