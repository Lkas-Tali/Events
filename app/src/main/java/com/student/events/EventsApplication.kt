package com.student.events

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.student.events.services.AuthenticationService
import kotlinx.coroutines.*

class EventsApplication : Application(), LifecycleObserver {

    private lateinit var sessionPrefs: SharedPreferences
    private val applicationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "EventsApplication"
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
        private const val KEY_APP_BACKGROUND_TIME = "app_background_time"

        // Keep session valid for 30 days
        private const val SESSION_VALIDITY_DAYS = 30L
        private const val SESSION_VALIDITY_MS = SESSION_VALIDITY_DAYS * 24 * 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: EventsApplication? = null

        fun getInstance(): EventsApplication {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: throw IllegalStateException("Application not initialized")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        Log.d(TAG, "Application starting...")

        // Initialize SharedPreferences
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize Firebase with enhanced configuration
        initializeFirebase()

        // Setup lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Start authentication service if user is logged in
        if (isUserLoggedIn()) {
            AuthenticationService.startService(this)
        }

        // Setup crash handler to preserve session
        setupCrashHandler()

        // Handle device-specific optimizations
        handleDeviceSpecificOptimizations()

        Log.d(TAG, "Application initialization complete")
    }

    private fun initializeFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d(TAG, "Firebase initialized")
            }

            // Configure Firebase Auth settings
            val auth = FirebaseAuth.getInstance()
            auth.setLanguageCode("en")

            // Add auth state listener for monitoring
            auth.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                Log.d(TAG, "Global auth state changed: ${user?.uid}")

                if (user != null) {
                    // Update session when auth state changes
                    updateSessionData(user.uid)

                    // Ensure authentication service is running
                    AuthenticationService.startService(this)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization error: ${e.message}")
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception: ${throwable.message}")

            // Preserve session data before crash
            preserveSessionOnCrash()

            // Call default handler
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handleDeviceSpecificOptimizations() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()

        Log.d(TAG, "Device: $manufacturer $model")

        // Samsung-specific optimizations
        if (manufacturer.contains("samsung")) {
            Log.d(TAG, "Applying Samsung device optimizations")

            // Samsung devices (especially S21 series) have aggressive memory management
            // Enable more frequent session updates
            applicationScope.launch {
                while (isActive) {
                    delay(5 * 60 * 1000L) // Every 5 minutes
                    if (isUserLoggedIn()) {
                        refreshSession()
                    }
                }
            }
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        Log.d(TAG, "App foregrounded")

        val backgroundTime = sessionPrefs.getLong(KEY_APP_BACKGROUND_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val timeInBackground = currentTime - backgroundTime

        Log.d(TAG, "Time in background: ${timeInBackground / 1000}s")

        // Check and restore session if needed
        if (isUserLoggedIn()) {
            checkAndRestoreSession()

            // Restart authentication service
            AuthenticationService.startService(this)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        Log.d(TAG, "App backgrounded")

        // Save background time
        sessionPrefs.edit().apply {
            putLong(KEY_APP_BACKGROUND_TIME, System.currentTimeMillis())
            apply()
        }

        // Ensure session is persisted
        if (isUserLoggedIn()) {
            refreshSession()
        }
    }

    fun isUserLoggedIn(): Boolean {
        val isLoggedIn = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)
        val userId = sessionPrefs.getString(KEY_USER_ID, null)
        val lastLoginTime = sessionPrefs.getLong(KEY_LAST_LOGIN_TIME, 0L)
        val currentTime = System.currentTimeMillis()

        // Check if session is still valid (within 30 days)
        val isSessionValid = isLoggedIn &&
                !userId.isNullOrEmpty() &&
                (currentTime - lastLoginTime) < SESSION_VALIDITY_MS

        Log.d(TAG, "Session check - Valid: $isSessionValid, UserId: $userId")

        return isSessionValid
    }

    fun getUserId(): String? {
        return if (isUserLoggedIn()) {
            sessionPrefs.getString(KEY_USER_ID, null)
        } else {
            null
        }
    }

    private fun checkAndRestoreSession() {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)

        Log.d(TAG, "Checking session - Firebase: ${currentUser?.uid}, Session: $sessionUserId")

        when {
            // Both valid - refresh session
            currentUser != null && sessionUserId == currentUser.uid -> {
                Log.d(TAG, "✅ Session valid and matches Firebase")
                refreshSession()
            }

            // Firebase user exists but different from session - update session
            currentUser != null && sessionUserId != currentUser.uid -> {
                Log.d(TAG, "⚠️ Session mismatch - updating to Firebase user")
                updateSessionData(currentUser.uid)
            }

            // No Firebase user but valid session - keep session
            currentUser == null && !sessionUserId.isNullOrEmpty() -> {
                Log.w(TAG, "⚠️ Firebase user null but session exists - preserving session")
                // Don't clear session - Firebase might restore
            }

            // No valid auth
            else -> {
                Log.d(TAG, "❌ No valid authentication")
                clearSession()
            }
        }
    }

    private fun refreshSession() {
        val userId = sessionPrefs.getString(KEY_USER_ID, null)
        if (!userId.isNullOrEmpty()) {
            sessionPrefs.edit().apply {
                putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Session refreshed for user: $userId")
        }
    }

    fun updateSessionData(userId: String) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Session data updated for user: $userId")

        // Start authentication service
        AuthenticationService.startService(this)
    }

    fun clearSession() {
        sessionPrefs.edit().clear().apply()
        Log.d(TAG, "Session cleared")

        // Stop authentication service
        AuthenticationService.stopService(this)
    }

    private fun preserveSessionOnCrash() {
        try {
            // Force sync SharedPreferences to disk
            sessionPrefs.edit().commit()
            Log.d(TAG, "Session preserved before crash")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preserve session: ${e.message}")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        INSTANCE = null
    }
}