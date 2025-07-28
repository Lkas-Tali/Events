package com.student.events.services

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resumeWithException

/**
 * Centralized authentication state manager that provides reactive authentication state
 * and prevents inconsistent auth states across the application.
 *
 * This singleton manages Firebase authentication, session persistence, and automatic
 * session recovery to ensure seamless user experience.
 */
class AuthStateManager private constructor(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val sessionPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Reactive authentication state observable by UI components
    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Current user information observable by UI components
    private val _currentUser = MutableStateFlow<UserInfo?>(null)
    val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    companion object {
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
        private const val KEY_DEVICE_TOKEN = "device_token"

        @Volatile
        private var INSTANCE: AuthStateManager? = null

        /**
         * Get singleton instance of AuthStateManager
         */
        fun getInstance(context: Context): AuthStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthStateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        initializeAuthState()
        setupAuthListener()
    }

    /**
     * Initialize authentication state based on Firebase and local session
     */
    private fun initializeAuthState() {
        scope.launch {
            val firebaseUser = auth.currentUser
            val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
            val isSessionValid = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)

            when {
                // Both Firebase and session are valid and matching
                firebaseUser != null && firebaseUser.uid == sessionUserId -> {
                    updateAuthState(AuthState.AUTHENTICATED)
                    loadUserInfo(firebaseUser.uid)
                }

                // Firebase user exists but session needs updating
                firebaseUser != null -> {
                    saveSession(firebaseUser.uid, firebaseUser.email)
                    updateAuthState(AuthState.AUTHENTICATED)
                    loadUserInfo(firebaseUser.uid)
                }

                // Session exists but Firebase user is missing - attempt recovery
                isSessionValid && !sessionUserId.isNullOrEmpty() -> {
                    updateAuthState(AuthState.RECOVERING)
                    attemptSessionRecovery(sessionUserId)
                }

                // No authentication found
                else -> {
                    updateAuthState(AuthState.UNAUTHENTICATED)
                }
            }
        }
    }

    /**
     * Setup Firebase authentication state listener for real-time auth changes
     */
    private fun setupAuthListener() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser

            scope.launch {
                when {
                    // User is authenticated
                    user != null -> {
                        updateAuthState(AuthState.AUTHENTICATED)
                        saveSession(user.uid, user.email)
                        loadUserInfo(user.uid)
                    }

                    // User was authenticated but Firebase user is now null
                    _authState.value == AuthState.AUTHENTICATED -> {
                        // Attempt session recovery before logging out
                        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
                        if (!sessionUserId.isNullOrEmpty()) {
                            updateAuthState(AuthState.RECOVERING)
                            attemptSessionRecovery(sessionUserId)
                        } else {
                            updateAuthState(AuthState.UNAUTHENTICATED)
                        }
                    }

                    // User is not authenticated
                    else -> {
                        updateAuthState(AuthState.UNAUTHENTICATED)
                    }
                }
            }
        }
    }

    /**
     * Attempt to recover user session when Firebase auth is missing but session exists
     */
    private suspend fun attemptSessionRecovery(userId: String) {
        try {
            // Verify user exists in database
            val snapshot = database.reference.child("users").child(userId).get().await()

            if (snapshot.exists()) {
                // User found in database - session is valid
                updateAuthState(AuthState.AUTHENTICATED)

                // Load user info from database
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: ""
                val email = snapshot.child("email").getValue(String::class.java) ?: ""
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                _currentUser.value = UserInfo(userId, email, fullName, profileImageUrl)

                // Attempt silent re-authentication if credentials are available
                attemptSilentReAuthentication(userId, email)
            } else {
                // User not found in database - invalid session
                clearSession()
                updateAuthState(AuthState.UNAUTHENTICATED)
            }
        } catch (e: Exception) {
            // Keep existing state on error to avoid unnecessary logouts
        }
    }

    /**
     * Attempt silent re-authentication for session recovery
     */
    private suspend fun attemptSilentReAuthentication(userId: String, email: String) {
        // In production, implement secure re-authentication methods:
        // - Refresh tokens
        // - Biometric authentication
        // - Custom authentication tokens

        // For now, ensure session stays valid
        refreshSession()
    }

    /**
     * Load user information from Firebase database
     */
    private suspend fun loadUserInfo(userId: String) {
        try {
            val snapshot = database.reference.child("users").child(userId).get().await()

            if (snapshot.exists()) {
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: ""
                val email = snapshot.child("email").getValue(String::class.java) ?: ""
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                _currentUser.value = UserInfo(userId, email, fullName, profileImageUrl)

                // Cache user info locally for offline access
                sessionPrefs.edit().apply {
                    putString(KEY_USER_NAME, fullName)
                    putString(KEY_USER_EMAIL, email)
                    apply()
                }
            }
        } catch (e: Exception) {
            // Use cached data if database access fails
            val cachedName = sessionPrefs.getString(KEY_USER_NAME, "")
            val cachedEmail = sessionPrefs.getString(KEY_USER_EMAIL, "")

            if (!cachedName.isNullOrEmpty()) {
                _currentUser.value = UserInfo(userId, cachedEmail ?: "", cachedName, null)
            }
        }
    }

    /**
     * Save user session data with device-specific validation
     */
    fun saveSession(userId: String, email: String?) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email ?: "")
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())

            // Generate device-specific token for additional security
            val deviceToken = generateDeviceToken(userId)
            putString(KEY_DEVICE_TOKEN, deviceToken)

            apply()
        }

        // Start background authentication service
        AuthenticationService.startService(context)
    }

    /**
     * Refresh session timestamp to maintain active state
     */
    fun refreshSession() {
        if (_authState.value == AuthState.AUTHENTICATED) {
            sessionPrefs.edit().apply {
                putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
                apply()
            }
        }
    }

    /**
     * Clear all session data and reset user state
     */
    fun clearSession() {
        sessionPrefs.edit().clear().apply()
        _currentUser.value = null
        AuthenticationService.stopService(context)
    }

    /**
     * Perform complete logout - clear session and Firebase auth
     */
    fun logout() {
        scope.launch {
            updateAuthState(AuthState.UNAUTHENTICATED)
            clearSession()

            try {
                auth.signOut()
            } catch (e: Exception) {
                // Continue with logout even if Firebase signout fails
            }
        }
    }

    /**
     * Update authentication state and notify observers
     */
    private fun updateAuthState(newState: AuthState) {
        _authState.value = newState
    }

    /**
     * Generate unique device token for session validation
     */
    private fun generateDeviceToken(userId: String): String {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        return "$userId-$deviceId-${System.currentTimeMillis()}".hashCode().toString()
    }

    /**
     * Validate current session integrity and freshness
     */
    fun validateSession(): Boolean {
        val isLoggedIn = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)
        val userId = sessionPrefs.getString(KEY_USER_ID, null)
        val lastLoginTime = sessionPrefs.getLong(KEY_LAST_LOGIN_TIME, 0L)
        val deviceToken = sessionPrefs.getString(KEY_DEVICE_TOKEN, null)

        val currentTime = System.currentTimeMillis()
        val timeSinceLogin = currentTime - lastLoginTime

        // Session is valid if all conditions are met:
        // - User is marked as logged in
        // - User ID exists
        // - Last login was within 30 days
        // - Device token exists (device-specific validation)
        return isLoggedIn &&
                !userId.isNullOrEmpty() &&
                timeSinceLogin < 30L * 24 * 60 * 60 * 1000 &&
                !deviceToken.isNullOrEmpty()
    }

    /**
     * Clean up resources when manager is no longer needed
     */
    fun onDestroy() {
        scope.cancel()
    }

    /**
     * User information data class
     */
    data class UserInfo(
        val uid: String,
        val email: String,
        val fullName: String,
        val profileImageUrl: String?
    )

    /**
     * Authentication state enumeration
     */
    enum class AuthState {
        UNKNOWN,          // Initial state while determining auth status
        AUTHENTICATED,    // User is authenticated and session is valid
        UNAUTHENTICATED, // User is not authenticated
        RECOVERING       // Attempting to recover existing session
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
    }