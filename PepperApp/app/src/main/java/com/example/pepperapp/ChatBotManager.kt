package com.example.pepperapp

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.`object`.conversation.Say
import com.aldebaran.qi.sdk.`object`.locale.Language
import com.aldebaran.qi.sdk.`object`.locale.Locale
import com.aldebaran.qi.sdk.`object`.locale.Region
import kotlinx.coroutines.*

class ChatBotManager(
    private val apiClient: ChatApiClient,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "ChatBotManager"
    }

    interface Listener {
        fun onSendingMessage(userMessage: String)

        fun onResponseReceived(botReply: String)

        fun onSpeakingStarted()

        fun onSpeakingFinished()

        fun onError(message: String)

        fun onPauseAsrRequested()

        fun onResumeAsrRequested()
    }

    enum class State {
        IDLE,           // Waiting for user input
        PROCESSING,     // Sending to API and waiting for response
        SPEAKING        // Pepper is speaking the response
    }

    // State management
    private var state: State = State.IDLE
    private var sessionId: String? = null
    private var qiContext: QiContext? = null

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Current speaking job (for cancellation)
    private var speakingJob: Job? = null

    // Conversation history for UI display
    data class Message(
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _conversationHistory = mutableListOf<Message>()
    val conversationHistory: List<Message> get() = _conversationHistory.toList()

    fun setQiContext(context: QiContext?) {
        this.qiContext = context
        Log.d(TAG, "QiContext set: ${context != null}")
    }

    fun getState(): State = state

    fun isBusy(): Boolean = state != State.IDLE

    fun processUserMessage(userMessage: String) {
        if (state != State.IDLE) {
            Log.w(TAG, "Chatbot is busy, ignoring message: $userMessage")
            return
        }

        if (userMessage.isBlank()) {
            Log.w(TAG, "Empty message, ignoring")
            return
        }

        Log.i(TAG, "Processing user message: $userMessage")

        // Add to conversation history
        _conversationHistory.add(Message(userMessage, isUser = true))

        // Update state and notify
        state = State.PROCESSING
        listener.onSendingMessage(userMessage)
        listener.onPauseAsrRequested()

        // Send to API
        scope.launch {
            try {
                val response = apiClient.sendMessage(userMessage, sessionId)

                when (response) {
                    is ChatApiClient.ChatResponse.Success -> {
                        // Update session ID for conversation continuity
                        sessionId = response.sessionId

                        // Add bot response to history
                        _conversationHistory.add(Message(response.reply, isUser = false))

                        // Notify and speak
                        listener.onResponseReceived(response.reply)
                        speakResponse(response.reply)
                    }
                    is ChatApiClient.ChatResponse.Error -> {
                        Log.e(TAG, "API error: ${response.message}")
                        state = State.IDLE
                        listener.onError(response.message)
                        listener.onResumeAsrRequested()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                state = State.IDLE
                listener.onError("Error: ${e.message}")
                listener.onResumeAsrRequested()
            }
        }
    }

    private fun speakResponse(text: String) {
        val context = qiContext
        if (context == null) {
            Log.e(TAG, "QiContext is null, cannot speak")
            state = State.IDLE
            listener.onError("Robot not connected")
            listener.onResumeAsrRequested()
            return
        }

        state = State.SPEAKING
        listener.onSpeakingStarted()

        speakingJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Build the Say action
                    val say: Say = SayBuilder.with(context)
                        .withText(text)
                        .withLocale(Locale(Language.ENGLISH, Region.UNITED_STATES))
                        .build()

                    Log.d(TAG, "Starting to speak: $text")

                    // Run synchronously (blocking) in IO dispatcher
                    say.run()

                    Log.d(TAG, "Finished speaking")
                }

                // Back on Main thread
                state = State.IDLE
                listener.onSpeakingFinished()
                listener.onResumeAsrRequested()

            } catch (e: CancellationException) {
                Log.d(TAG, "Speaking was cancelled")
                state = State.IDLE
                listener.onResumeAsrRequested()
            } catch (e: Exception) {
                Log.e(TAG, "Error during speech", e)
                state = State.IDLE
                listener.onError("Speech error: ${e.message}")
                listener.onResumeAsrRequested()
            }
        }
    }

    fun cancelSpeaking() {
        speakingJob?.cancel()
        speakingJob = null
    }

    fun clearConversation() {
        _conversationHistory.clear()
        sessionId = null
        Log.i(TAG, "Conversation cleared")
    }

    fun destroy() {
        cancelSpeaking()
        scope.cancel()
        qiContext = null
        Log.d(TAG, "ChatBotManager destroyed")
    }
}

