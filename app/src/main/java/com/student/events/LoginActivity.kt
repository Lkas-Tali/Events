package com.student.events

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.student.events.services.AuthStateManager

class LoginActivity : AppCompatActivity() {

    // Declare a variable for the Firebase Authentication service.
    private lateinit var auth: FirebaseAuth

    // NEW: Session management
    private lateinit var sessionPrefs: SharedPreferences

    // Declare variables for all the UI elements we will interact with.
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var forgotPasswordTextView: TextView
    private lateinit var signupLayout: LinearLayout

    companion object {
        private const val TAG = "LoginActivity"
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Programmatically control the system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize the FirebaseAuth instance and session preferences
        auth = Firebase.auth
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        Log.d(TAG, "LoginActivity created")

        // Get references to UI elements
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        progressBar = findViewById(R.id.progressBar)
        forgotPasswordTextView = findViewById(R.id.forgotPasswordTextView)
        signupLayout = findViewById(R.id.signupLayout)

        // --- ROBUST KEYBOARD FIX ---
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showKeyboard(view)
            }
        }
        emailEditText.onFocusChangeListener = focusListener
        passwordEditText.onFocusChangeListener = focusListener

        // Set up click listeners
        loginButton.setOnClickListener {
            performLogin()
        }

        forgotPasswordTextView.setOnClickListener {
            val intent = Intent(this, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }

        signupLayout.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Enhanced onStart method with comprehensive authentication checking
     */
    public override fun onStart() {
        super.onStart()

        Log.d(TAG, "onStart - Checking existing authentication")

        val currentUser = auth.currentUser
        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
        val isSessionValid = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)
        val lastLoginTime = sessionPrefs.getLong(KEY_LAST_LOGIN_TIME, 0)
        val currentTime = System.currentTimeMillis()

        Log.d(TAG, "Firebase user: ${currentUser?.uid}")
        Log.d(TAG, "Session user: $sessionUserId")
        Log.d(TAG, "Session valid: $isSessionValid")
        Log.d(TAG, "Time since last login: ${(currentTime - lastLoginTime) / 1000}s")

        when {
            // Case 1: Firebase user exists and session is valid
            currentUser != null && isSessionValid && currentUser.uid == sessionUserId -> {
                Log.d(TAG, "✅ Valid authentication found - navigating to main")
                updateSessionData(currentUser.uid)
                navigateToMain()
            }

            // Case 2: Firebase user exists but session is missing/invalid - restore session
            currentUser != null -> {
                Log.d(TAG, "✅ Firebase user found, restoring session")
                updateSessionData(currentUser.uid)
                navigateToMain()
            }

            // Case 3: No Firebase user but valid recent session - attempt restore
            currentUser == null && isSessionValid && sessionUserId != null &&
                    (currentTime - lastLoginTime) < 7 * 24 * 60 * 60 * 1000L -> { // 7 days
                Log.d(TAG, "⚠️ Firebase user missing but recent session exists - attempting silent login")
                // For this case, we'll stay on login screen but could attempt silent re-auth
                // This is safer than automatically logging in without Firebase confirmation
                showSessionRestoreOption(sessionUserId)
            }

            // Case 4: No valid authentication
            else -> {
                Log.d(TAG, "ℹ️ No valid authentication - staying on login screen")
                clearSessionData()
                // Stay on login screen
            }
        }
    }

    /**
     * NEW: Show option to restore previous session
     */
    private fun showSessionRestoreOption(userId: String) {
        // Could show a "Restore previous session?" dialog
        // For now, we'll just clear the invalid session
        Log.d(TAG, "Clearing invalid session for user: $userId")
        clearSessionData()
    }

    /**
     * Enhanced login with session management
     */
    private fun performLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        // Input validation
        if (email.isEmpty()) {
            emailEditText.error = "Email address is required."
            emailEditText.requestFocus()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Please enter a valid email address."
            emailEditText.requestFocus()
            return
        }

        if (password.isEmpty()) {
            passwordEditText.error = "Password is required."
            passwordEditText.requestFocus()
            return
        }

        // Show loading state
        progressBar.visibility = View.VISIBLE
        loginButton.isEnabled = false

        Log.d(TAG, "Attempting login for: $email")

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        // Use AuthStateManager to save session
                        val authStateManager = AuthStateManager.getInstance(this)
                        authStateManager.saveSession(user.uid, user.email)

                        Toast.makeText(baseContext, "Login Successful.", Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                }
                 else {
                    val errorMessage = task.exception?.message ?: "Unknown error"
                    Log.e(TAG, "Login failed: $errorMessage")
                    Toast.makeText(baseContext, "Authentication failed: $errorMessage", Toast.LENGTH_LONG).show()

                    // Clear any existing session data on failed login
                    clearSessionData()
                }
            }
    }

    /**
     * NEW: Update session data when user logs in
     */
    private fun updateSessionData(userId: String) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Session data saved for user: $userId")
    }

    /**
     * NEW: Clear session data
     */
    private fun clearSessionData() {
        sessionPrefs.edit().clear().apply()
        Log.d(TAG, "Session data cleared")
    }

    /**
     * Helper function to explicitly show the soft keyboard for a given View.
     */
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Enhanced navigation to MainActivity with proper session setup
     */
    private fun navigateToMain() {
        Log.d(TAG, "Navigating to MainActivity")

        val intent = Intent(this, MainActivity::class.java)
        // Clear the back stack so user can't return to login by pressing back
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * NEW: Handle back button to prevent session issues
     */
    override fun onBackPressed() {
        // Clear any partial session data if user backs out
        if (sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false) && auth.currentUser == null) {
            clearSessionData()
        }
        super.onBackPressed()
    }

    /**
     * NEW: Clear session data if activity is destroyed without successful login
     */
    override fun onDestroy() {
        super.onDestroy()

        // If we're destroying without a successful login and there's no Firebase user,
        // make sure we don't have stale session data
        if (auth.currentUser == null && !isFinishing) {
            Log.d(TAG, "Activity destroyed without valid user - ensuring clean session state")
        }
    }
}