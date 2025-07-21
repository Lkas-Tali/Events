package com.student.events.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resumeWithException

/**
 * Centralized authentication state manager to prevent inconsistent auth states
 */
class AuthStateManager private constructor(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val sessionPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<UserInfo?>(null)
    val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    companion object {
        private const val TAG = "AuthStateManager"
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
        private const val KEY_DEVICE_TOKEN = "device_token"

        @Volatile
        private var INSTANCE: AuthStateManager? = null

        fun getInstance(context: Context): AuthStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthStateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        // Initialize auth state
        initializeAuthState()

        // Setup Firebase auth listener
        setupAuthListener()
    }

    private fun initializeAuthState() {
        scope.launch {
            val firebaseUser = auth.currentUser
            val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
            val isSessionValid = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)

            Log.d(TAG, "Initializing auth state - Firebase: ${firebaseUser?.uid}, Session: $sessionUserId")

            when {
                firebaseUser != null && firebaseUser.uid == sessionUserId -> {
                    // Both valid and matching
                    updateAuthState(AuthState.AUTHENTICATED)
                    loadUserInfo(firebaseUser.uid)
                }

                firebaseUser != null -> {
                    // Firebase user exists, update session
                    saveSession(firebaseUser.uid, firebaseUser.email)
                    updateAuthState(AuthState.AUTHENTICATED)
                    loadUserInfo(firebaseUser.uid)
                }

                isSessionValid && !sessionUserId.isNullOrEmpty() -> {
                    // Session exists but no Firebase user - attempt recovery
                    updateAuthState(AuthState.RECOVERING)
                    attemptSessionRecovery(sessionUserId)
                }

                else -> {
                    // No authentication
                    updateAuthState(AuthState.UNAUTHENTICATED)
                }
            }
        }
    }

    private fun setupAuthListener() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser

            scope.launch {
                when {
                    user != null -> {
                        Log.d(TAG, "Auth state changed - User authenticated: ${user.uid}")
                        updateAuthState(AuthState.AUTHENTICATED)
                        saveSession(user.uid, user.email)
                        loadUserInfo(user.uid)
                    }

                    _authState.value == AuthState.AUTHENTICATED -> {
                        // User was authenticated but now Firebase user is null
                        Log.w(TAG, "Auth state changed - User became null, attempting recovery")

                        // Don't immediately log out - attempt recovery first
                        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
                        if (!sessionUserId.isNullOrEmpty()) {
                            updateAuthState(AuthState.RECOVERING)
                            attemptSessionRecovery(sessionUserId)
                        } else {
                            updateAuthState(AuthState.UNAUTHENTICATED)
                        }
                    }

                    else -> {
                        Log.d(TAG, "Auth state changed - User not authenticated")
                        updateAuthState(AuthState.UNAUTHENTICATED)
                    }
                }
            }
        }
    }

    private suspend fun attemptSessionRecovery(userId: String) {
        Log.d(TAG, "Attempting session recovery for user: $userId")

        try {
            // Check if user exists in database
            val snapshot = database.reference.child("users").child(userId).get().await()

            if (snapshot.exists()) {
                Log.d(TAG, "✅ User found in database - session is valid")

                // Keep session valid
                updateAuthState(AuthState.AUTHENTICATED)

                // Load user info from database
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: ""
                val email = snapshot.child("email").getValue(String::class.java) ?: ""
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                _currentUser.value = UserInfo(userId, email, fullName, profileImageUrl)

                // Try to silently re-authenticate if we have stored credentials
                attemptSilentReAuthentication(userId, email)

            } else {
                Log.w(TAG, "❌ User not found in database - invalid session")
                clearSession()
                updateAuthState(AuthState.UNAUTHENTICATED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover session: ${e.message}")
            // Keep existing state on error - don't log user out
        }
    }

    private suspend fun attemptSilentReAuthentication(userId: String, email: String) {
        // In a production app, you might want to:
        // 1. Use refresh tokens
        // 2. Implement biometric re-authentication
        // 3. Use custom authentication tokens

        Log.d(TAG, "Silent re-authentication attempted for: $email")

        // For now, just ensure the session stays valid
        refreshSession()
    }

    private suspend fun loadUserInfo(userId: String) {
        try {
            val snapshot = database.reference.child("users").child(userId).get().await()

            if (snapshot.exists()) {
                val fullName = snapshot.child("fullName").getValue(String::class.java) ?: ""
                val email = snapshot.child("email").getValue(String::class.java) ?: ""
                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                _currentUser.value = UserInfo(userId, email, fullName, profileImageUrl)

                // Update cached user info
                sessionPrefs.edit().apply {
                    putString(KEY_USER_NAME, fullName)
                    putString(KEY_USER_EMAIL, email)
                    apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user info: ${e.message}")

            // Try to use cached data
            val cachedName = sessionPrefs.getString(KEY_USER_NAME, "")
            val cachedEmail = sessionPrefs.getString(KEY_USER_EMAIL, "")

            if (!cachedName.isNullOrEmpty()) {
                _currentUser.value = UserInfo(userId, cachedEmail ?: "", cachedName, null)
            }
        }
    }

    fun saveSession(userId: String, email: String?) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email ?: "")
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())

            // Generate a device-specific token for additional validation
            val deviceToken = generateDeviceToken(userId)
            putString(KEY_DEVICE_TOKEN, deviceToken)

            apply()
        }

        Log.d(TAG, "Session saved for user: $userId")

        // Start authentication service
        AuthenticationService.startService(context)
    }

    fun refreshSession() {
        if (_authState.value == AuthState.AUTHENTICATED) {
            sessionPrefs.edit().apply {
                putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Session refreshed")
        }
    }

    fun clearSession() {
        sessionPrefs.edit().clear().apply()
        _currentUser.value = null
        Log.d(TAG, "Session cleared")

        // Stop authentication service
        AuthenticationService.stopService(context)
    }

    fun logout() {
        scope.launch {
            updateAuthState(AuthState.UNAUTHENTICATED)
            clearSession()

            // Sign out from Firebase
            try {
                auth.signOut()
            } catch (e: Exception) {
                Log.e(TAG, "Error signing out: ${e.message}")
            }
        }
    }

    private fun updateAuthState(newState: AuthState) {
        Log.d(TAG, "Auth state updated: ${_authState.value} -> $newState")
        _authState.value = newState
    }

    private fun generateDeviceToken(userId: String): String {
        // Generate a unique token based on user ID and device info
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        return "$userId-$deviceId-${System.currentTimeMillis()}".hashCode().toString()
    }

    fun validateSession(): Boolean {
        val isLoggedIn = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)
        val userId = sessionPrefs.getString(KEY_USER_ID, null)
        val lastLoginTime = sessionPrefs.getLong(KEY_LAST_LOGIN_TIME, 0L)
        val deviceToken = sessionPrefs.getString(KEY_DEVICE_TOKEN, null)

        val currentTime = System.currentTimeMillis()
        val timeSinceLogin = currentTime - lastLoginTime

        // Session is valid if:
        // 1. User is marked as logged in
        // 2. User ID exists
        // 3. Last login was within 30 days
        // 4. Device token exists (for this device)
        val isValid = isLoggedIn &&
                !userId.isNullOrEmpty() &&
                timeSinceLogin < 30L * 24 * 60 * 60 * 1000 &&
                !deviceToken.isNullOrEmpty()

        Log.d(TAG, "Session validation: $isValid (Time since login: ${timeSinceLogin / 1000 / 60}min)")

        return isValid
    }

    fun onDestroy() {
        scope.cancel()
    }

    // Data classes
    data class UserInfo(
        val uid: String,
        val email: String,
        val fullName: String,
        val profileImageUrl: String?
    )

    enum class AuthState {
        UNKNOWN,          // Initial state
        AUTHENTICATED,    // User is authenticated
        UNAUTHENTICATED, // User is not authenticated
        RECOVERING       // Attempting to recover session
    }
}

// Extension function for Tasks
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        cont.resume(result, null)
    }
    addOnFailureListener { exception ->
        cont.resumeWithException(exception)
    }
}