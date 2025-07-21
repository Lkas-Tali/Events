package com.student.events.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.student.events.EventsApplication
import com.student.events.services.AuthStateManager
import com.student.events.services.AuthenticationService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot receiver triggered: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        Log.d(TAG, "Device boot completed - checking authentication state")

        try {
            val app = context.applicationContext as? EventsApplication
            if (app != null && app.isUserLoggedIn()) {
                Log.d(TAG, "User session found - starting authentication service")

                // Start authentication service to maintain session
                AuthenticationService.startService(context)

                // Initialize auth state manager
                AuthStateManager.getInstance(context)

                Log.d(TAG, "✅ Authentication restored after boot")
            } else {
                Log.d(TAG, "No valid user session found after boot")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling boot completed: ${e.message}")
        }
    }
}