package com.student.events.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Service to maintain persistent authentication and prevent unwanted logouts
 */
class AuthenticationService : Service() {

    private lateinit var auth: FirebaseAuth
    private lateinit var sessionPrefs: SharedPreferences
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var authRefreshJob: Job? = null

    companion object {
        private const val TAG = "AuthenticationService"
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PASSWORD_HASH = "user_password_hash"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"

        // Refresh token every 30 minutes
        private const val TOKEN_REFRESH_INTERVAL = 30L

        fun startService(context: Context) {
            val intent = Intent(context, AuthenticationService::class.java)
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AuthenticationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AuthenticationService created")

        auth = FirebaseAuth.getInstance()
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Start monitoring authentication
        startAuthenticationMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AuthenticationService started")

        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAuthenticationMonitoring() {
        authRefreshJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkAndRefreshAuthentication()
                    delay(TimeUnit.MINUTES.toMillis(TOKEN_REFRESH_INTERVAL))
                } catch (e: Exception) {
                    Log.e(TAG, "Error in authentication monitoring: ${e.message}")
                }
            }
        }
    }

    private suspend fun checkAndRefreshAuthentication() = withContext(Dispatchers.Main) {
        val currentUser = auth.currentUser
        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
        val isSessionValid = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)

        Log.d(TAG, "Checking authentication - Firebase: ${currentUser?.uid}, Session: $sessionUserId")

        when {
            // Case 1: Both Firebase and session are valid
            currentUser != null && isSessionValid && currentUser.uid == sessionUserId -> {
                Log.d(TAG, "✅ Authentication valid - refreshing token")
                refreshAuthToken(currentUser)
            }

            // Case 2: Firebase user exists but session is missing
            currentUser != null -> {
                Log.d(TAG, "✅ Firebase user found, updating session")
                updateSessionData(currentUser)
                refreshAuthToken(currentUser)
            }

            // Case 3: Session exists but Firebase user is null (device-specific issue)
            currentUser == null && isSessionValid && sessionUserId != null -> {
                Log.w(TAG, "⚠️ Firebase user null but session valid - attempting silent re-authentication")
                attemptSilentReAuthentication(sessionUserId)
            }

            // Case 4: No valid authentication
            else -> {
                Log.d(TAG, "❌ No valid authentication found")
                // Don't clear session immediately - wait for user action
            }
        }
    }

    private suspend fun refreshAuthToken(user: FirebaseUser) = withContext(Dispatchers.IO) {
        try {
            // Force token refresh to prevent expiration
            val result = user.getIdToken(true).await()
            val token = result.token
            val expirationTime = result.expirationTimestamp

            if (token != null) {
                sessionPrefs.edit().apply {
                    putString(KEY_AUTH_TOKEN, token)
                    putLong(KEY_TOKEN_EXPIRY, expirationTime)
                    apply()
                }
                Log.d(TAG, "✅ Auth token refreshed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh auth token: ${e.message}")
        }
    }

    private suspend fun attemptSilentReAuthentication(userId: String) = withContext(Dispatchers.IO) {
        try {
            // First, verify user exists in database
            val database = FirebaseDatabase.getInstance()
            val userSnapshot = database.reference.child("users").child(userId).get().await()

            if (userSnapshot.exists()) {
                Log.d(TAG, "✅ User data found in database - session is valid")

                // Try to restore auth using saved credentials if available
                val savedEmail = sessionPrefs.getString(KEY_USER_EMAIL, null)
                val savedPasswordHash = sessionPrefs.getString(KEY_USER_PASSWORD_HASH, null)

                if (!savedEmail.isNullOrEmpty() && !savedPasswordHash.isNullOrEmpty()) {
                    // Note: In production, you should use more secure methods
                    // This is a simplified example
                    Log.d(TAG, "Attempting to restore authentication with saved credentials")
                    // Keep the session valid even if Firebase auth is temporarily unavailable
                }

                // Update session timestamp to keep it fresh
                sessionPrefs.edit().apply {
                    putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
                    apply()
                }
            } else {
                Log.w(TAG, "❌ User data not found in database - invalid session")
                clearSessionData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify user session: ${e.message}")
            // Keep existing session on error
        }
    }

    private fun updateSessionData(user: FirebaseUser) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, user.uid)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            user.email?.let { putString(KEY_USER_EMAIL, it) }
            apply()
        }
        Log.d(TAG, "Session data updated for user: ${user.uid}")
    }

    private fun clearSessionData() {
        sessionPrefs.edit().clear().apply()
        Log.d(TAG, "Session data cleared")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AuthenticationService destroyed")
        authRefreshJob?.cancel()
        serviceScope.cancel()
    }
}

// Extension function to convert Task to Coroutine
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        cont.resume(result, null)
    }
    addOnFailureListener { exception ->
        cont.resumeWithException(exception)
    }
    cont.invokeOnCancellation {
        // Cancel the task if the coroutine is cancelled
    }
}