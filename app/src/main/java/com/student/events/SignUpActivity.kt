package com.student.events

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
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
        createFirebaseAccount(email, password)
    }

    /**
     * Uses Firebase Auth to create a new user with email and password.
     */
    private fun createFirebaseAccount(email: String, password: String) {
        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                setLoading(false)
                if (task.isSuccessful) {
                    // Sign up success, navigate to the main activity
                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finishAffinity() // Closes all previous activities
                } else {
                    // If sign up fails, display a message to the user.
                    Toast.makeText(baseContext, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
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
