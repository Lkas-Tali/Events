package com.student.events.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced EmailService with comprehensive logging and debugging
 * This handles sending emails using EmailJS service with detailed error reporting
 */
class EmailService(private val context: Context) {

    companion object {
        private const val TAG = "EmailService"

        // EmailJS Configuration - VERIFY these values in your EmailJS dashboard
        private const val EMAILJS_SERVICE_ID = "Events"
        private const val EMAILJS_TEMPLATE_ID = "template_ai5gxz4"
        private const val EMAILJS_PUBLIC_KEY = "3fN6-9tPDe7k_oSg6"
        private const val EMAILJS_URL = "https://api.emailjs.com/api/v1.0/email/send"

        // Rate limiting
        private const val MIN_EMAIL_INTERVAL = 5000L // 5 seconds between emails
        private var lastEmailTime = 0L
    }

    /**
     * Send an email using EmailJS with comprehensive error handling and logging
     */
    suspend fun sendEmail(
        toEmail: String,
        toName: String,
        fromName: String,
        fromEmail: String,
        subject: String,
        message: String,
        callback: (Boolean, String?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=".repeat(60))
                Log.d(TAG, "🚀 STARTING EMAIL SEND PROCESS")
                Log.d(TAG, "=".repeat(60))
                Log.d(TAG, "📧 Email Details:")
                Log.d(TAG, "   To: $toEmail ($toName)")
                Log.d(TAG, "   From: $fromName <$fromEmail>")
                Log.d(TAG, "   Subject: $subject")
                Log.d(TAG, "   Message Length: ${message.length} characters")
                Log.d(TAG, "📡 EmailJS Configuration:")
                Log.d(TAG, "   Service ID: $EMAILJS_SERVICE_ID")
                Log.d(TAG, "   Template ID: $EMAILJS_TEMPLATE_ID")
                Log.d(TAG, "   Public Key: ${EMAILJS_PUBLIC_KEY.take(8)}...${EMAILJS_PUBLIC_KEY.takeLast(4)}")
                Log.d(TAG, "   API URL: $EMAILJS_URL")

                // Rate limiting check
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastEmailTime < MIN_EMAIL_INTERVAL) {
                    val waitTime = MIN_EMAIL_INTERVAL - (currentTime - lastEmailTime)
                    val error = "Rate limited: Please wait ${waitTime}ms before sending another email"
                    Log.w(TAG, "⏱️ $error")
                    withContext(Dispatchers.Main) { callback(false, error) }
                    return@withContext
                }
                lastEmailTime = currentTime

                // Validate inputs
                Log.d(TAG, "🔍 Validating email inputs...")
                if (!isValidEmail(toEmail)) {
                    val error = "Invalid recipient email format: $toEmail"
                    Log.e(TAG, "❌ $error")
                    withContext(Dispatchers.Main) { callback(false, error) }
                    return@withContext
                }

                if (!isValidEmail(fromEmail)) {
                    val error = "Invalid sender email format: $fromEmail"
                    Log.e(TAG, "❌ $error")
                    withContext(Dispatchers.Main) { callback(false, error) }
                    return@withContext
                }

                if (toName.isEmpty() || fromName.isEmpty() || subject.isEmpty() || message.isEmpty()) {
                    val error = "Missing required fields: name, subject, or message"
                    Log.e(TAG, "❌ $error")
                    withContext(Dispatchers.Main) { callback(false, error) }
                    return@withContext
                }

                Log.d(TAG, "✅ Input validation passed")

                // Create the email payload
                Log.d(TAG, "📦 Building email payload...")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val templateParams = JSONObject().apply {
                    put("to_email", toEmail)
                    put("to_name", toName)
                    put("from_name", fromName)
                    put("from_email", fromEmail)
                    put("subject", subject)
                    put("message", message)
                    put("reply_to", fromEmail)

                    // Additional parameters for debugging
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

                Log.d(TAG, "📋 Email payload created:")
                Log.d(TAG, "   Service ID: ${emailData.getString("service_id")}")
                Log.d(TAG, "   Template ID: ${emailData.getString("template_id")}")
                Log.d(TAG, "   User ID: ${emailData.getString("user_id")}")
                Log.d(TAG, "   Template params count: ${templateParams.length()}")

                // Log template parameters (sensitive data masked)
                Log.d(TAG, "📝 Template Parameters:")
                templateParams.keys().forEach { key ->
                    val value = templateParams.getString(key)
                    val maskedValue = when (key) {
                        "to_email", "from_email" -> maskEmail(value)
                        "message" -> "${value.take(50)}${if (value.length > 50) "..." else ""}"
                        else -> value
                    }
                    Log.d(TAG, "   $key: $maskedValue")
                }

                // Send HTTP POST request to EmailJS
                Log.d(TAG, "🌐 Sending HTTP request to EmailJS...")
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
                    connectTimeout = 15000 // 15 seconds
                    readTimeout = 30000    // 30 seconds
                }

                Log.d(TAG, "🔧 HTTP Connection configured:")
                Log.d(TAG, "   Method: ${connection.requestMethod}")
                Log.d(TAG, "   Content-Type: ${connection.getRequestProperty("Content-Type")}")
                Log.d(TAG, "   User-Agent: ${connection.getRequestProperty("User-Agent")}")
                Log.d(TAG, "   Connect Timeout: ${connection.connectTimeout}ms")
                Log.d(TAG, "   Read Timeout: ${connection.readTimeout}ms")

                // Write the payload
                Log.d(TAG, "📤 Writing payload to request...")
                val payloadString = emailData.toString()
                Log.d(TAG, "   Payload size: ${payloadString.length} bytes")

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payloadString)
                    writer.flush()
                    Log.d(TAG, "✅ Payload written successfully")
                }

                // Check response
                Log.d(TAG, "📥 Reading response...")
                val responseCode = connection.responseCode
                val responseHeaders = connection.headerFields

                Log.d(TAG, "📊 HTTP Response:")
                Log.d(TAG, "   Response Code: $responseCode")
                Log.d(TAG, "   Response Message: ${connection.responseMessage}")

                // Log response headers
                Log.d(TAG, "   Response Headers:")
                responseHeaders.forEach { (key, values) ->
                    Log.d(TAG, "     $key: ${values.joinToString(", ")}")
                }

                val responseBody = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().use {
                        val body = it.readText()
                        Log.d(TAG, "✅ Response Body: $body")
                        body
                    }
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use {
                        val body = it.readText()
                        Log.e(TAG, "❌ Error Response Body: $body")
                        body
                    } ?: "No error details available"
                    errorBody
                }

                withContext(Dispatchers.Main) {
                    if (responseCode == 200) {
                        Log.i(TAG, "🎉 EMAIL SENT SUCCESSFULLY!")
                        Log.i(TAG, "   Recipient: $toEmail")
                        Log.i(TAG, "   Subject: $subject")
                        Log.i(TAG, "   Timestamp: $timestamp")
                        Log.d(TAG, "=".repeat(60))
                        callback(true, "Email sent successfully")
                    } else {
                        val errorMsg = parseEmailJSError(responseCode, responseBody)
                        Log.e(TAG, "💥 EMAIL SEND FAILED!")
                        Log.e(TAG, "   Error Code: $responseCode")
                        Log.e(TAG, "   Error Message: $errorMsg")
                        Log.e(TAG, "   Recipient: $toEmail")
                        Log.e(TAG, "   Subject: $subject")
                        Log.d(TAG, "=".repeat(60))
                        callback(false, errorMsg)
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                val errorMsg = "Email timeout - check your internet connection"
                Log.e(TAG, "⏱️ TIMEOUT ERROR: ${e.message}", e)
                Log.e(TAG, "   This usually indicates:")
                Log.e(TAG, "   - Slow or unstable internet connection")
                Log.e(TAG, "   - EmailJS servers are slow to respond")
                Log.e(TAG, "   - Network firewall blocking the request")
                withContext(Dispatchers.Main) { callback(false, errorMsg) }

            } catch (e: java.net.UnknownHostException) {
                val errorMsg = "Network error - unable to reach EmailJS servers"
                Log.e(TAG, "🌐 NETWORK ERROR: ${e.message}", e)
                Log.e(TAG, "   This usually indicates:")
                Log.e(TAG, "   - No internet connection")
                Log.e(TAG, "   - DNS resolution failed")
                Log.e(TAG, "   - EmailJS servers are down")
                withContext(Dispatchers.Main) { callback(false, errorMsg) }

            } catch (e: java.security.cert.CertificateException) {
                val errorMsg = "SSL Certificate error"
                Log.e(TAG, "🔒 SSL ERROR: ${e.message}", e)
                Log.e(TAG, "   This usually indicates:")
                Log.e(TAG, "   - Device time/date is incorrect")
                Log.e(TAG, "   - Certificate validation failed")
                withContext(Dispatchers.Main) { callback(false, errorMsg) }

            } catch (e: Exception) {
                val errorMsg = "Unexpected error: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, "💥 UNEXPECTED ERROR: ${e.message}", e)
                Log.e(TAG, "   Exception Type: ${e.javaClass.name}")
                Log.e(TAG, "   Stack trace:")
                e.stackTrace.take(5).forEach {
                    Log.e(TAG, "     at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
                }
                withContext(Dispatchers.Main) { callback(false, errorMsg) }
            }
        }
    }

    /**
     * Parse EmailJS specific error messages
     */
    private fun parseEmailJSError(responseCode: Int, responseBody: String): String {
        return when (responseCode) {
            400 -> {
                if (responseBody.contains("service", ignoreCase = true)) {
                    "Invalid EmailJS Service ID: '$EMAILJS_SERVICE_ID' not found"
                } else if (responseBody.contains("template", ignoreCase = true)) {
                    "Invalid EmailJS Template ID: '$EMAILJS_TEMPLATE_ID' not found"
                } else if (responseBody.contains("user", ignoreCase = true)) {
                    "Invalid EmailJS Public Key: '$EMAILJS_PUBLIC_KEY' not valid"
                } else {
                    "Bad Request: $responseBody"
                }
            }
            401 -> "Unauthorized: Invalid EmailJS credentials"
            403 -> "Forbidden: EmailJS account may be suspended or quota exceeded"
            404 -> "Not Found: EmailJS service/template doesn't exist"
            422 -> "Validation Error: Check template parameters - $responseBody"
            429 -> "Rate Limited: Too many emails sent, try again later"
            500 -> "EmailJS Server Error: Try again later"
            503 -> "EmailJS Service Unavailable: Try again later"
            else -> "HTTP $responseCode: $responseBody"
        }
    }

    /**
     * Validate email format
     */
    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() &&
                email.contains("@") &&
                email.contains(".")
    }

    /**
     * Mask email for logging privacy
     */
    private fun maskEmail(email: String): String {
        val parts = email.split("@")
        return if (parts.size == 2) {
            val username = parts[0]
            val domain = parts[1]
            val maskedUsername = if (username.length > 2) {
                "${username.take(2)}${"*".repeat(username.length - 2)}"
            } else {
                "*".repeat(username.length)
            }
            "$maskedUsername@$domain"
        } else {
            email.take(3) + "*".repeat(maxOf(0, email.length - 3))
        }
    }

    /**
     * Send a contact message email with pre-formatted content
     */
    suspend fun sendContactMessage(
        recipientEmail: String,
        recipientName: String,
        senderName: String,
        senderEmail: String,
        messageContent: String,
        eventContext: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        Log.d(TAG, "📬 Sending contact message...")
        Log.d(TAG, "   From: $senderName")
        Log.d(TAG, "   To: $recipientName")
        Log.d(TAG, "   Context: $eventContext")

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

        sendEmail(
            toEmail = recipientEmail,
            toName = recipientName,
            fromName = "Events App on behalf of $senderName",
            fromEmail = senderEmail,
            subject = subject,
            message = formattedMessage,
            callback = callback
        )
    }

    /**
     * Send an event invitation email
     */
    suspend fun sendEventInvitation(
        recipientEmail: String,
        recipientName: String,
        organizerName: String,
        organizerEmail: String,
        eventTitle: String,
        eventDate: String,
        eventLocation: String,
        eventDescription: String,
        callback: (Boolean, String?) -> Unit
    ) {
        Log.d(TAG, "🎉 Sending event invitation...")
        Log.d(TAG, "   Event: $eventTitle")
        Log.d(TAG, "   Organizer: $organizerName")
        Log.d(TAG, "   Invitee: $recipientName")

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

        sendEmail(
            toEmail = recipientEmail,
            toName = recipientName,
            fromName = "Events - $organizerName",
            fromEmail = organizerEmail,
            subject = subject,
            message = formattedMessage,
            callback = callback
        )
    }

    /**
     * Test EmailJS configuration with detailed diagnostics
     */
    suspend fun testEmailConfiguration(
        testRecipientEmail: String,
        callback: (Boolean, String?) -> Unit
    ) {
        Log.i(TAG, "🧪 STARTING EMAILJS CONFIGURATION TEST")
        Log.i(TAG, "=".repeat(60))
        Log.i(TAG, "🎯 Test Details:")
        Log.i(TAG, "   Test Email: ${maskEmail(testRecipientEmail)}")
        Log.i(TAG, "   Service ID: $EMAILJS_SERVICE_ID")
        Log.i(TAG, "   Template ID: $EMAILJS_TEMPLATE_ID")
        Log.i(TAG, "   Public Key: ${EMAILJS_PUBLIC_KEY.take(8)}...${EMAILJS_PUBLIC_KEY.takeLast(4)}")

        val testMessage = buildString {
            appendLine("🧪 EmailJS Configuration Test")
            appendLine()
            appendLine("If you receive this email, your EmailJS configuration is working correctly!")
            appendLine()
            appendLine("Test Details:")
            appendLine("- Service ID: $EMAILJS_SERVICE_ID")
            appendLine("- Template ID: $EMAILJS_TEMPLATE_ID")
            appendLine("- Test Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("Next steps:")
            appendLine("1. ✅ EmailJS configuration is correct")
            appendLine("2. Check that emails aren't going to spam folder")
            appendLine("3. Verify template parameters match your code")
            appendLine()
            appendLine("---")
            appendLine("Events App - Email Test")
        }

        sendEmail(
            toEmail = testRecipientEmail,
            toName = "Test Recipient",
            fromName = "Events App (Test)",
            fromEmail = testRecipientEmail,
            subject = "🧪 EmailJS Configuration Test - Events App",
            message = testMessage,
            callback = { success, message ->
                if (success) {
                    Log.i(TAG, "✅ EMAILJS TEST SUCCESSFUL!")
                    Log.i(TAG, "   The configuration is working correctly")
                    Log.i(TAG, "   Check the recipient's inbox (and spam folder)")
                } else {
                    Log.e(TAG, "❌ EMAILJS TEST FAILED!")
                    Log.e(TAG, "   Error: $message")
                    Log.e(TAG, "   Check the logs above for detailed error information")
                }
                Log.i(TAG, "=".repeat(60))
                callback(success, message)
            }
        )
    }

    /**
     * Get configuration status for debugging
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