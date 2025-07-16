package com.student.events

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // AProgrammatically control the system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // This tells the system that the content behind the status bar is light, so icons should be dark
        insetsController.isAppearanceLightStatusBars = true
        // This tells the system that the content behind the navigation bar is light, so the handle should be dark
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Setup all the listeners for buttons
        setupListeners()
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
     * Uses Firebase Auth to create a new user and then saves their details to the Realtime Database.
     */
    private fun createFirebaseAccount(email: String, password: String, fullName: String) {
        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Authentication successful, now save user data to Realtime Database
                    val firebaseUser = auth.currentUser
                    val uid = firebaseUser?.uid

                    if (uid != null) {
                        // Get a reference to the "users" node in your Realtime Database
                        val databaseReference = FirebaseDatabase.getInstance().getReference("users")

                        // Create a User object with the provided details
                        val user = User(
                            fullName = fullName,
                            email = email,
                            // You can add default values for other fields
                            about = "Welcome to my profile!",
                            profileImageUrl = ""
                        )

                        // Save the user object to the database under their unique UID
                        databaseReference.child(uid).setValue(user)
                            .addOnCompleteListener { dbTask ->
                                setLoading(false)
                                if (dbTask.isSuccessful) {
                                    // Data saved successfully!
                                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                                    startActivity(Intent(this, MainActivity::class.java))
                                    finishAffinity() // Closes all previous activities
                                } else {
                                    // Failed to save data
                                    Toast.makeText(baseContext, "Failed to save user data: ${dbTask.exception?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                    } else {
                        setLoading(false)
                        Toast.makeText(baseContext, "Failed to get user ID.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // If sign up fails, display a message to the user.
                    setLoading(false)
                    Toast.makeText(baseContext, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
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
}
