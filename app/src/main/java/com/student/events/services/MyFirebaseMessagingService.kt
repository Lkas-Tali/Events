package com.student.events.services

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.student.events.util.NotificationUtils

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    /**
     * Called when a new FCM registration token is generated.
     * This is where you should save the token to your server/database.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")
        sendTokenToDatabase(token)
    }

    /**
     * Called when a message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Check if message contains a data payload.
        remoteMessage.data.isNotEmpty().let {
            Log.d(TAG, "Message data payload: " + remoteMessage.data)

            // Extract title and body from the data payload
            val title = remoteMessage.data["title"]
            val body = remoteMessage.data["body"]
            val eventId = remoteMessage.data["eventId"]

            if (!title.isNullOrBlank() && !body.isNullOrBlank()) {
                NotificationUtils.showNotification(this, title, body, eventId)
            }
        }
    }

    /**
     * Persist token to the real-time database.
     * This allows you to send notifications to this specific device.
     */
    private fun sendTokenToDatabase(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            FirebaseDatabase.getInstance().getReference("users")
                .child(userId)
                .child("fcmToken")
                .setValue(token)
                .addOnSuccessListener {
                    Log.d(TAG, "FCM token successfully updated for user: $userId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update FCM token for user: $userId", e)
                }
        }
    }
}
