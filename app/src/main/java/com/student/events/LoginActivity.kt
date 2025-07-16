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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {

    // Declare a variable for the Firebase Authentication service.
    // 'lateinit' means we promise to initialize it before we use it.
    private lateinit var auth: FirebaseAuth

    // Declare variables for all the UI elements we will interact with.
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var forgotPasswordTextView: TextView
    private lateinit var signupLayout: LinearLayout


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This line connects our Kotlin code to our XML layout file.
        setContentView(R.layout.activity_login)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // AProgrammatically control the system bar appearance
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // This tells the system that the content behind the status bar is light, so icons should be dark
        insetsController.isAppearanceLightStatusBars = true
        // This tells the system that the content behind the navigation bar is light, so the handle should be dark
        insetsController.isAppearanceLightNavigationBars = true

        // Initialize the FirebaseAuth instance.
        auth = Firebase.auth

        // Get references to our UI elements from the XML layout using their IDs.
        // This is how we can control them from our code.
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        progressBar = findViewById(R.id.progressBar)
        forgotPasswordTextView = findViewById(R.id.forgotPasswordTextView)
        signupLayout = findViewById(R.id.signupLayout)

        // --- ROBUST KEYBOARD FIX ---
        // Set focus change listeners to programmatically show the keyboard.
        // This is more robust than a click listener as it also handles tabbing.
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showKeyboard(view)
            }
        }
        emailEditText.onFocusChangeListener = focusListener
        passwordEditText.onFocusChangeListener = focusListener
        // --- END KEYBOARD FIX ---


        // Set up the click listener for the main login button.
        // The code inside the curly braces will run when the button is clicked.
        loginButton.setOnClickListener {
            // When the button is clicked, we call our function to perform the login.
            performLogin()
        }

        // Set up the click listener for the "Forgot Password?" text.
        forgotPasswordTextView.setOnClickListener {
            val intent = Intent(this, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }

        // Set up the click listener for the "Sign up" layout.
        signupLayout.setOnClickListener {
            // Create an Intent to navigate to the SignUpActivity.
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * This function is called when the app starts. It checks if a user is already signed in.
     * If they are, we can send them directly to the main screen without making them log in again.
     */
    public override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // User is already signed in, navigate to MainActivity
            navigateToMain()
        }
    }

    /**
     * Handles the login logic when the login button is pressed.
     */
    private fun performLogin() {
        // Get the text from the input fields and trim whitespace.
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        // --- Input Validation ---
        // Check if the email field is empty.
        if (email.isEmpty()) {
            emailEditText.error = "Email address is required."
            emailEditText.requestFocus() // Put the cursor in the email field.
            return // Stop the function here.
        }

        // Check if the email format is valid.
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Please enter a valid email address."
            emailEditText.requestFocus()
            return
        }

        // Check if the password field is empty.
        if (password.isEmpty()) {
            passwordEditText.error = "Password is required."
            passwordEditText.requestFocus()
            return
        }

        // --- Firebase Authentication ---
        // Show the progress bar and disable the button to provide user feedback.
        progressBar.visibility = View.VISIBLE
        loginButton.isEnabled = false

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                // This block of code runs when Firebase responds.

                // Login was successful.
                if (task.isSuccessful) {
                    Toast.makeText(baseContext, "Login Successful.", Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    // If sign in fails, display a message to the user.
                    // The task.exception will contain more specific error info.
                    Toast.makeText(baseContext, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()

                    // Hide the progress bar and re-enable the button so the user can try again.
                    progressBar.visibility = View.GONE
                    loginButton.isEnabled = true
                }
            }
    }

    /**
     * Helper function to explicitly show the soft keyboard for a given View.
     * @param view The View that should receive focus and for which the keyboard should be shown.
     */
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }


    /**
     * Navigates the user to the MainActivity (the main app dashboard).
     */
    private fun navigateToMain(){
        // An Intent is an object used to request an action from another app component.
        // Here, we're requesting to start the MainActivity.
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        // Call finish() to close the LoginActivity so the user cannot press the back
        // button to return to it after logging in.
        finish()
    }
}
