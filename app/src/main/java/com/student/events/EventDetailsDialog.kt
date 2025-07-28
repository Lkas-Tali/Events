package com.student.events

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*

/**
 * Modal dialog for displaying detailed event information.
 * Features a blurred background effect on supported Android versions
 * and provides a focused view of event details including image,
 * date/time, location, description, and attendee information.
 */
class EventDetailsDialog(
    context: Context,
    private val event: Event
) : Dialog(context, R.style.BlurredDialog) {

    private val currentUserId: String? = FirebaseAuth.getInstance().currentUser?.uid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_event_details)

        configureDialogWindow()
        setupEventContent()
    }

    /**
     * Configure dialog window properties for modern material design appearance
     * with blur effects and proper sizing
     */
    private fun configureDialogWindow() {
        window?.let { dialogWindow ->
            // Configure dialog as centered popup with content-based sizing
            dialogWindow.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            // Remove default dialog background for custom styling
            dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // Apply background blur effect on Android 12+ for enhanced visual depth
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dialogWindow.setBackgroundBlurRadius(60)
            }

            // Add subtle background dimming to focus attention on dialog content
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow.attributes.dimAmount = 0.5f
        }
    }

    /**
     * Populate dialog with event information and configure UI elements
     */
    private fun setupEventContent() {
        // Set event title and close button functionality
        findViewById<TextView>(R.id.eventTitle).text = event.title
        findViewById<ImageView>(R.id.closeButton).setOnClickListener { dismiss() }

        configureEventImage()
        populateEventDetails()
        configureOrganizerDisplay()
    }

    /**
     * Load and display event image if available, hide image view if no image exists
     */
    private fun configureEventImage() {
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
    }

    /**
     * Fill in event details including date/time, location, description, and attendee count
     */
    private fun populateEventDetails() {
        findViewById<TextView>(R.id.dateTimeText).text = formatDateTime(event)
        findViewById<TextView>(R.id.locationText).text = event.location
        findViewById<TextView>(R.id.descriptionText).text = event.description
        findViewById<TextView>(R.id.attendeesText).text = "${event.attendeesCount} people attending"
    }

    /**
     * Configure organizer name display with special styling for current user
     */
    private fun configureOrganizerDisplay() {
        val organizerText = findViewById<TextView>(R.id.organizerText)
        val organizerName = event.organizer?.fullName ?: "Unknown"
        val organizerUid = event.organizer?.uid

        if (organizerUid != null && organizerUid == currentUserId) {
            // Current user is the organizer - add "(You)" indicator and use secondary text color
            organizerText.text = "$organizerName (You)"
            organizerText.setTextColor(context.resources.getColor(R.color.app_text_secondary, null))
        } else {
            // Different organizer - show name with standard text color
            organizerText.text = organizerName
            organizerText.setTextColor(context.resources.getColor(R.color.app_text_primary, null))
        }
    }

    /**
     * Format event date and time for user-friendly display
     * @param event The event containing date/time information
     * @return Formatted date/time string or fallback message
     */
    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }
}