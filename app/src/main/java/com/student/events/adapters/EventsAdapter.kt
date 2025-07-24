package com.student.events.adapters

import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.student.events.R
import com.student.events.models.Event
import java.text.SimpleDateFormat
import androidx.cardview.widget.CardView
import android.graphics.drawable.ColorDrawable
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
        val diffCallback = EventDiffCallback(this.events, newEvents)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.events = newEvents
        diffResult.dispatchUpdatesTo(this)
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
            val isPastEvent = (event.dateTime?.seconds ?: Long.MAX_VALUE) < (System.currentTimeMillis() / 1000)

            // --- COMPREHENSIVE VIEW STATE RESET ---
            // This is crucial for dual-adapter scenarios in ProfileActivity and PublicProfileActivity
            // Reset ALL view properties to ensure clean state for both past and upcoming events

            // Reset image filter
            imageView.colorFilter = null

            // Reset card transparency - CRITICAL FIX
            itemView.alpha = 1.0f

            // Reset click listeners
            itemView.setOnClickListener { onEventClick(event) }

            // Reset text colors to default
            titleText.setTextColor(itemView.context.getColor(R.color.app_text_primary))
            dateText.setTextColor(itemView.context.getColor(R.color.app_text_primary))
            locationText.setTextColor(itemView.context.getColor(R.color.app_text_secondary))
            attendeesText.setTextColor(itemView.context.getColor(R.color.app_text_secondary))

            // Reset button visibility
            action1Button.visibility = View.VISIBLE
            action2Button.visibility = View.VISIBLE
            action3Button.visibility = View.VISIBLE

            // --- END OF COMPREHENSIVE RESET ---

            if (isPastEvent) {
                // 1. Apply greyscale to image
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f)
                imageView.colorFilter = ColorMatrixColorFilter(colorMatrix)

                // 2. THE KEY FIX: Add semi-transparent overlay
                (itemView as CardView).foreground = ColorDrawable(0x80FFFFFF.toInt())

                // 3. Disable clicks
                itemView.setOnClickListener(null)

                // 4. Configure button
                action1Button.apply {
                    text = "Details"
                    backgroundTintList = ColorStateList.valueOf(itemView.context.getColor(R.color.app_tertiary_bg))
                    setTextColor(itemView.context.getColor(R.color.tertiary_text))
                    setOnClickListener { onEventClick(event) }
                    visibility = View.VISIBLE
                }
                action2Button.visibility = View.GONE
                action3Button.visibility = View.GONE
            } else {
                // --- UPCOMING EVENT STYLING ---
                // (Reset has already been applied, so we just configure buttons)

                // IMPORTANT: Remove overlay for upcoming events
                (itemView as CardView).foreground = null

                when {
                    isMyEvent -> {
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
                    isAttending -> {
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
                    else -> {
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
        }
    }
}

// --- DiffUtil Callback Class ---
class EventDiffCallback(
    private val oldList: List<Event>,
    private val newList: List<Event>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        // Items are the same if their IDs are the same
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        // Contents are the same if the event objects are equal
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}