package com.student.events

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.student.events.databinding.ActivityCreateEventBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CreateEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateEventBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var storage: FirebaseStorage

    private var selectedImageUri: Uri? = null
    private var selectedDate: String = ""
    private var selectedTime: String = ""

    // Edit mode variables
    private var isEditMode = false
    private var eventId: String? = null
    private var existingImageUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display - EXACTLY like MainActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Configure system bar appearance - EXACTLY like MainActivity
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        binding = ActivityCreateEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        storage = FirebaseStorage.getInstance()

        isEditMode = intent.getBooleanExtra("editMode", false)
        if (isEditMode) {
            setupEditMode()
        }

        // Apply system bar insets - EXACTLY like MainActivity
        applySystemBarInsets()

        setupViews()
    }

    // COPIED EXACTLY from MainActivity
    private fun applySystemBarInsets() {
        val header = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.nestedScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    private fun setupEditMode() {
        binding.pageTitle.text = "Edit Event"
        binding.createButton.text = "Save Changes"

        eventId = intent.getStringExtra("eventId")
        binding.titleInput.setText(intent.getStringExtra("eventTitle"))
        selectedDate = intent.getStringExtra("eventDate") ?: ""
        selectedTime = intent.getStringExtra("eventTime") ?: ""
        binding.locationInput.setText(intent.getStringExtra("eventLocation"))
        binding.descriptionInput.setText(intent.getStringExtra("eventDescription"))
        existingImageUrl = intent.getStringExtra("eventImage")

        binding.dateInput.setText(formatDateForDisplay(selectedDate))
        binding.timeInput.setText(selectedTime)

        existingImageUrl?.let { url ->
            if (url.isNotEmpty()) {
                binding.imagePreview.visibility = View.VISIBLE
                Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.image_placeholder)
                    .into(binding.imagePreview)
            }
        }
    }

    private fun setupViews() {
        // Updated back button setup - now using ImageView like ProfileActivity
        binding.backButton.setOnClickListener { finish() }

        binding.dateInput.setOnClickListener { showDatePicker() }
        binding.timeInput.setOnClickListener { showTimePicker() }
        binding.imageUploadArea.setOnClickListener { selectImage() }
        binding.cancelButton.setOnClickListener { finish() }
        binding.createButton.setOnClickListener {
            if (validateInputs()) {
                handleEventCreationOrUpdate()
            }
        }
    }

    private fun handleEventCreationOrUpdate() {
        setLoading(true)
        if (selectedImageUri != null) {
            uploadImageThenSaveEvent()
        } else {
            saveEventToDatabase(existingImageUrl)
        }
    }

    private fun uploadImageThenSaveEvent() {
        try {
            val storageRef = storage.reference.child("event_images/${UUID.randomUUID()}.jpg")
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val data = baos.toByteArray()

            storageRef.putBytes(data)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { uri ->
                        saveEventToDatabase(uri.toString())
                    }.addOnFailureListener {
                        setLoading(false)
                        Toast.makeText(this, "Failed to get image URL.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    setLoading(false)
                    Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }

        } catch (e: Exception) {
            setLoading(false)
            Toast.makeText(this, "Failed to process image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveEventToDatabase(imageUrl: String?) {
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        if (isEditMode) {
            // Update existing event
            eventId?.let { id ->
                val updates = hashMapOf<String, Any?>(
                    "title" to title,
                    "location" to location,
                    "description" to description,
                    "dateTime" to getCombinedDateTime(),
                    "imageUrl" to imageUrl
                )
                database.reference.child("events").child(id).updateChildren(updates)
                    .addOnSuccessListener {
                        setLoading(false)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        setLoading(false)
                        Toast.makeText(this, "Failed to update event: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        } else {
            // Create new event
            val user = auth.currentUser ?: return
            val userId = user.uid

            // --- FIX STARTS HERE ---
            // Fetch the user's full name from the Realtime Database before creating the event.
            database.reference.child("users").child(userId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val userName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown"
                    val userPhotoUrl = user.photoUrl?.toString() ?: ""

                    val creatorAsAttendee = mapOf(
                        "fullName" to userName,
                        "profileImageUrl" to userPhotoUrl
                    )

                    val event = hashMapOf(
                        "title" to title,
                        "location" to location,
                        "description" to description,
                        "imageUrl" to (imageUrl ?: ""),
                        "organizer" to mapOf("uid" to userId, "fullName" to userName),
                        "attendees" to mapOf(userId to creatorAsAttendee),
                        "attendeesCount" to 1,
                        "status" to "upcoming",
                        "dateTime" to getCombinedDateTime()
                    )

                    database.reference.child("events").push().setValue(event)
                        .addOnSuccessListener {
                            setLoading(false)
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            Toast.makeText(this@CreateEventActivity, "Failed to create event: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    setLoading(false)
                    Toast.makeText(this@CreateEventActivity, "Failed to get user details: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
            // --- FIX ENDS HERE ---
        }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        if (isEditMode && selectedDate.isNotEmpty()) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate)
                date?.let { calendar.time = it }
            } catch (e: Exception) { /* Ignore */ }
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
                binding.dateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.UK).format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        if (isEditMode && selectedTime.isNotEmpty()) {
            try {
                val parts = selectedTime.split(":")
                if (parts.size == 2) {
                    calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    calendar.set(Calendar.MINUTE, parts[1].toInt())
                }
            } catch (e: Exception) { /* Ignore */ }
        }
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                selectedTime = String.format("%02d:%02d", hourOfDay, minute)
                binding.timeInput.setText(selectedTime)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, IMAGE_PICK_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                selectedImageUri = uri
                binding.imagePreview.visibility = View.VISIBLE
                binding.imagePreview.setImageURI(uri)
                binding.uploadText.text = "Click to change image"
            }
        }
    }

    private fun validateInputs(): Boolean {
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        when {
            title.isEmpty() -> {
                binding.titleInputLayout.error = "Event title is required"
                return false
            }
            selectedDate.isEmpty() -> {
                Toast.makeText(this, "Please select a date", Toast.LENGTH_SHORT).show()
                return false
            }
            selectedTime.isEmpty() -> {
                Toast.makeText(this, "Please select a time", Toast.LENGTH_SHORT).show()
                return false
            }
            location.isEmpty() -> {
                binding.locationInputLayout.error = "Location is required"
                return false
            }
            description.isEmpty() -> {
                binding.descriptionInputLayout.error = "Description is required"
                return false
            }
        }
        return true
    }

    private fun getCombinedDateTime(): Map<String, Long>? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val date = format.parse("$selectedDate $selectedTime")
            mapOf("_seconds" to (date!!.time / 1000), "_nanoseconds" to 0)
        } catch (e: Exception) {
            null
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.createButton.isEnabled = false
            binding.cancelButton.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            binding.createButton.isEnabled = true
            binding.cancelButton.isEnabled = true
        }
    }

    private fun formatDateForDisplay(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("dd/MM/yyyy", Locale.UK)
            val date = inputFormat.parse(dateString)
            date?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val IMAGE_PICK_REQUEST = 1001
    }
}