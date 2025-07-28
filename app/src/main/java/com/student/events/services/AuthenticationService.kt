package com.student.events.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Background service that maintains persistent authentication state and prevents unwanted logouts.
 * Handles automatic token refresh and session validation to ensure seamless user experience.
 */
class AuthenticationService : Service() {

    private lateinit var auth: FirebaseAuth
    private lateinit var sessionPrefs: SharedPreferences
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var authRefreshJob: Job? = null

    companion object {
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PASSWORD_HASH = "user_password_hash"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"

        // Refresh authentication token every 30 minutes to prevent expiration
        private const val TOKEN_REFRESH_INTERVAL = 30L

        /**
         * Start the authentication service to monitor session state
         */
        fun startService(context: Context) {
            val intent = Intent(context, AuthenticationService::class.java)
            context.startService(intent)
        }

        /**
         * Stop the authentication service
         */
        fun stopService(context: Context) {
            val intent = Intent(context, AuthenticationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        auth = FirebaseAuth.getInstance()
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        startAuthenticationMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY to ensure service restarts if killed by system
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start background monitoring of authentication state with periodic token refresh
     */
    private fun startAuthenticationMonitoring() {
        authRefreshJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkAndRefreshAuthentication()
                    delay(TimeUnit.MINUTES.toMillis(TOKEN_REFRESH_INTERVAL))
                } catch (e: Exception) {
                    // Continue monitoring even if individual check fails
                    delay(TimeUnit.MINUTES.toMillis(5)) // Retry in 5 minutes on error
                }
            }
        }
    }

    /**
     * Check current authentication state and refresh tokens as needed
     */
    private suspend fun checkAndRefreshAuthentication() = withContext(Dispatchers.Main) {
        val currentUser = auth.currentUser
        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
        val isSessionValid = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)

        when {
            // Both Firebase and session are valid and match
            currentUser != null && isSessionValid && currentUser.uid == sessionUserId -> {
                refreshAuthToken(currentUser)
            }

            // Firebase user exists but session needs updating
            currentUser != null -> {
                updateSessionData(currentUser)
                refreshAuthToken(currentUser)
            }

            // Session exists but Firebase user is missing - attempt recovery
            currentUser == null && isSessionValid && sessionUserId != null -> {
                attemptSilentReAuthentication(sessionUserId)
            }

            // No valid authentication - service will continue monitoring
            else -> {
                // Authentication monitoring continues in background
            }
        }
    }

    /**
     * Refresh Firebase authentication token to prevent expiration
     */
    private suspend fun refreshAuthToken(user: FirebaseUser) = withContext(Dispatchers.IO) {
        try {
            val result = user.getIdToken(true).await()
            val token = result.token
            val expirationTime = result.expirationTimestamp

            if (token != null) {
                // Store refreshed token securely
                sessionPrefs.edit().apply {
                    putString(KEY_AUTH_TOKEN, token)
                    putLong(KEY_TOKEN_EXPIRY, expirationTime)
                    apply()
                }
            }
        } catch (e: Exception) {
            // Token refresh failed - will retry on next cycle
        }
    }

    /**
     * Attempt to restore authentication session when Firebase user is missing
     */
    private suspend fun attemptSilentReAuthentication(userId: String) = withContext(Dispatchers.IO) {
        try {
            // Verify user exists in database before attempting restoration
            val database = FirebaseDatabase.getInstance()
            val userSnapshot = database.reference.child("users").child(userId).get().await()

            if (userSnapshot.exists()) {
                // User data found - session is valid, update timestamp
                sessionPrefs.edit().apply {
                    putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
                    apply()
                }

                // Attempt to restore authentication using saved credentials if available
                val savedEmail = sessionPrefs.getString(KEY_USER_EMAIL, null)
                if (!savedEmail.isNullOrEmpty()) {
                    // In production, implement secure credential restoration
                    // This maintains session validity even if Firebase auth is temporarily unavailable
                }
            } else {
                // User data not found - invalid session
                clearSessionData()
            }
        } catch (e: Exception) {
            // Keep existing session on database access errors
        }
    }

    /**
     * Update session data with current user information
     */
    private fun updateSessionData(user: FirebaseUser) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, user.uid)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            user.email?.let { putString(KEY_USER_EMAIL, it) }
            apply()
        }
    }

    /**
     * Clear all session data when authentication is invalid
     */
    private fun clearSessionData() {
        sessionPrefs.edit().clear().apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        authRefreshJob?.cancel()
        serviceScope.cancel()
    }
}

/**
 * Extension function to convert Firebase Task to Kotlin Coroutine
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            cont.resume(result, null)
        }
        addOnFailureListener { exception ->
            cont.resumeWithException(exception)
        }
        cont.invokeOnCancellation {
            // Task cancellation handling
        }
    }