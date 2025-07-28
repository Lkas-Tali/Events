package com.student.events

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.student.events.databinding.ActivitySignUpBinding

/**
 * SignUpActivity handles new user registration with Firebase Auth and database.
 * Creates user accounts and stores profile information in Realtime Database.
 */
class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var sessionPrefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize Firebase and session management
        auth = FirebaseAuth.getInstance()
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupUserInterface()
        setupInputTypes()
    }

    /**
     * Configure input field types for better user experience
     */
    private fun setupInputTypes() {
        binding.fullNameEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        binding.emailEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        binding.passwordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        binding.confirmPasswordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /**
     * Setup UI component listeners and interactions
     */
    private fun setupUserInterface() {
        // Sign-up button handler
        binding.signupButton.setOnClickListener {
            validateAndSignUp()
        }

        // Navigate to login screen
        binding.loginLayout.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    /**
     * Validate all input fields and proceed with account creation if valid
     */
    private fun validateAndSignUp() {
        val fullName = binding.fullNameEditText.text.toString().trim()
        val email = binding.emailEditText.text.toString().trim()
        val password = binding.passwordEditText.text.toString()
        val confirmPassword = binding.confirmPasswordEditText.text.toString()

        // Clear any previous error messages
        clearValidationErrors()

        // Validate full name
        if (fullName.isEmpty()) {
            binding.fullNameLayout.error = "Full name is required"
            return
        }

        // Validate email format
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = "Invalid email address"
            return
        }

        // Validate password strength with comprehensive requirements
        val passwordValidation = validatePassword(password)
        if (passwordValidation != null) {
            binding.passwordLayout.error = passwordValidation
            return
        }

        // Validate password confirmation
        if (password != confirmPassword) {
            binding.confirmPasswordLayout.error = "Passwords do not match"
            return
        }

        // Validate terms acceptance
        if (!binding.termsCheckbox.isChecked) {
            Toast.makeText(this, "Please accept the terms and conditions", Toast.LENGTH_SHORT).show()
            return
        }

        // All validations passed, create the account
        createFirebaseAccount(email, password, fullName)
    }

    /**
     * Clear all validation error messages from input fields
     */
    private fun clearValidationErrors() {
        binding.fullNameLayout.error = null
        binding.emailLayout.error = null
        binding.passwordLayout.error = null
        binding.confirmPasswordLayout.error = null
    }

    /**
     * User data model for Firebase Realtime Database
     */
    data class User(
        val fullName: String = "",
        val email: String = "",
        val about: String = "",
        val profileImageUrl: String = ""
    )

    /**
     * Validate password strength with comprehensive requirements
     * @param password The password to validate
     * @return Error message if invalid, null if valid
     */
    private fun validatePassword(password: String): String? {
        if (password.length < 8) {
            return "Password must be at least 8 characters long"
        }

        if (!password.any { it.isUpperCase() }) {
            return "Password must contain at least one uppercase letter"
        }

        if (!password.any { it.isLowerCase() }) {
            return "Password must contain at least one lowercase letter"
        }

        if (!password.any { it.isDigit() }) {
            return "Password must contain at least one number"
        }

        val specialCharacters = "!@#$%^&*()_+-=[]{}|;:,.<>?"
        if (!password.any { specialCharacters.contains(it) }) {
            return "Password must contain at least one special character (!@#$%^&*()_+-=[]{}|;:,.<>?)"
        }

        return null // Password is valid
    }

    /**
     * Create new Firebase account and save user profile data
     */
    private fun createFirebaseAccount(email: String, password: String, fullName: String) {
        setLoadingState(true)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    val uid = firebaseUser?.uid

                    if (uid != null) {
                        saveUserToDatabase(uid, fullName, email)
                    } else {
                        setLoadingState(false)
                        Toast.makeText(this, "Failed to get user ID.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    setLoadingState(false)
                    val errorMessage = task.exception?.message ?: "Authentication failed"
                    Toast.makeText(this, "Authentication failed: $errorMessage", Toast.LENGTH_LONG).show()
                    clearSessionData()
                }
            }
    }

    /**
     * Save user profile data to Firebase Realtime Database
     */
    private fun saveUserToDatabase(uid: String, fullName: String, email: String) {
        val databaseReference = FirebaseDatabase.getInstance().getReference("users")

        // Create user profile object with default values
        val user = User(
            fullName = fullName,
            email = email,
            about = "Welcome to my profile!",
            profileImageUrl = ""
        )

        // Save user data to database
        databaseReference.child(uid).setValue(user)
            .addOnCompleteListener { dbTask ->
                setLoadingState(false)

                if (dbTask.isSuccessful) {
                    // Account created and profile saved successfully
                    updateSessionData(uid)
                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    // Account created but profile save failed - still allow login
                    updateSessionData(uid)
                    Toast.makeText(
                        this,
                        "Account created but profile data failed to save. You can update it later.",
                        Toast.LENGTH_LONG
                    ).show()
                    navigateToMain()
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
     * Navigate to MainActivity and clear the activity stack
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Show or hide loading state for sign-up process
     */
    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            binding.signupButton.text = ""
            binding.progressBar.visibility = View.VISIBLE
            binding.signupButton.isEnabled = false
        } else {
            binding.signupButton.setText(R.string.create_account)
            binding.progressBar.visibility = View.GONE
            binding.signupButton.isEnabled = true
        }
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