package com.student.events.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * EmailService handles sending emails through EmailJS API for event invitations and notifications.
 * Provides reliable email delivery with proper error handling and rate limiting.
 */
class EmailService(private val context: Context) {

    companion object {
        // EmailJS Configuration
        private const val EMAILJS_SERVICE_ID = "Events"
        private const val EMAILJS_TEMPLATE_ID = "template_ai5gxz4"
        private const val EMAILJS_PUBLIC_KEY = "3fN6-9tPDe7k_oSg6"
        private const val EMAILJS_URL = "https://api.emailjs.com/api/v1.0/email/send"

        // Rate limiting to prevent spam
        private const val MIN_EMAIL_INTERVAL = 5000L
        private var lastEmailTime = 0L
    }

    /**
     * Send an email using EmailJS service
     * @param toEmail Recipient email address
     * @param toName Recipient name
     * @param fromName Sender name
     * @param fromEmail Sender email address
     * @param subject Email subject
     * @param message Email content
     * @return Pair of success status and optional error message
     */
    suspend fun sendEmail(
        toEmail: String,
        toName: String,
        fromName: String,
        fromEmail: String,
        subject: String,
        message: String
    ): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                // Check rate limiting
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastEmailTime < MIN_EMAIL_INTERVAL) {
                    val waitTime = MIN_EMAIL_INTERVAL - (currentTime - lastEmailTime)
                    return@withContext Pair(false, "Please wait ${waitTime}ms before sending another email")
                }
                lastEmailTime = currentTime

                // Validate email inputs
                if (!isValidEmail(toEmail)) {
                    return@withContext Pair(false, "Invalid recipient email format: $toEmail")
                }

                if (!isValidEmail(fromEmail)) {
                    return@withContext Pair(false, "Invalid sender email format: $fromEmail")
                }

                if (toName.isBlank() || fromName.isBlank() || subject.isBlank() || message.isBlank()) {
                    return@withContext Pair(false, "Missing required fields: name, subject, or message")
                }

                // Prepare email payload
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val templateParams = JSONObject().apply {
                    put("to_email", toEmail)
                    put("to_name", toName)
                    put("from_name", fromName)
                    put("from_email", fromEmail)
                    put("subject", subject)
                    put("message", message)
                    put("reply_to", fromEmail)
                    put("app_name", "Events App")
                    put("timestamp", timestamp)
                    put("app_version", "1.0")
                    put("platform", "Android")
                }

                val emailData = JSONObject().apply {
                    put("service_id", EMAILJS_SERVICE_ID)
                    put("template_id", EMAILJS_TEMPLATE_ID)
                    put("user_id", EMAILJS_PUBLIC_KEY)
                    put("template_params", templateParams)
                }

                // Send HTTP request to EmailJS
                val url = URL(EMAILJS_URL)
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("User-Agent", "EventsApp/1.0 (Android)")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "gzip, deflate")
                    doOutput = true
                    doInput = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }

                // Write payload to request
                val payloadString = emailData.toString()
                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payloadString)
                    writer.flush()
                }

                // Process response
                val responseCode = connection.responseCode
                val responseBody = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details available"
                }

                if (responseCode == 200) {
                    Pair(true, "Email sent successfully")
                } else {
                    val errorMsg = parseEmailJSError(responseCode, responseBody)
                    Pair(false, errorMsg)
                }

            } catch (e: java.net.SocketTimeoutException) {
                Pair(false, "Email timeout - check your internet connection")

            } catch (e: java.net.UnknownHostException) {
                Pair(false, "Network error - unable to reach email service")

            } catch (e: java.security.cert.CertificateException) {
                Pair(false, "SSL Certificate error")

            } catch (e: Exception) {
                Pair(false, "Unexpected error: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Parse EmailJS API error responses into user-friendly messages
     */
    private fun parseEmailJSError(responseCode: Int, responseBody: String): String {
        return when (responseCode) {
            400 -> {
                when {
                    responseBody.contains("service", ignoreCase = true) -> "Email service configuration error"
                    responseBody.contains("template", ignoreCase = true) -> "Email template not found"
                    responseBody.contains("user", ignoreCase = true) -> "Invalid email service credentials"
                    else -> "Bad request: Please check email parameters"
                }
            }
            401 -> "Unauthorized: Invalid email service credentials"
            403 -> "Forbidden: Email service account may be suspended or quota exceeded"
            404 -> "Not Found: Email service or template doesn't exist"
            422 -> "Validation Error: Check email parameters"
            429 -> "Rate Limited: Too many emails sent, try again later"
            500 -> "Email Service Error: Try again later"
            503 -> "Email Service Unavailable: Try again later"
            else -> "HTTP $responseCode: Email service error"
        }
    }

    /**
     * Validate email address format
     */
    private fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false

        // Check for common invalid patterns
        if (email.startsWith(".") || email.endsWith(".") || email.contains("..") ||
            email.contains(".@") || email.contains("@.")) {
            return false
        }

        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    /**
     * Send a contact message email with formatted content
     * @param recipientEmail Email of the person receiving the message
     * @param recipientName Name of the recipient
     * @param senderName Name of the person sending the message
     * @param senderEmail Email of the sender
     * @param messageContent The actual message content
     * @param eventContext Optional event context for the message
     * @return Pair of success status and optional error message
     */
    suspend fun sendContactMessage(
        recipientEmail: String,
        recipientName: String,
        senderName: String,
        senderEmail: String,
        messageContent: String,
        eventContext: String? = null
    ): Pair<Boolean, String?> {

        val subject = if (eventContext != null) {
            "Message about your event: $eventContext"
        } else {
            "Message from $senderName via Events App"
        }

        val formattedMessage = buildString {
            appendLine("You received a new message through the Events app:")
            appendLine()
            appendLine("From: $senderName")
            appendLine("Email: $senderEmail")
            appendLine()
            appendLine("Message:")
            appendLine(messageContent)
            appendLine()
            if (eventContext != null) {
                appendLine("Event: $eventContext")
                appendLine()
            }
            appendLine("---")
            appendLine("This message was sent through the Events app.")
            appendLine("You can reply directly to this email to respond to $senderName.")
        }

        return sendEmail(
            toEmail = recipientEmail,
            toName = recipientName,
            fromName = "Events App on behalf of $senderName",
            fromEmail = senderEmail,
            subject = subject,
            message = formattedMessage
        )
    }

    /**
     * Send an event invitation email with event details
     * @param recipientEmail Email of the person being invited
     * @param recipientName Name of the invitee
     * @param organizerName Name of the event organizer
     * @param organizerEmail Email of the organizer
     * @param eventTitle Title of the event
     * @param eventDate Formatted date of the event
     * @param eventLocation Location of the event
     * @param eventDescription Description of the event
     * @return Pair of success status and optional error message
     */
    suspend fun sendEventInvitation(
        recipientEmail: String,
        recipientName: String,
        organizerName: String,
        organizerEmail: String,
        eventTitle: String,
        eventDate: String,
        eventLocation: String,
        eventDescription: String
    ): Pair<Boolean, String?> {

        val subject = "You're invited to: $eventTitle"

        val formattedMessage = buildString {
            appendLine("You've been invited to an event!")
            appendLine()
            appendLine("Event: $eventTitle")
            appendLine("Date: $eventDate")
            appendLine("Location: $eventLocation")
            appendLine()
            appendLine("Description:")
            appendLine(eventDescription)
            appendLine()
            appendLine("Organized by: $organizerName ($organizerEmail)")
            appendLine()
            appendLine("---")
            appendLine("Open the Events app to RSVP and see more details.")
            appendLine("You can reply to this email to contact the organizer directly.")
        }

        return sendEmail(
            toEmail = recipientEmail,
            toName = recipientName,
            fromName = "Events - $organizerName",
            fromEmail = organizerEmail,
            subject = subject,
            message = formattedMessage
        )
    }

    /**
     * Test EmailJS configuration by sending a test email
     * @param testRecipientEmail Email address to send test email to
     * @return Pair of success status and optional error message
     */
    suspend fun testEmailConfiguration(testRecipientEmail: String): Pair<Boolean, String?> {
        val testMessage = buildString {
            appendLine("EmailJS Configuration Test")
            appendLine()
            appendLine("If you receive this email, your EmailJS configuration is working correctly!")
            appendLine()
            appendLine("Test Details:")
            appendLine("- Service ID: $EMAILJS_SERVICE_ID")
            appendLine("- Template ID: $EMAILJS_TEMPLATE_ID")
            appendLine("- Test Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("Next steps:")
            appendLine("1. EmailJS configuration is correct")
            appendLine("2. Check that emails aren't going to spam folder")
            appendLine("3. Verify template parameters match your code")
            appendLine()
            appendLine("---")
            appendLine("Events App - Email Test")
        }

        return sendEmail(
            toEmail = testRecipientEmail,
            toName = "Test Recipient",
            fromName = "Events App (Test)",
            fromEmail = testRecipientEmail,
            subject = "EmailJS Configuration Test - Events App",
            message = testMessage
        )
    }

    /**
     * Get current EmailJS configuration information for debugging
     * @return Configuration details as formatted string
     */
    fun getConfigurationInfo(): String {
        return buildString {
            appendLine("EmailJS Configuration:")
            appendLine("Service ID: $EMAILJS_SERVICE_ID")
            appendLine("Template ID: $EMAILJS_TEMPLATE_ID")
            appendLine("Public Key: ${EMAILJS_PUBLIC_KEY.take(8)}...${EMAILJS_PUBLIC_KEY.takeLast(4)}")
            appendLine("API URL: $EMAILJS_URL")
            appendLine("Last Email: ${if (lastEmailTime > 0) Date(lastEmailTime) else "Never"}")
        }
    }
}