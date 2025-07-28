package com.student.events.adapters

import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.student.events.R
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying events in a grid layout.
 * Handles different event states (upcoming/past) and user interactions (RSVP, edit, etc.).
 * Supports efficient updates using DiffUtil for smooth animations.
 */
class EventsAdapter(
    private var events: List<Event>,
    private val currentUserId: String,
    private val onEventClick: (Event) -> Unit,
    private val onEditClick: (Event) -> Unit,
    private val onCancelClick: (Event) -> Unit,
    private val onRsvpClick: (Event) -> Unit,
    private val onCancelRsvpClick: (Event) -> Unit
) : RecyclerView.Adapter<EventsAdapter.EventViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_card, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(events[position])
    }

    override fun getItemCount() = events.size

    /**
     * Update the adapter with new event data using DiffUtil for efficient changes
     */
    fun updateEvents(newEvents: List<Event>) {
        val diffCallback = EventDiffCallback(this.events, newEvents)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.events = newEvents
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * ViewHolder class that manages individual event card views
     */
    inner class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.eventImage)
        private val imagePlaceholder: View = itemView.findViewById(R.id.eventImagePlaceholder)
        private val titleText: TextView = itemView.findViewById(R.id.eventTitle)
        private val dateText: TextView = itemView.findViewById(R.id.eventDate)
        private val locationText: TextView = itemView.findViewById(R.id.eventLocation)
        private val attendeesText: TextView = itemView.findViewById(R.id.eventAttendees)
        private val action1Button: Button = itemView.findViewById(R.id.action1Button)
        private val action2Button: Button = itemView.findViewById(R.id.action2Button)
        private val action3Button: Button = itemView.findViewById(R.id.action3Button)

        /**
         * Bind event data to the view and configure UI based on event state and user relationship
         */
        fun bind(event: Event) {
            // Set basic event information
            titleText.text = event.title
            locationText.text = event.location
            attendeesText.text = "${event.attendeesCount} attending"

            // Format and display event date
            event.dateTime?.seconds?.let {
                val date = Date(it * 1000)
                val outputFormat = SimpleDateFormat("d MMMM", Locale.UK)
                dateText.text = outputFormat.format(date)
            } ?: run {
                dateText.text = "Date not set"
            }

            // Handle event image display
            setupEventImage(event)

            // Determine event and user state
            val isMyEvent = event.organizer?.uid == currentUserId
            val isAttending = event.attendees.containsKey(currentUserId)
            val isPastEvent = (event.dateTime?.seconds ?: Long.MAX_VALUE) < (System.currentTimeMillis() / 1000)

            // Reset view state to ensure clean display
            resetViewState()

            // Configure view based on event status
            if (isPastEvent) {
                configurePastEventView(event)
            } else {
                configureUpcomingEventView(event, isMyEvent, isAttending)
            }
        }

        /**
         * Setup event image with fallback to placeholder
         */
        private fun setupEventImage(event: Event) {
            if (!event.imageUrl.isNullOrEmpty()) {
                imageView.visibility = View.VISIBLE
                imagePlaceholder.visibility = View.GONE

                Glide.with(itemView.context)
                    .load(event.imageUrl)
                    .placeholder(R.drawable.image_placeholder)
                    .error(R.drawable.image_placeholder)
                    .centerCrop()
                    .into(imageView)
            } else {
                imageView.visibility = View.GONE
                imagePlaceholder.visibility = View.VISIBLE
            }
        }

        /**
         * Reset all view properties to ensure consistent state across different event types
         */
        private fun resetViewState() {
            // Reset image styling
            imageView.colorFilter = null

            // Reset card appearance
            itemView.alpha = 1.0f
            (itemView as CardView).foreground = null

            // Reset click handlers
            itemView.setOnClickListener { onEventClick(events[bindingAdapterPosition]) }

            // Reset text colors to default
            titleText.setTextColor(itemView.context.getColor(R.color.app_text_primary))
            dateText.setTextColor(itemView.context.getColor(R.color.app_text_primary))
            locationText.setTextColor(itemView.context.getColor(R.color.app_text_secondary))
            attendeesText.setTextColor(itemView.context.getColor(R.color.app_text_secondary))

            // Reset button visibility
            action1Button.visibility = View.VISIBLE
            action2Button.visibility = View.VISIBLE
            action3Button.visibility = View.VISIBLE
        }

        /**
         * Configure view styling and interactions for past events
         */
        private fun configurePastEventView(event: Event) {
            // Apply visual styling to indicate past event
            val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
            imageView.colorFilter = ColorMatrixColorFilter(colorMatrix)

            // Add semi-transparent overlay to indicate past status
            (itemView as CardView).foreground = ColorDrawable(0x80FFFFFF.toInt())

            // Disable main click interaction for past events
            itemView.setOnClickListener(null)

            // Configure single action button for details
            action1Button.apply {
                text = "Details"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_tertiary_bg))
                setTextColor(itemView.context.getColor(R.color.tertiary_text))
                setOnClickListener { onEventClick(event) }
                visibility = View.VISIBLE
            }
            action2Button.visibility = View.GONE
            action3Button.visibility = View.GONE
        }

        /**
         * Configure view and actions for upcoming events based on user relationship
         */
        private fun configureUpcomingEventView(event: Event, isMyEvent: Boolean, isAttending: Boolean) {
            when {
                isMyEvent -> configureOrganizerButtons(event)
                isAttending -> configureAttendeeButtons(event)
                else -> configureGuestButtons(event)
            }
        }

        /**
         * Configure buttons for event organizer (current user)
         */
        private fun configureOrganizerButtons(event: Event) {
            action1Button.apply {
                text = "Details"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_secondary_bg))
                setTextColor(itemView.context.getColor(R.color.app_primary_blue))
                setOnClickListener { onEventClick(event) }
                visibility = View.VISIBLE
            }
            action2Button.apply {
                text = "Edit"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_primary_blue))
                setTextColor(itemView.context.getColor(android.R.color.white))
                setOnClickListener { onEditClick(event) }
                visibility = View.VISIBLE
            }
            action3Button.apply {
                text = "Cancel"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_danger_bg))
                setTextColor(itemView.context.getColor(R.color.danger_text))
                setOnClickListener { onCancelClick(event) }
                visibility = View.VISIBLE
            }
        }

        /**
         * Configure buttons for users already attending the event
         */
        private fun configureAttendeeButtons(event: Event) {
            action1Button.apply {
                text = "Cancel RSVP"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_danger_bg))
                setTextColor(itemView.context.getColor(R.color.danger_text))
                setOnClickListener { onCancelRsvpClick(event) }
                visibility = View.VISIBLE
            }
            action2Button.apply {
                text = "Details"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_tertiary_bg))
                setTextColor(itemView.context.getColor(R.color.tertiary_text))
                setOnClickListener { onEventClick(event) }
                visibility = View.VISIBLE
            }
            action3Button.visibility = View.GONE
        }

        /**
         * Configure buttons for users not yet attending the event
         */
        private fun configureGuestButtons(event: Event) {
            action1Button.apply {
                text = "Details"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_secondary_bg))
                setTextColor(itemView.context.getColor(R.color.app_primary_blue))
                setOnClickListener { onEventClick(event) }
                visibility = View.VISIBLE
            }
            action2Button.apply {
                text = "RSVP"
                backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_primary_blue))
                setTextColor(itemView.context.getColor(android.R.color.white))
                setOnClickListener { onRsvpClick(event) }
                visibility = View.VISIBLE
            }
            action3Button.visibility = View.GONE
        }
    }
}

/**
 * DiffUtil callback for efficient list updates with smooth animations
 */
class EventDiffCallback(
    private val oldList: List<Event>,
    private val newList: List<Event>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}