package com.student.events

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.student.events.databinding.ActivityCreateEventBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CreateEventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateEventBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private var selectedImageUri: Uri? = null
    private var selectedDate: String = ""
    private var selectedTime: String = ""

    // Edit mode variables
    private var isEditMode = false
    private var eventId: String? = null
    private var existingImageUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateEventBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Check if we're in edit mode
        isEditMode = intent.getBooleanExtra("editMode", false)
        if (isEditMode) {
            setupEditMode()
        }

        setupViews()
    }

    private fun setupEditMode() {
        binding.pageTitle.text = "Edit Event"
        binding.createButton.text = "Save Changes"

        // Populate fields with existing data
        eventId = intent.getStringExtra("eventId")
        binding.titleInput.setText(intent.getStringExtra("eventTitle"))
        selectedDate = intent.getStringExtra("eventDate") ?: ""
        selectedTime = intent.getStringExtra("eventTime") ?: ""
        binding.locationInput.setText(intent.getStringExtra("eventLocation"))
        binding.descriptionInput.setText(intent.getStringExtra("eventDescription"))
        existingImageUrl = intent.getStringExtra("eventImage")

        // Format and display date/time
        binding.dateInput.setText(formatDateForDisplay(selectedDate))
        binding.timeInput.setText(selectedTime)

        // Load existing image if available
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
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.dateInput.setOnClickListener {
            showDatePicker()
        }

        binding.timeInput.setOnClickListener {
            showTimePicker()
        }

        binding.imageUploadArea.setOnClickListener {
            selectImage()
        }

        binding.cancelButton.setOnClickListener {
            finish()
        }

        binding.createButton.setOnClickListener {
            if (validateInputs()) {
                if (isEditMode) {
                    updateEvent()
                } else {
                    createEvent()
                }
            }
        }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()

        // If editing and date exists, parse it
        if (isEditMode && selectedDate.isNotEmpty()) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate)
                date?.let { calendar.time = it }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
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

        // If editing and time exists, parse it
        if (isEditMode && selectedTime.isNotEmpty()) {
            try {
                val parts = selectedTime.split(":")
                if (parts.size == 2) {
                    calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    calendar.set(Calendar.MINUTE, parts[1].toInt())
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
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

    private fun createEvent() {
        setLoading(true)

        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()
        val userId = auth.currentUser?.uid ?: return
        val userName = auth.currentUser?.displayName ?: "Unknown"

        // First, handle image upload if selected
        if (selectedImageUri != null) {
            uploadImageAndCreateEvent(title, location, description, userId, userName)
        } else {
            // Create event without image
            saveEventToDatabase(title, location, description, userId, userName, null)
        }
    }

    private fun updateEvent() {
        setLoading(true)

        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        // If new image selected, upload it first
        if (selectedImageUri != null) {
            uploadImageAndUpdateEvent(title, location, description)
        } else {
            // Update event with existing image or no image
            updateEventInDatabase(title, location, description, existingImageUrl)
        }
    }

    private fun uploadImageAndCreateEvent(title: String, location: String, description: String, userId: String, userName: String) {
        // Convert image to base64 for simple storage
        // In production, you'd want to use Firebase Storage
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.DEFAULT)

            saveEventToDatabase(title, location, description, userId, userName, base64Image)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to process image", Toast.LENGTH_SHORT).show()
            setLoading(false)
        }
    }

    private fun uploadImageAndUpdateEvent(title: String, location: String, description: String) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.DEFAULT)

            updateEventInDatabase(title, location, description, base64Image)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to process image", Toast.LENGTH_SHORT).show()
            setLoading(false)
        }
    }

    private fun saveEventToDatabase(title: String, location: String, description: String, userId: String, userName: String, imageUrl: String?) {
        val event = hashMapOf(
            "title" to title,
            "date" to selectedDate,
            "time" to selectedTime,
            "location" to location,
            "description" to description,
            "image" to (imageUrl ?: ""),
            "organizerId" to userId,
            "organizerName" to userName,
            "attendees" to mapOf<String, String>(),
            "attendeeCount" to 0,
            "status" to "upcoming",
            "createdAt" to System.currentTimeMillis()
        )

        database.reference.child("events").push().setValue(event)
            .addOnSuccessListener {
                setLoading(false)
                setResult(Activity.RESULT_OK)
                finish()
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Failed to create event: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateEventInDatabase(title: String, location: String, description: String, imageUrl: String?) {
        eventId?.let { id ->
            val updates = hashMapOf<String, Any>(
                "title" to title,
                "date" to selectedDate,
                "time" to selectedTime,
                "location" to location,
                "description" to description
            )

            // Only update image if it changed
            imageUrl?.let {
                updates["image"] = it
            }

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