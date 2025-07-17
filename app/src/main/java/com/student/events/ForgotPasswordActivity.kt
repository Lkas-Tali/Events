package com.student.events

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class ForgotPasswordActivity : AppCompatActivity() {

    // Firebase Auth instance
    private lateinit var auth: FirebaseAuth

    // UI elements
    private lateinit var emailEditText: TextInputEditText
    private lateinit var emailInputLayout: TextInputLayout
    private lateinit var sendLinkButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var backToLoginTextView: LinearLayout
    private lateinit var backToLoginButton: Button
    private lateinit var successMessageTextView: TextView

    // View groups for showing/hiding states
    private lateinit var formGroup: Group
    private lateinit var successGroup: Group

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Configure system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize Firebase Auth
        auth = Firebase.auth

        // Initialize UI elements
        initializeViews()

        // Set up click listeners
        setupClickListeners()

        // Set up keyboard handling
        setupKeyboardHandling()
    }

    private fun initializeViews() {
        // Form elements
        emailEditText = findViewById(R.id.emailEditText)
        emailInputLayout = findViewById(R.id.emailInputLayout)
        sendLinkButton = findViewById(R.id.sendLinkButton)
        progressBar = findViewById(R.id.progressBar)
        backToLoginTextView = findViewById(R.id.backToLoginTextView)

        // Success state elements
        backToLoginButton = findViewById(R.id.backToLoginButton)
        successMessageTextView = findViewById(R.id.successMessageTextView)

        // View groups
        formGroup = findViewById(R.id.formGroup)
        successGroup = findViewById(R.id.successGroup)
    }

    private fun setupClickListeners() {
        // Send reset link button
        sendLinkButton.setOnClickListener {
            sendPasswordResetEmail()
        }

        // Back to login link (form state)
        backToLoginTextView.setOnClickListener {
            navigateToLogin()
        }

        // Back to login button (success state)
        backToLoginButton.setOnClickListener {
            navigateToLogin()
        }
    }

    private fun setupKeyboardHandling() {
        // Show keyboard when email field gets focus
        emailEditText.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showKeyboard(view)
            }
        }

        // Handle Enter key press
        emailEditText.setOnEditorActionListener { _, _, _ ->
            sendPasswordResetEmail()
            true
        }
    }

    private fun sendPasswordResetEmail() {
        val email = emailEditText.text.toString().trim()

        // Clear any previous errors
        emailInputLayout.error = null

        // Validate email
        if (email.isEmpty()) {
            emailInputLayout.error = getString(R.string.email_required)
            emailEditText.requestFocus()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInputLayout.error = getString(R.string.invalid_email)
            emailEditText.requestFocus()
            return
        }

        // Hide keyboard
        hideKeyboard()

        // Show loading state
        showLoadingState(true)

        // Send password reset email directly without checking if email exists
        // This is the recommended approach by Firebase for security reasons
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                showLoadingState(false)

                if (task.isSuccessful) {
                    // Always show success state, regardless of whether email exists
                    // Firebase won't send emails to non-existent accounts, but user doesn't know this
                    // This prevents email enumeration attacks
                    showSuccessState(email)
                } else {
                    // Show error message only for technical issues (network, rate limiting, etc.)
                    val errorMessage = when {
                        task.exception?.message?.contains("network") == true ||
                                task.exception?.message?.contains("NETWORK_ERROR") == true ->
                            getString(R.string.network_error)

                        task.exception?.message?.contains("too-many-requests") == true ||
                                task.exception?.message?.contains("TOO_MANY_ATTEMPTS_TRY_LATER") == true ->
                            getString(R.string.too_many_requests)

                        task.exception?.message?.contains("invalid-email") == true ->
                            getString(R.string.invalid_email)

                        else -> getString(R.string.reset_email_failed)
                    }

                    emailInputLayout.error = errorMessage
                    emailEditText.requestFocus()
                }
            }
    }

    private fun showLoadingState(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        sendLinkButton.isEnabled = !isLoading
        emailEditText.isEnabled = !isLoading
    }

    private fun showSuccessState(email: String) {
        // Update success message with the email
        successMessageTextView.text = getString(R.string.password_reset_sent, email)

        // Hide form and show success state
        formGroup.visibility = View.GONE
        successGroup.visibility = View.VISIBLE
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun navigateToLogin() {
        // Use FLAG_CLEAR_TOP to remove this activity from the back stack
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Navigate back to login when back button is pressed
        super.onBackPressed()
        navigateToLogin()
    }
}