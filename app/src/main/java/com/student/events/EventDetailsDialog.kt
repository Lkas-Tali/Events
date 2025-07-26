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

class EventDetailsDialog(
    context: Context,
    private val event: Event
    // FIX: The style is changed to the new BlurredDialog style
) : Dialog(context, R.style.BlurredDialog) {

    // Get the current user ID to check against the organizer
    private val currentUserId: String? = FirebaseAuth.getInstance().currentUser?.uid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The requestWindowFeature call is no longer needed with the new style
        setContentView(R.layout.dialog_event_details)

        // FIX: The window is now configured for the blur and pop-up effect
        window?.let {
            // Set the layout to wrap the content, making it a pop-up
            it.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // Make the dialog's window background fully transparent
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // Apply blur to the background window (the activity) on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setBackgroundBlurRadius(60)
            }

            // Dim the background to make the pop-up more prominent
            it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.attributes.dimAmount = 0.5f
        }

        setupViews()
    }

    private fun setupViews() {
        findViewById<TextView>(R.id.eventTitle).text = event.title
        findViewById<ImageView>(R.id.closeButton).setOnClickListener { dismiss() }

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

        findViewById<TextView>(R.id.dateTimeText).text = formatDateTime(event)
        findViewById<TextView>(R.id.locationText).text = event.location
        findViewById<TextView>(R.id.descriptionText).text = event.description
        findViewById<TextView>(R.id.attendeesText).text = "${event.attendeesCount} people attending"

        // --- FIX STARTS HERE ---
        // Add logic to check if the current user is the organizer.
        val organizerText = findViewById<TextView>(R.id.organizerText)
        val organizerName = event.organizer?.fullName ?: "Unknown"
        val organizerUid = event.organizer?.uid

        if (organizerUid != null && organizerUid == currentUserId) {
            // If the current user is the organizer, append "(You)" and change color.
            organizerText.text = "$organizerName (You)"
            organizerText.setTextColor(context.resources.getColor(R.color.app_text_secondary, null))
        } else {
            // Otherwise, just show the name with the default color.
            organizerText.text = organizerName
            organizerText.setTextColor(context.resources.getColor(R.color.app_text_primary, null))
        }
        // --- FIX ENDS HERE ---
    }

    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }
}
