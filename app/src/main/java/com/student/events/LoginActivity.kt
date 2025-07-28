package com.student.events

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
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

/**
 * LoginActivity handles user authentication and session management.
 * Features secure login with Firebase Auth and persistent session storage.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var sessionPrefs: SharedPreferences

    // UI Components
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var forgotPasswordTextView: TextView
    private lateinit var signupLayout: LinearLayout

    companion object {
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Configure edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize Firebase and session management
        auth = Firebase.auth
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        initializeViews()
        setupInputHandlers()
        setupClickListeners()
    }

    /**
     * Initialize all UI components
     */
    private fun initializeViews() {
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        progressBar = findViewById(R.id.progressBar)
        forgotPasswordTextView = findViewById(R.id.forgotPasswordTextView)
        signupLayout = findViewById(R.id.signupLayout)
    }

    /**
     * Setup input field behavior and keyboard handling
     */
    private fun setupInputHandlers() {
        // Configure input types for better user experience
        emailEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        passwordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        // Enhanced keyboard management
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showKeyboard(view)
            }
        }
        emailEditText.onFocusChangeListener = focusListener
        passwordEditText.onFocusChangeListener = focusListener
    }

    /**
     * Setup click listeners for all interactive elements
     */
    private fun setupClickListeners() {
        loginButton.setOnClickListener { performLogin() }

        forgotPasswordTextView.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        signupLayout.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    /**
     * Check for existing authentication when activity starts
     */
    public override fun onStart() {
        super.onStart()

        val currentUser = auth.currentUser
        val sessionUserId = sessionPrefs.getString(KEY_USER_ID, null)
        val isSessionValid = sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false)
        val lastLoginTime = sessionPrefs.getLong(KEY_LAST_LOGIN_TIME, 0)
        val currentTime = System.currentTimeMillis()

        when {
            // Valid Firebase user with matching session
            currentUser != null && isSessionValid && currentUser.uid == sessionUserId -> {
                updateSessionData(currentUser.uid)
                navigateToMain()
            }

            // Firebase user exists but session needs restoration
            currentUser != null -> {
                updateSessionData(currentUser.uid)
                navigateToMain()
            }

            // Session exists but Firebase user is missing - check if recent
            currentUser == null && isSessionValid && sessionUserId != null &&
                    (currentTime - lastLoginTime) < 7 * 24 * 60 * 60 * 1000L -> {
                // Session exists but older than 7 days, clear it
                clearSessionData()
            }

            // No valid authentication found
            else -> {
                clearSessionData()
            }
        }
    }

    /**
     * Perform user login with input validation and Firebase authentication
     */
    private fun performLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        // Validate email input
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

        // Validate password input
        if (password.isEmpty()) {
            passwordEditText.error = "Password is required."
            passwordEditText.requestFocus()
            return
        }

        // Show loading state
        setLoadingState(true)

        // Attempt Firebase authentication
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                setLoadingState(false)

                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        // Save session using AuthStateManager
                        val authStateManager = AuthStateManager.getInstance(this)
                        authStateManager.saveSession(user.uid, user.email)

                        Toast.makeText(this, "Login Successful.", Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                } else {
                    val errorMessage = task.exception?.message ?: "Authentication failed"
                    Toast.makeText(this, "Authentication failed: $errorMessage", Toast.LENGTH_LONG).show()
                    clearSessionData()
                }
            }
    }

    /**
     * Update local session data for persistent login
     */
    private fun updateSessionData(userId: String) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }
    }

    /**
     * Clear all session data
     */
    private fun clearSessionData() {
        sessionPrefs.edit().clear().apply()
    }

    /**
     * Show or hide loading indicators
     */
    private fun setLoadingState(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        loginButton.isEnabled = !isLoading
    }

    /**
     * Show soft keyboard for the specified view
     */
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Navigate to MainActivity and clear the activity stack
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Handle back button press to maintain clean session state
     */
    override fun onBackPressed() {
        // Clear any partial session data if user backs out
        if (sessionPrefs.getBoolean(KEY_USER_LOGGED_IN, false) && auth.currentUser == null) {
            clearSessionData()
        }
        super.onBackPressed()
    }
}