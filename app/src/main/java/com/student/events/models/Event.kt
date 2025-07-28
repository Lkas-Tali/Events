package com.student.events.models

import com.google.firebase.database.PropertyName
import java.io.Serializable
import java.util.*

/**
 * Represents an event organizer with basic identification information.
 * Used within Event objects to track who created and manages the event.
 */
data class Organizer(
    val uid: String = "",
    val fullName: String = ""
) : Serializable

/**
 * Represents date and time information for events using Firebase Timestamp format.
 * Stores time as seconds since epoch with nanosecond precision for accurate scheduling.
 */
data class DateTime(
    @get:PropertyName("_seconds") @set:PropertyName("_seconds") var seconds: Long = 0,
    @get:PropertyName("_nanoseconds") @set:PropertyName("_nanoseconds") var nanoseconds: Long = 0
) : Serializable {

    /**
     * Convert Firebase timestamp to Java Date object for easy manipulation
     */
    fun toDate(): Date {
        return Date(seconds * 1000)
    }

    /**
     * Check if this datetime represents a past event
     */
    fun isPast(): Boolean {
        val currentTime = System.currentTimeMillis() / 1000
        return seconds < currentTime
    }

    /**
     * Check if this datetime represents a future event
     */
    fun isFuture(): Boolean {
        return !isPast()
    }

    companion object {
        /**
         * Create DateTime from Java Date object
         */
        fun fromDate(date: Date): DateTime {
            val seconds = date.time / 1000
            return DateTime(seconds = seconds, nanoseconds = 0)
        }

        /**
         * Create DateTime representing current moment
         */
        fun now(): DateTime {
            return fromDate(Date())
        }
    }
}

/**
 * Represents an attendee of an event with their profile information.
 * Used to track who has RSVP'd to events and display participant lists.
 */
data class Attendee(
    val fullName: String = "",
    val profileImageUrl: String = ""
) : Serializable

/**
 * Main data class representing an event in the application.
 * Contains all event details including scheduling, location, attendees, and metadata.
 * Designed to map directly to Firebase Realtime Database structure.
 */
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
) : Serializable {

    /**
     * Check if the current user is the organizer of this event
     */
    fun isOrganizedBy(userId: String): Boolean {
        return organizer?.uid == userId
    }

    /**
     * Check if a user is attending this event
     */
    fun isUserAttending(userId: String): Boolean {
        return attendees.containsKey(userId)
    }

    /**
     * Get the event date as a Java Date object, or null if no date is set
     */
    fun getEventDate(): Date? {
        return dateTime?.toDate()
    }

    /**
     * Check if this is a past event based on the current time
     */
    fun isPastEvent(): Boolean {
        return dateTime?.isPast() ?: false
    }

    /**
     * Check if this is an upcoming event
     */
    fun isUpcoming(): Boolean {
        return dateTime?.isFuture() ?: true
    }

    /**
     * Get a list of attendee names for display purposes
     */
    fun getAttendeeNames(): List<String> {
        return attendees.values.map { it.fullName }
    }

    /**
     * Check if the event has a cover image
     */
    fun hasImage(): Boolean {
        return !imageUrl.isNullOrBlank()
    }

    /**
     * Get event status with proper capitalization for display
     */
    fun getFormattedStatus(): String {
        return status.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
    }

    /**
     * Check if the event is at capacity (if there's a maximum attendee limit)
     * Currently returns false as no limit is implemented, but structure is ready for future use
     */
    fun isAtCapacity(): Boolean {
        // Future enhancement: add maxAttendees field and implement capacity checking
        return false
    }

    /**
     * Get a summary string suitable for notifications or quick display
     */
    fun getEventSummary(): String {
        val date = getEventDate()
        val dateString = date?.let {
            java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
        } ?: "Date TBD"

        return "$title • $location • $dateString"
    }
}