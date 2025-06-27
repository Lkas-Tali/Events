package com.student.events.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.student.events.R
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*

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

    fun updateEvents(newEvents: List<Event>) {
        events = newEvents
        notifyDataSetChanged()
    }

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

        fun bind(event: Event) {
            titleText.text = event.title
            locationText.text = event.location
            attendeesText.text = "${event.attendeesCount} attending"

            event.dateTime?.seconds?.let {
                val date = Date(it * 1000) // Convert seconds to milliseconds
                val outputFormat = SimpleDateFormat("d MMMM", Locale.UK)
                dateText.text = outputFormat.format(date)
            } ?: run {
                dateText.text = "Date not set"
            }

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

            val isMyEvent = event.organizer?.uid == currentUserId
            val isAttending = event.attendees.containsKey(currentUserId)

            when {
                isMyEvent -> {
                    // My event: Details, Edit, Cancel
                    action1Button.apply {
                        text = "Details"
                        // FIX: Explicitly set background and text colors for secondary action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_secondary_bg))
                        setTextColor(itemView.context.getColor(R.color.app_primary_blue))
                        setOnClickListener { onEventClick(event) }
                        visibility = View.VISIBLE
                    }
                    action2Button.apply {
                        text = "Edit"
                        // FIX: Explicitly set background and text colors for primary action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_primary_blue))
                        setTextColor(itemView.context.getColor(android.R.color.white))
                        setOnClickListener { onEditClick(event) }
                        visibility = View.VISIBLE
                    }
                    action3Button.apply {
                        text = "Cancel"
                        // FIX: Explicitly set background and text colors for danger action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_danger_bg))
                        setTextColor(itemView.context.getColor(R.color.danger_text))
                        setOnClickListener { onCancelClick(event) }
                        visibility = View.VISIBLE
                    }
                }
                isAttending -> {
                    // Attending: Cancel RSVP, Details
                    action1Button.apply {
                        text = "Cancel RSVP"
                        // FIX: Explicitly set background and text colors for danger action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_danger_bg))
                        setTextColor(itemView.context.getColor(R.color.danger_text))
                        setOnClickListener { onCancelRsvpClick(event) }
                        visibility = View.VISIBLE
                    }
                    action2Button.apply {
                        text = "Details"
                        // FIX: Explicitly set background and text colors for tertiary action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_tertiary_bg))
                        setTextColor(itemView.context.getColor(R.color.tertiary_text))
                        setOnClickListener { onEventClick(event) }
                        visibility = View.VISIBLE
                    }
                    action3Button.visibility = View.GONE
                }
                else -> {
                    // Not attending: Details, RSVP
                    action1Button.apply {
                        text = "Details"
                        // FIX: Explicitly set background and text colors for secondary action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_secondary_bg))
                        setTextColor(itemView.context.getColor(R.color.app_primary_blue))
                        setOnClickListener { onEventClick(event) }
                        visibility = View.VISIBLE
                    }
                    action2Button.apply {
                        text = "RSVP"
                        // FIX: Explicitly set background and text colors for primary action
                        backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_primary_blue))
                        setTextColor(itemView.context.getColor(android.R.color.white))
                        setOnClickListener { onRsvpClick(event) }
                        visibility = View.VISIBLE
                    }
                    action3Button.visibility = View.GONE
                }
            }
        }
    }
}
