package com.student.events

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.student.events.databinding.ActivitySignUpBinding

class SignUpActivity : AppCompatActivity() {

    // Using View Binding to easily access the views from the XML layout
    private lateinit var binding: ActivitySignUpBinding
    // Firebase Authentication instance
    private lateinit var auth: FirebaseAuth

    // NEW: Session management
    private lateinit var sessionPrefs: SharedPreferences

    companion object {
        private const val TAG = "SignUpActivity"
        private const val PREFS_NAME = "EventsAppSession"
        private const val KEY_USER_LOGGED_IN = "user_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Programmatically control the system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize Firebase Auth and session preferences
        auth = FirebaseAuth.getInstance()
        sessionPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        Log.d(TAG, "SignUpActivity created")

        // Setup all the listeners for buttons
        setupListeners()
        setupInputTypes()
    }

    private fun setupInputTypes() {
        binding.fullNameEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        binding.emailEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        binding.passwordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        binding.confirmPasswordEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun setupListeners() {
        // Listener for the sign-up button
        binding.signupButton.setOnClickListener {
            validateAndSignUp()
        }

        // Listener for the login layout to navigate to the LoginActivity
        binding.loginLayout.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    /**
     * Validates all input fields. If all are valid, proceeds with Firebase sign-up.
     */
    private fun validateAndSignUp() {
        val fullName = binding.fullNameEditText.text.toString().trim()
        val email = binding.emailEditText.text.toString().trim()
        val password = binding.passwordEditText.text.toString()
        val confirmPassword = binding.confirmPasswordEditText.text.toString()

        // Clear previous errors
        binding.fullNameLayout.error = null
        binding.emailLayout.error = null
        binding.passwordLayout.error = null
        binding.confirmPasswordLayout.error = null

        // Validation checks
        if (fullName.isEmpty()) {
            binding.fullNameLayout.error = "Full name is required"
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = "Invalid email address"
            return
        }

        if (password.length < 8) {
            binding.passwordLayout.error = "Password must be at least 8 characters"
            return
        }

        if (password != confirmPassword) {
            binding.confirmPasswordLayout.error = "Passwords do not match"
            return
        }

        if (!binding.termsCheckbox.isChecked) {
            Toast.makeText(this, "Please accept the terms and conditions", Toast.LENGTH_SHORT).show()
            return
        }

        // If all validations pass, create the account
        createFirebaseAccount(email, password, fullName)
    }

    /**
     * Data class to represent a User object in the database.
     */
    data class User(
        val fullName: String = "",
        val email: String = "",
        val about: String = "",
        val profileImageUrl: String = ""
    )

    /**
     * Enhanced Firebase account creation with session management
     */
    private fun createFirebaseAccount(email: String, password: String, fullName: String) {
        setLoading(true)

        Log.d(TAG, "Creating Firebase account for: $email")

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Authentication successful, now save user data to Realtime Database
                    val firebaseUser = auth.currentUser
                    val uid = firebaseUser?.uid

                    if (uid != null) {
                        Log.d(TAG, "✅ Firebase user created: $uid")

                        // Get a reference to the "users" node in your Realtime Database
                        val databaseReference = FirebaseDatabase.getInstance().getReference("users")

                        // Create a User object with the provided details
                        val user = User(
                            fullName = fullName,
                            email = email,
                            about = "Welcome to my profile!",
                            profileImageUrl = ""
                        )

                        // Save the user object to the database under their unique UID
                        databaseReference.child(uid).setValue(user)
                            .addOnCompleteListener { dbTask ->
                                setLoading(false)
                                if (dbTask.isSuccessful) {
                                    Log.d(TAG, "✅ User data saved successfully")

                                    // NEW: Save session data for immediate persistence
                                    updateSessionData(uid)

                                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                                    navigateToMain()
                                } else {
                                    Log.e(TAG, "Failed to save user data: ${dbTask.exception?.message}")

                                    // Even if database save fails, user is created in Auth
                                    // Save session data and let them proceed
                                    updateSessionData(uid)

                                    Toast.makeText(
                                        baseContext,
                                        "Account created but profile data failed to save. You can update it later.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    navigateToMain()
                                }
                            }
                    } else {
                        setLoading(false)
                        Log.e(TAG, "Failed to get user ID after account creation")
                        Toast.makeText(baseContext, "Failed to get user ID.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // If sign up fails, display a message to the user.
                    setLoading(false)
                    val errorMessage = task.exception?.message ?: "Unknown error"
                    Log.e(TAG, "Authentication failed: $errorMessage")
                    Toast.makeText(baseContext, "Authentication failed: $errorMessage", Toast.LENGTH_LONG).show()

                    // Clear any partial session data
                    clearSessionData()
                }
            }
    }

    /**
     * NEW: Update session data when user signs up successfully
     */
    private fun updateSessionData(userId: String) {
        sessionPrefs.edit().apply {
            putBoolean(KEY_USER_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Session data saved for new user: $userId")
    }

    /**
     * NEW: Clear session data
     */
    private fun clearSessionData() {
        sessionPrefs.edit().clear().apply()
        Log.d(TAG, "Session data cleared")
    }

    /**
     * Enhanced navigation to MainActivity with proper session setup
     */
    private fun navigateToMain() {
        Log.d(TAG, "Navigating to MainActivity")

        val intent = Intent(this, MainActivity::class.java)
        // Clear the back stack so user can't return to signup by pressing back
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Manages the loading state of the sign up button and progress bar
     */
    private fun setLoading(isLoading: Boolean) {
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
     * NEW: Clean up on destroy if needed
     */
    override fun onDestroy() {
        super.onDestroy()

        // If we're destroying without a successful signup and there's no Firebase user,
        // make sure we don't have stale session data
        if (auth.currentUser == null && !isFinishing) {
            Log.d(TAG, "Activity destroyed without valid user - ensuring clean session state")
        }
    }
}