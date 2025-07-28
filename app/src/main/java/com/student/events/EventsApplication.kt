package com.student.events

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.student.events.services.AuthenticationService
import com.student.events.util.NotificationUtils
import kotlinx.coroutines.*

/**
 * Main Application class responsible for global app initialization and lifecycle management.
 * Handles Firebase setup, user session persistence, authentication state management,
 * and device-specific optimizations for better performance and reliability.
 */
class EventsApplication : Application(), LifecycleObserver {

    private lateinit var sessionPrefs: SharedPreferences
    private val applicationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
        private const val KEY_APP_BACKGROUND_TIME = "app_background_time"

        // Session validity period (30 days)
        private const val SESSION_VALIDITY_DAYS = 30L
        private const val SESSION_VALIDITY_MS = SESSION_VALIDITY_DAYS * 24 * 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: EventsApplication? = null

        /**
         * Get the singleton instance of the application
         * @return The application instance
         * @throws IllegalStateException if application is not initialized
         */
        fun getInstance(): EventsApplication {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: throw IllegalStateException("Application not initialized")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        initializeCore()
        setupLifecycleMonitoring()
        initializeServices()
        setupCrashProtection()
        applyDeviceOptimizations()
    }

    /**
     * Initialize core application components
     */
    private fun initializeCore() {
        // Setup notification channels for the app
        NotificationUtils.createNotificationChannel(this)

        // Initialize session management
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize Firebase services
        initializeFirebase()
    }

    /**
     * Setup application lifecycle monitoring
     */
    private fun setupLifecycleMonitoring() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Initialize background services if user is authenticated
     */
    private fun initializeServices() {
        if (isUserLoggedIn()) {
            AuthenticationService.startService(this)
        }
    }

    /**
     * Initialize Firebase services with proper configuration
     */
    private fun initializeFirebase() {
        try {
            // Initialize Firebase if not already done
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }

            // Configure Firebase Auth
            val auth = FirebaseAuth.getInstance()
            auth.setLanguageCode("en")

            // Setup global authentication state monitoring
            auth.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser

                if (user != null) {
                    updateSessionData(user.uid)
                    AuthenticationService.startService(this)
                }
            }

        } catch (e: Exception) {
            // Handle Firebase initialization errors gracefully
        }
    }

    /**
     * Setup crash protection to preserve user sessions
     */
    private fun setupCrashProtection() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Preserve session data before crash
            preserveSessionOnCrash()

            // Delegate to default handler
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Apply device-specific optimizations for better performance
     */
    private fun applyDeviceOptimizations() {
        val manufacturer = Build.MANUFACTURER.lowercase()

        // Apply Samsung-specific optimizations for better session persistence
        if (manufacturer.contains("samsung")) {
            // Samsung devices have aggressive memory management
            // Implement more frequent session refresh to prevent data loss
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

    /**
     * Called when app comes to foreground
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        val backgroundTime = sessionPrefs.getLong(KEY_APP_BACKGROUND_TIME, 0L)
        val currentTime = System.currentTimeMillis()

        // Restore session and services if user is logged in
        if (isUserLoggedIn()) {
            checkAndRestoreSession()
            AuthenticationService.startService(this)
        }
    }

    /**
     * Called when app goes to background
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        // Save background timestamp for session management
        sessionPrefs.edit().apply {
            putLong(KEY_APP_BACKGROUND_TIME, System.currentTimeMillis())
            apply()
        }

        // Ensure session data is persisted
        if (isUserLoggedIn()) {
            refreshSession()
        }
    }

    /**
     * Check if user has a valid login session
     * @return true if user is logged in with valid session, false otherwise
     */
    fun isUserLoggedIn(): Boolean {
        val isLoggedIn = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)
        val userId = sessionPrefs.getString(KEY_USER_ID, null)
        val lastLoginTime = sessionPrefs.getLong(KEY_LAST_LOGIN_TIME, 0L)
        val currentTime = System.currentTimeMillis()

        // Validate session within time limit
        val isSessionValid = isLoggedIn &&
                !userId.isNullOrEmpty() &&
                (currentTime - lastLoginTime) < SESSION_VALIDITY_MS

        return isSessionValid
    }

    /**
     * Get current user ID if logged in
     * @return User ID string or null if not logged in
     */
    fun getUserId(): String? {
        return if (isUserLoggedIn()) {
            sessionPrefs.getString(KEY_USER_ID, null)
        } else {
            null
        }
    }

    /**
     * Check and restore user session, handling Firebase auth state mismatches
     */
    private fun checkAndRestoreSession() {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)

        when {
            // Both Firebase and session are valid and match
            currentUser != null && sessionUserId == currentUser.uid -> {
                refreshSession()
            }

            // Firebase user exists but differs from session - update session
            currentUser != null && sessionUserId != currentUser.uid -> {
                updateSessionData(currentUser.uid)
            }

            // No Firebase user but valid session exists - preserve session
            // This handles cases where Firebase auth might be temporarily unavailable
            currentUser == null && !sessionUserId.isNullOrEmpty() -> {
                // Keep existing session - Firebase might restore on its own
            }

            // No valid authentication available
            else -> {
                clearSession()
            }
        }
    }

    /**
     * Refresh current session timestamp to extend validity
     */
    private fun refreshSession() {
        val userId = sessionPrefs.getString(KEY_USER_ID, null)
        if (!userId.isNullOrEmpty()) {
            sessionPrefs.edit().apply {
                putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
                apply()
            }
        }
    }

    /**
     * Update session data with new user information
     * @param userId The user ID to store in session
     */
    fun updateSessionData(userId: String) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }

        // Start authentication service for session maintenance
        AuthenticationService.startService(this)
    }

    /**
     * Clear all session data and stop authentication services
     */
    fun clearSession() {
        sessionPrefs.edit().clear().apply()
        AuthenticationService.stopService(this)
    }

    /**
     * Preserve session data to disk before app crash
     */
    private fun preserveSessionOnCrash() {
        try {
            // Force synchronous write to ensure data is saved
            sessionPrefs.edit().commit()
        } catch (e: Exception) {
            // Handle preservation errors silently
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        INSTANCE = null
    }
}