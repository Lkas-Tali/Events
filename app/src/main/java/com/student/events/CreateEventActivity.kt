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

        isEditMode = intent.getBooleanExtra("editMode", false)
        if (isEditMode) {
            setupEditMode()
        }

        setupViews()
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
        binding.backButton.setOnClickListener { finish() }
        binding.dateInput.setOnClickListener { showDatePicker() }
        binding.timeInput.setOnClickListener { showTimePicker() }
        binding.imageUploadArea.setOnClickListener { selectImage() }
        binding.cancelButton.setOnClickListener { finish() }
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
        // (Input validation remains the same)
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

    // --- FIX STARTS HERE ---
    // The following functions `createEvent`, `updateEvent`, `saveEventToDatabase`,
    // and `updateEventInDatabase` have been updated to save the data in the correct
    // structure that matches your `Event.kt` model. This prevents data corruption.

    private fun createEvent() {
        setLoading(true)
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()
        val userId = auth.currentUser?.uid ?: return
        val userName = auth.currentUser?.displayName ?: "Unknown"

        if (selectedImageUri != null) {
            uploadImageAndSaveEvent(title, location, description, userId, userName)
        } else {
            saveEventToDatabase(title, location, description, userId, userName, null)
        }
    }

    private fun updateEvent() {
        setLoading(true)
        val title = binding.titleInput.text.toString().trim()
        val location = binding.locationInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        if (selectedImageUri != null) {
            uploadImageAndSaveEvent(title, location, description, null, null, isUpdate = true)
        } else {
            updateEventInDatabase(title, location, description, existingImageUrl)
        }
    }

    private fun uploadImageAndSaveEvent(title: String, location: String, description: String, userId: String?, userName: String?, isUpdate: Boolean = false) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, selectedImageUri)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.DEFAULT)

            if (isUpdate) {
                updateEventInDatabase(title, location, description, base64Image)
            } else {
                saveEventToDatabase(title, location, description, userId!!, userName!!, base64Image)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to process image", Toast.LENGTH_SHORT).show()
            setLoading(false)
        }
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

    private fun saveEventToDatabase(title: String, location: String, description: String, userId: String, userName: String, imageUrl: String?) {
        val event = hashMapOf(
            "title" to title,
            "location" to location,
            "description" to description,
            "imageUrl" to (imageUrl ?: ""),
            "organizer" to mapOf("uid" to userId, "fullName" to userName),
            "attendees" to mapOf<String, Any>(),
            "attendeesCount" to 0,
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
                Toast.makeText(this, "Failed to create event: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateEventInDatabase(title: String, location: String, description: String, imageUrl: String?) {
        eventId?.let { id ->
            val updates = hashMapOf<String, Any?>(
                "title" to title,
                "location" to location,
                "description" to description,
                "dateTime" to getCombinedDateTime()
            )

            imageUrl?.let {
                updates["imageUrl"] = it
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

    // --- FIX ENDS HERE ---

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
