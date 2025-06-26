package com.student.events

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*

class EventDetailsDialog(
    context: Context,
    private val event: Event
) : Dialog(context, R.style.FullScreenDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_event_details)

        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        setupViews()
    }

    private fun setupViews() {
        findViewById<TextView>(R.id.eventTitle).text = event.title
        findViewById<ImageView>(R.id.closeButton).setOnClickListener { dismiss() }

        // **Fix**: Load imageUrl
        val imageView = findViewById<ImageView>(R.id.eventImage)
        if (!event.imageUrl.isNullOrEmpty()) {
            imageView.visibility = View.VISIBLE
            Glide.with(context)
                .load(event.imageUrl)
                .placeholder(R.drawable.image_placeholder)
                .error(R.drawable.image_placeholder)
                .centerCrop()
                .into(imageView)
        } else {
            imageView.visibility = View.GONE
        }

        // **Fix**: Format dateTime object
        findViewById<TextView>(R.id.dateTimeText).text = formatDateTime(event)
        findViewById<TextView>(R.id.locationText).text = event.location
        findViewById<TextView>(R.id.descriptionText).text = event.description
        // **Fix**: Use attendeesCount
        findViewById<TextView>(R.id.attendeesText).text = "${event.attendeesCount} people attending"
        // **Fix**: Use organizer.fullName
        findViewById<TextView>(R.id.organizerText).text = "Organized by ${event.organizer?.fullName ?: "Unknown"}"
    }

    // **Fix**: Updated formatDateTime to take an Event object
    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }
}
