package com.student.events.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.student.events.EventsApplication
import com.student.events.services.AuthStateManager
import com.student.events.services.AuthenticationService

/**
 * BootReceiver handles device boot events to maintain user authentication sessions.
 *
 * This receiver ensures that authenticated users remain logged in across device reboots
 * by automatically restoring authentication services and session state when the device
 * finishes booting.
 *
 * Listens for:
 * - ACTION_BOOT_COMPLETED: Standard boot completion
 * - ACTION_LOCKED_BOOT_COMPLETED: Boot completion while device is locked
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * Called when the broadcast receiver receives an intent.
     * Handles boot completion events to restore authentication state.
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }
        }
    }

    /**
     * Handles device boot completion by restoring authentication services
     * for users who were previously logged in.
     */
    private fun handleBootCompleted(context: Context) {
        try {
            val app = context.applicationContext as? EventsApplication

            // Check if user has a valid session that should be restored
            if (app != null && app.isUserLoggedIn()) {
                // Restart authentication service to maintain session persistence
                AuthenticationService.startService(context)

                // Initialize authentication state manager for session validation
                AuthStateManager.getInstance(context)
            }
        } catch (e: Exception) {
            // Silently handle errors to prevent boot issues
            // Authentication will be handled normally when user opens the app
        }
    }
}