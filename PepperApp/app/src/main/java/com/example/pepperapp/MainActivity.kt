package com.example.pepperapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import java.util.Locale

private const val TAG = "MainActivity"
private const val PERMISSION_REQUEST_CODE = 1001

// API Configuration - Change these to your server's IP and token
private const val API_BASE_URL = "http://192.168.0.108:5000"
private const val API_AUTH_TOKEN = "supersecretapitoken"  // Replace with your actual Bearer token

/**
 * Main Activity for the Pepper Chatbot App.
 *
 * This activity manages:
 * - Speech recognition (HandsFreeAsr)
 * - Chatbot API communication (ChatApiClient)
 * - Pepper speech synthesis (ChatBotManager)
 * - UI for conversation display
 *
 * Flow:
 * 1. User speaks → ASR transcribes
 * 2. Transcription → API → Bot response
 * 3. Bot response → Pepper speaks
 * 4. Repeat
 */
class MainActivity : ComponentActivity(),
    RobotLifecycleCallbacks,
    HandsFreeAsr.Listener,
    ChatBotManager.Listener {

    // UI Components
    private lateinit var statusTextView: TextView
    private lateinit var stateIndicator: View
    private lateinit var volumeIndicator: ProgressBar
    private lateinit var conversationScrollView: ScrollView
    private lateinit var conversationTextView: TextView
    private lateinit var currentSpeechTextView: TextView
    private lateinit var clearButton: Button

    // Robot state
    private var qiContext: QiContext? = null
    private var hasRobotFocus = false

    // Components
    private var handsFreeAsr: HandsFreeAsr? = null
    private var chatBotManager: ChatBotManager? = null
    private val apiClient = ChatApiClient(API_BASE_URL, API_AUTH_TOKEN)

    // Activity visibility state
    private var isActivityVisible = false

    // Permission state
    private var hasAudioPermission = false

    // Conversation display
    private val conversationBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        initializeViews()

        // Set initial state
        updateStateIndicator(ChatState.INITIALIZING)

        // Check audio permission
        checkAndRequestAudioPermission()

        // Initialize components
        initHandsFreeAsr()
        initChatBotManager()

        // Register with QiSDK for robot focus
        QiSDK.register(this, this)

        Log.i(TAG, "MainActivity created")
    }

    private fun initializeViews() {
        statusTextView = findViewById(R.id.statusTextView)
        stateIndicator = findViewById(R.id.stateIndicator)
        volumeIndicator = findViewById(R.id.volumeIndicator)
        conversationScrollView = findViewById(R.id.conversationScrollView)
        conversationTextView = findViewById(R.id.conversationTextView)
        currentSpeechTextView = findViewById(R.id.currentSpeechTextView)
        clearButton = findViewById(R.id.clearButton)

        // Set up exit button (was clear button)
        clearButton.setOnClickListener {
            exitApp()
        }

        // Initial UI state
        updateStateIndicator(ChatState.INITIALIZING)
        conversationTextView.text = ""
        currentSpeechTextView.text = getString(R.string.hint_speak_now)
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume - Activity becoming visible")

        isActivityVisible = true
        tryStartListening()
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause - Activity losing visibility")

        isActivityVisible = false
        stopListeningCompletely()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy - Cleaning up resources")

        handsFreeAsr?.destroy()
        handsFreeAsr = null

        chatBotManager?.destroy()
        chatBotManager = null

        QiSDK.unregister(this, this)

        super.onDestroy()
    }

    // Permission Handling

    private fun checkAndRequestAudioPermission() {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            Log.i(TAG, "Requesting audio permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            Log.i(TAG, "Audio permission already granted")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Audio permission granted by user")
                hasAudioPermission = true
                tryStartListening()
            } else {
                Log.e(TAG, "Audio permission denied by user")
                hasAudioPermission = false
                statusTextView.text = getString(R.string.status_permission_denied)
                updateStateIndicator(ChatState.ERROR)
            }
        }
    }

    // Component Initialization

    private fun initHandsFreeAsr() {
        handsFreeAsr = HandsFreeAsr(this, this).apply {
//            setLanguage(Locale.ITALIAN)
            setLanguage(Locale.ENGLISH)
        }

        if (handsFreeAsr?.isRecognitionAvailable() != true) {
            Log.e(TAG, "Speech recognition not available on this device")
            statusTextView.text = getString(R.string.status_asr_not_available)
        }
    }

    private fun initChatBotManager() {
        chatBotManager = ChatBotManager(apiClient, this)
    }

    // Listening Control

    private fun tryStartListening() {
        Log.d(TAG, "tryStartListening: visible=$isActivityVisible, permission=$hasAudioPermission, focus=$hasRobotFocus")

        if (!isActivityVisible) {
            Log.d(TAG, "Cannot start: activity not visible")
            return
        }

        if (!hasAudioPermission) {
            Log.d(TAG, "Cannot start: no audio permission")
            statusTextView.text = getString(R.string.status_permission_denied)
            return
        }

        if (!hasRobotFocus) {
            Log.d(TAG, "Cannot start: no robot focus")
            statusTextView.text = getString(R.string.status_waiting)
            return
        }

        // Don't start if chatbot is busy (speaking or processing)
        if (chatBotManager?.isBusy() == true) {
            Log.d(TAG, "Cannot start: chatbot is busy")
            return
        }

        Log.i(TAG, "All conditions met, starting listening")
        handsFreeAsr?.startListening()
    }

    private fun stopListeningCompletely() {
        Log.i(TAG, "Stopping listening completely")
        handsFreeAsr?.stopListening()

        runOnUiThread {
            updateStateIndicator(ChatState.IDLE)
            volumeIndicator.progress = 0
        }
    }

    // Robot Lifecycle Callbacks

    override fun onRobotFocusGained(qiContext: QiContext?) {
        this.qiContext = qiContext
        hasRobotFocus = true

        if (qiContext == null) {
            Log.e(TAG, "QiContext is null")
            return
        }

        Log.i(TAG, "Robot focus gained")

        // Set QiContext for ChatBotManager
        chatBotManager?.setQiContext(qiContext)

        runOnUiThread {
            tryStartListening()
        }
    }

    override fun onRobotFocusLost() {
        Log.i(TAG, "Robot focus lost")
        hasRobotFocus = false
        qiContext = null

        chatBotManager?.setQiContext(null)
        chatBotManager?.cancelSpeaking()

        runOnUiThread {
            stopListeningCompletely()
            statusTextView.text = getString(R.string.status_waiting)
        }
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.e(TAG, "Robot focus refused: $reason")
        hasRobotFocus = false

        runOnUiThread {
            statusTextView.text = getString(R.string.status_error, reason ?: "Unknown")
            updateStateIndicator(ChatState.ERROR)
        }
    }

    // HandsFreeAsr.Listener Implementation

    override fun onReadyForSpeech() {
        Log.d(TAG, "ASR: Ready for speech")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_listening)
            currentSpeechTextView.text = getString(R.string.hint_speak_now)
            updateStateIndicator(ChatState.LISTENING)
        }
    }

    override fun onSpeechStarted() {
        Log.d(TAG, "ASR: Speech started")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_speaking)
            updateStateIndicator(ChatState.USER_SPEAKING)
        }
    }

    override fun onSpeechEnded() {
        Log.d(TAG, "ASR: Speech ended")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_processing)
            updateStateIndicator(ChatState.PROCESSING)
        }
    }

    override fun onPartialResult(partialText: String) {
        Log.d(TAG, "ASR: Partial result: $partialText")
        runOnUiThread {
            currentSpeechTextView.text = partialText
        }
    }

    override fun onFinalResult(text: String) {
        Log.i(TAG, "ASR: Final result: $text")
        runOnUiThread {
            currentSpeechTextView.text = text

            // Send to chatbot for processing
            if (text.isNotBlank()) {
                chatBotManager?.processUserMessage(text)
            } else {
                // Resume listening if no text
                tryStartListening()
            }
        }
    }

    override fun onError(error: HandsFreeAsr.AsrError) {
        Log.e(TAG, "ASR: Error: ${error.message}")
        runOnUiThread {
            when {
                error.isPhysiological -> {
                    statusTextView.text = getString(R.string.status_no_speech)
                    // Will auto-restart
                }
                error is HandsFreeAsr.AsrError.PermissionDenied -> {
                    statusTextView.text = getString(R.string.status_permission_denied)
                    updateStateIndicator(ChatState.ERROR)
                }
                error is HandsFreeAsr.AsrError.NotAvailable -> {
                    statusTextView.text = getString(R.string.status_asr_not_available)
                    updateStateIndicator(ChatState.ERROR)
                }
                else -> {
                    statusTextView.text = getString(R.string.status_error, error.message)
                }
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        runOnUiThread {
            val normalized = ((rmsdB + 2) / 12 * 100).toInt().coerceIn(0, 100)
            volumeIndicator.progress = normalized
        }
    }

    // ChatBotManager.Listener Implementation

    override fun onSendingMessage(userMessage: String) {
        Log.d(TAG, "ChatBot: Sending message: $userMessage")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_sending)
            updateStateIndicator(ChatState.PROCESSING)

            // Add user message to conversation display
            addToConversation("You", userMessage)
        }
    }

    override fun onResponseReceived(botReply: String) {
        Log.d(TAG, "ChatBot: Response received: $botReply")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_bot_responding)

            // Add bot response to conversation display
            addToConversation("Pepper", botReply)
        }
    }

    override fun onSpeakingStarted() {
        Log.d(TAG, "ChatBot: Pepper started speaking")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_pepper_speaking)
            updateStateIndicator(ChatState.BOT_SPEAKING)
        }
    }

    override fun onSpeakingFinished() {
        Log.d(TAG, "ChatBot: Pepper finished speaking")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_ready)
            currentSpeechTextView.text = getString(R.string.hint_speak_now)
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "ChatBot: Error: $message")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_error, message)
            updateStateIndicator(ChatState.ERROR)

            // Add error to conversation
            addToConversation("System", "Error: $message")
        }
    }

    override fun onPauseAsrRequested() {
        Log.d(TAG, "ChatBot: Pause ASR requested")
        handsFreeAsr?.stopListening()
    }

    override fun onResumeAsrRequested() {
        Log.d(TAG, "ChatBot: Resume ASR requested")
        runOnUiThread {
            tryStartListening()
        }
    }

    // UI Helpers

    /**
     * Represents the overall chat state for UI updates.
     */
    enum class ChatState {
        INITIALIZING,
        IDLE,
        LISTENING,
        USER_SPEAKING,
        PROCESSING,
        BOT_SPEAKING,
        ERROR
    }

    private fun updateStateIndicator(state: ChatState) {
        val colorRes = when (state) {
            ChatState.INITIALIZING -> R.color.state_idle
            ChatState.IDLE -> R.color.state_idle
            ChatState.LISTENING -> R.color.state_listening
            ChatState.USER_SPEAKING -> R.color.state_speaking
            ChatState.PROCESSING -> R.color.state_processing
            ChatState.BOT_SPEAKING -> R.color.state_bot_speaking
            ChatState.ERROR -> R.color.state_error
        }
        stateIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    private fun addToConversation(speaker: String, message: String) {
        if (conversationBuilder.isNotEmpty()) {
            conversationBuilder.append("\n\n")
        }
        conversationBuilder.append("$speaker: $message")

        conversationTextView.text = conversationBuilder.toString()

        // Scroll to bottom
        conversationScrollView.post {
            conversationScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun exitApp() {
        Log.i(TAG, "Exiting application")

        // Stop listening and release resources
        handsFreeAsr?.stopListening()
        chatBotManager?.cancelSpeaking()

        // Finish the activity
        finishAffinity()
    }
}