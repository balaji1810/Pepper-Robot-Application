package com.example.pepperapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ChatApiClient - HTTP client for communicating with the Flask chatbot backend.
 *
 * Sends user messages to the /api/chat endpoint and receives AI responses.
 */
class ChatApiClient(
    private val baseUrl: String = "http://192.168.0.108:5000",
    private val authToken: String = "supersecretapitoken"
) {
    companion object {
        private const val TAG = "ChatApiClient"
        private const val TIMEOUT_MS = 30000 // 30 seconds
    }

    /**
     * Response from the chat API.
     */
    sealed class ChatResponse {
        data class Success(
            val reply: String,
            val sessionId: String
        ) : ChatResponse()

        data class Error(
            val message: String,
            val code: Int = -1
        ) : ChatResponse()
    }

    /**
     * Send a message to the chatbot API.
     *
     * @param text The user's message
     * @param sessionId Optional session ID for conversation continuity
     * @return ChatResponse with either the reply or an error
     */
    suspend fun sendMessage(text: String, sessionId: String? = null): ChatResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/api/chat")
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $authToken")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    doInput = true
                }

                // Build JSON request body
                val requestBody = JSONObject().apply {
                    put("text", text)
                    if (!sessionId.isNullOrBlank()) {
                        put("session_id", sessionId)
                    }
                }

                Log.d(TAG, "Sending request: $requestBody")

                // Write request body
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                // Read response
                val responseCode = connection.responseCode
                val responseBody = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use(BufferedReader::readText)
                } else {
                    connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                }

                Log.d(TAG, "Response ($responseCode): $responseBody")

                connection.disconnect()

                // Parse response
                parseResponse(responseCode, responseBody)

            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Request timeout", e)
                ChatResponse.Error("Request timed out. Please try again.", 408)
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection failed", e)
                ChatResponse.Error("Cannot connect to server. Please check your connection.", 503)
            } catch (e: Exception) {
                Log.e(TAG, "Request failed", e)
                ChatResponse.Error("Network error: ${e.message}", -1)
            }
        }
    }

    /**
     * Parse the JSON response from the API.
     */
    private fun parseResponse(responseCode: Int, responseBody: String): ChatResponse {
        return try {
            val json = JSONObject(responseBody)
            val ok = json.optBoolean("ok", false)

            if (ok && responseCode in 200..299) {
                val reply = json.optString("reply", "")
                val sessionId = json.optString("session_id", "")

                if (reply.isNotBlank()) {
                    ChatResponse.Success(reply, sessionId)
                } else {
                    ChatResponse.Error("Empty response from server", responseCode)
                }
            } else {
                val error = json.optString("error", "Unknown error")
                ChatResponse.Error(error, responseCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response", e)
            ChatResponse.Error("Invalid response from server", responseCode)
        }
    }
}

