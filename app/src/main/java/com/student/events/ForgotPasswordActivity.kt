package com.student.events

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.Group
import androidx.core.view.WindowCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

/**
 * Activity for handling password reset functionality.
 * Provides a secure interface for users to request password reset emails.
 * Implements security best practices to prevent email enumeration attacks
 * by showing success state regardless of whether the email exists in the system.
 */
class ForgotPasswordActivity : AppCompatActivity() {

    // Firebase Authentication service
    private lateinit var auth: FirebaseAuth

    // Form UI components
    private lateinit var emailEditText: TextInputEditText
    private lateinit var emailInputLayout: TextInputLayout
    private lateinit var sendLinkButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var backToLoginTextView: LinearLayout

    // Success state UI components
    private lateinit var backToLoginButton: Button
    private lateinit var successMessageTextView: TextView

    // View groups for state management
    private lateinit var formGroup: Group
    private lateinit var successGroup: Group

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        configureSystemUI()
        initializeFirebase()
        initializeViews()
        setupUserInteractions()
        setupKeyboardBehavior()
    }

    /**
     * Configure system UI for modern edge-to-edge display
     */
    private fun configureSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true
    }

    /**
     * Initialize Firebase authentication service
     */
    private fun initializeFirebase() {
        auth = Firebase.auth
    }

    /**
     * Initialize all UI components and configure input types
     */
    private fun initializeViews() {
        // Form state components
        emailEditText = findViewById(R.id.emailEditText)
        emailInputLayout = findViewById(R.id.emailInputLayout)
        sendLinkButton = findViewById(R.id.sendLinkButton)
        progressBar = findViewById(R.id.progressBar)
        backToLoginTextView = findViewById(R.id.backToLoginTextView)

        // Success state components
        backToLoginButton = findViewById(R.id.backToLoginButton)
        successMessageTextView = findViewById(R.id.successMessageTextView)

        // State management groups
        formGroup = findViewById(R.id.formGroup)
        successGroup = findViewById(R.id.successGroup)

        // Configure email input for optimal user experience
        emailEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }

    /**
     * Setup click listeners for user interactions
     */
    private fun setupUserInteractions() {
        sendLinkButton.setOnClickListener {
            initiatePasswordReset()
        }

        backToLoginTextView.setOnClickListener {
            navigateToLogin()
        }

        backToLoginButton.setOnClickListener {
            navigateToLogin()
        }
    }

    /**
     * Configure keyboard behavior and input handling
     */
    private fun setupKeyboardBehavior() {
        // Auto-show keyboard when email field gains focus
        emailEditText.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showKeyboard(view)
            }
        }

        // Handle Enter key press to submit form
        emailEditText.setOnEditorActionListener { _, _, _ ->
            initiatePasswordReset()
            true
        }
    }

    /**
     * Initiate password reset process with comprehensive validation
     */
    private fun initiatePasswordReset() {
        val email = emailEditText.text.toString().trim()

        // Clear any previous error states
        emailInputLayout.error = null

        // Validate email input
        if (!validateEmailInput(email)) {
            return
        }

        // Hide keyboard and show loading state
        hideKeyboard()
        setLoadingState(true)

        // Send password reset email using Firebase Auth
        sendPasswordResetEmail(email)
    }

    /**
     * Validate email input and show appropriate error messages
     * @param email The email address to validate
     * @return true if email is valid, false otherwise
     */
    private fun validateEmailInput(email: String): Boolean {
        when {
            email.isEmpty() -> {
                emailInputLayout.error = getString(R.string.email_required)
                emailEditText.requestFocus()
                return false
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                emailInputLayout.error = getString(R.string.invalid_email)
                emailEditText.requestFocus()
                return false
            }
        }
        return true
    }

    /**
     * Send password reset email through Firebase Auth
     * Implements security best practice by showing success regardless of email existence
     * @param email The email address to send reset link to
     */
    private fun sendPasswordResetEmail(email: String) {
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                setLoadingState(false)

                if (task.isSuccessful) {
                    // Show success state for security reasons (prevents email enumeration)
                    // Firebase won't actually send emails to non-existent accounts
                    showSuccessState(email)
                } else {
                    // Handle technical errors (network issues, rate limiting, etc.)
                    handlePasswordResetError(task.exception)
                }
            }
    }

    /**
     * Handle password reset errors with user-friendly messages
     * @param exception The exception from Firebase Auth
     */
    private fun handlePasswordResetError(exception: Exception?) {
        val errorMessage = when {
            exception?.message?.contains("network", ignoreCase = true) == true ||
                    exception?.message?.contains("NETWORK_ERROR", ignoreCase = true) == true ->
                getString(R.string.network_error)

            exception?.message?.contains("too-many-requests", ignoreCase = true) == true ||
                    exception?.message?.contains("TOO_MANY_ATTEMPTS_TRY_LATER", ignoreCase = true) == true ->
                getString(R.string.too_many_requests)

            exception?.message?.contains("invalid-email", ignoreCase = true) == true ->
                getString(R.string.invalid_email)

            else -> getString(R.string.reset_email_failed)
        }

        emailInputLayout.error = errorMessage
        emailEditText.requestFocus()
    }

    /**
     * Control loading state of the form
     * @param isLoading true to show loading state, false to hide
     */
    private fun setLoadingState(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        sendLinkButton.isEnabled = !isLoading
        emailEditText.isEnabled = !isLoading
    }

    /**
     * Show success state with personalized message
     * @param email The email address the reset link was sent to
     */
    private fun showSuccessState(email: String) {
        // Update success message with the provided email
        successMessageTextView.text = getString(R.string.password_reset_sent, email)

        // Transition from form state to success state
        formGroup.visibility = View.GONE
        successGroup.visibility = View.VISIBLE
    }

    /**
     * Show soft keyboard for the specified view
     * @param view The view to show keyboard for
     */
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Hide soft keyboard
     */
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    /**
     * Navigate back to login activity and clear this activity from back stack
     */
    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    /**
     * Handle back button press to navigate to login
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        navigateToLogin()
    }
}