package com.student.events.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.student.events.util.NotificationUtils

/**
 * MyFirebaseMessagingService handles Firebase Cloud Messaging (FCM) for push notifications.
 * Manages FCM token updates and processes incoming notification messages.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Called when FCM registration token is updated.
     * This occurs when the app is restored on a new device, app is backed up and restored,
     * or when app data is cleared.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        // Save the new token to the database for this user
        saveTokenToDatabase(token)
    }

    /**
     * Called when a message is received from Firebase Cloud Messaging.
     * Processes the message and displays appropriate notifications to the user.
     *
     * @param remoteMessage Object representing the message received from FCM
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Process data payload if present
        if (remoteMessage.data.isNotEmpty()) {
            handleDataMessage(remoteMessage.data)
        }

        // Process notification payload if present
        remoteMessage.notification?.let { notification ->
            handleNotificationMessage(
                title = notification.title ?: "Events App",
                body = notification.body ?: "",
                eventId = remoteMessage.data["eventId"]
            )
        }
    }

    /**
     * Handle data-only messages from FCM
     * These messages contain custom data fields but no notification payload
     */
    private fun handleDataMessage(data: Map<String, String>) {
        val title = data["title"]
        val body = data["body"]
        val eventId = data["eventId"]

        if (!title.isNullOrBlank() && !body.isNullOrBlank()) {
            NotificationUtils.showNotification(this, title, body, eventId)
        }
    }

    /**
     * Handle notification messages from FCM
     * These messages contain a notification payload that may be automatically displayed
     */
    private fun handleNotificationMessage(title: String, body: String, eventId: String?) {
        // Create custom notification to ensure consistent handling across app states
        NotificationUtils.showNotification(this, title, body, eventId)
    }

    /**
     * Save the FCM token to the user's profile in Firebase Realtime Database.
     * This allows the server to send targeted notifications to this specific device.
     */
    private fun saveTokenToDatabase(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid

        if (userId != null) {
            FirebaseDatabase.getInstance().getReference("users")
                .child(userId)
                .child("fcmToken")
                .setValue(token)
                .addOnSuccessListener {
                    // Token successfully updated
                }
                .addOnFailureListener {
                    // Handle token update failure silently
                    // The token will be retried on next app launch
                }
        }
    }
}