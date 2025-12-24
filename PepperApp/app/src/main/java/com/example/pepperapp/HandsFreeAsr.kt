package com.example.pepperapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * HandsFreeAsr - Hands-Free Automatic Speech Recognition Component
 *
 * This class encapsulates all speech recognition logic using Android's SpeechRecognizer.
 * It provides a simple interface for the Activity/Fragment to start/stop listening
 * and receive speech events without knowing the internal implementation details.
 */
class HandsFreeAsr(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "HandsFreeAsr"

        // Delay before restarting listening after results or errors
        private const val RESTART_DELAY_MS = 300L

        // Delay before restarting after physiological errors
        private const val ERROR_RESTART_DELAY_MS = 500L

        // RMS threshold for soft VAD (detecting speech via volume)
        private const val RMS_SPEECH_THRESHOLD = 3.0f

        // Minimum RMS readings above threshold to consider speech started
        private const val RMS_SPEECH_COUNT_THRESHOLD = 3
    }

    /**
     * Listener interface for ASR events.
     * The Activity/Fragment implements this to receive speech recognition events.
     */
    interface Listener {
        fun onReadyForSpeech()

        fun onSpeechStarted()

        fun onSpeechEnded()

        fun onPartialResult(partialText: String)

        fun onFinalResult(text: String)

        fun onError(error: AsrError)

        fun onRmsChanged(rmsdB: Float)
    }

    /**
     * Represents the internal state of the ASR.
     */
    enum class State {
        IDLE,           // Not listening, microphone inactive
        LISTENING,      // Microphone active, waiting for speech
        SPEECH_ACTIVE,  // User is currently speaking
        PROCESSING      // Processing the utterance (after speech ends)
    }

    /**
     * Categories of ASR errors.
     */
    sealed class AsrError(val message: String) {
        /** Physiological errors - can automatically restart */
        class NoSpeech : AsrError("No speech detected")
        class NoMatch : AsrError("Speech not recognized")
        class Timeout : AsrError("Speech timeout")

        /** Blocking errors - require user intervention or cannot recover */
        class PermissionDenied : AsrError("Microphone permission denied")
        class AudioError : AsrError("Audio recording error")
        class NetworkError : AsrError("Network error")
        class RecognizerBusy : AsrError("Recognizer is busy")
        class ServiceError : AsrError("Speech recognition service error")
        class NotAvailable : AsrError("Speech recognition not available")
        class Unknown(code: Int) : AsrError("Unknown error ($code)")

        val isPhysiological: Boolean
            get() = this is NoSpeech || this is NoMatch || this is Timeout
    }

    // Internal state
    private var speechRecognizer: SpeechRecognizer? = null
    private var state: State = State.IDLE
    private var isEnabled = false  // Whether listening is allowed (set by Activity)

    // Handler for main thread operations and delayed restarts
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { startListeningInternal() }

    private var rmsAboveThresholdCount = 0
    private var speechDetectedByRms = false

    // Recognition language
    private var language: Locale = Locale.getDefault()

    /**
     * Check if speech recognition is available on this device.
     */
    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Set the language for speech recognition.
     */
    fun setLanguage(locale: Locale) {
        this.language = locale
    }

    /**
     * Get the current state of the ASR.
     */
    fun getState(): State = state

    /**
     * Check if the ASR is currently listening (in any active state).
     */
    fun isActive(): Boolean = state != State.IDLE

    /**
     * Start listening for speech.
     * This enables the ASR and begins the listening cycle.
     * The ASR will automatically restart after each utterance or physiological error.
     */
    fun startListening() {
        Log.d(TAG, "startListening() called, current state: $state")

        if (!isRecognitionAvailable()) {
            Log.e(TAG, "Speech recognition not available")
            listener.onError(AsrError.NotAvailable())
            return
        }

        isEnabled = true

        if (state != State.IDLE) {
            Log.d(TAG, "Already active, ignoring startListening")
            return
        }

        startListeningInternal()
    }

    /**
     * Stop listening completely.
     * This disables the ASR and stops any pending restarts.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening() called")

        isEnabled = false
        handler.removeCallbacks(restartRunnable)

        speechRecognizer?.let { recognizer ->
            try {
                recognizer.stopListening()
                recognizer.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognizer", e)
            }
        }

        resetSoftVad()
        state = State.IDLE
    }

    /**
     * Release all resources.
     * Must be called when the Activity is destroyed.
     */
    fun destroy() {
        Log.d(TAG, "destroy() called")

        stopListening()

        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Internal method to initialize the SpeechRecognizer.
     */
    private fun initSpeechRecognizer() {
        if (speechRecognizer != null) return

        Log.d(TAG, "Initializing SpeechRecognizer")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    /**
     * Internal method to start a listening session.
     */
    private fun startListeningInternal() {
        if (!isEnabled) {
            Log.d(TAG, "Not enabled, skipping start")
            return
        }

        initSpeechRecognizer()

        val recognizer = speechRecognizer
        if (recognizer == null) {
            Log.e(TAG, "SpeechRecognizer is null after init")
            listener.onError(AsrError.NotAvailable())
            return
        }

        resetSoftVad()

        val intent = createRecognizerIntent()

        try {
            state = State.LISTENING
            recognizer.startListening(intent)
            Log.d(TAG, "Started listening session")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            state = State.IDLE
            listener.onError(AsrError.ServiceError())
        }
    }

    /**
     * Create the intent for speech recognition configuration.
     */
    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Use free-form language model for natural speech
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // Set the recognition language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.toLanguageTag())

            // Enable partial results for real-time transcription
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Get top 3 results
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

            // Silence detection settings for natural speech
            // Minimum speech input length (3 seconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)

            // How long to wait after speech stops to finalize (1.5 seconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)

            // How long to wait for possibly complete silence (1.5 seconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
    }

    /**
     * Schedule a restart of listening after a delay.
     */
    private fun scheduleRestart(delayMs: Long) {
        if (!isEnabled) {
            Log.d(TAG, "Not enabled, not scheduling restart")
            state = State.IDLE
            return
        }

        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    /**
     * Reset soft VAD tracking.
     */
    private fun resetSoftVad() {
        rmsAboveThresholdCount = 0
        speechDetectedByRms = false
    }

    /**
     * Convert Android error code to AsrError.
     */
    private fun mapError(errorCode: Int): AsrError {
        return when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> AsrError.NoMatch()
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AsrError.Timeout()
            SpeechRecognizer.ERROR_AUDIO -> AsrError.AudioError()
            SpeechRecognizer.ERROR_CLIENT -> AsrError.ServiceError()
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AsrError.PermissionDenied()
            SpeechRecognizer.ERROR_NETWORK -> AsrError.NetworkError()
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> AsrError.NetworkError()
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> AsrError.RecognizerBusy()
            SpeechRecognizer.ERROR_SERVER -> AsrError.ServiceError()
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> AsrError.ServiceError()
            else -> AsrError.Unknown(errorCode)
        }
    }

    /**
     * RecognitionListener implementation for handling ASR events.
     */
    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            state = State.LISTENING
            listener.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            state = State.SPEECH_ACTIVE
            listener.onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Update listener for visual feedback
            listener.onRmsChanged(rmsdB)

            // Soft VAD: detect speech through volume levels
            if (state == State.LISTENING && rmsdB > RMS_SPEECH_THRESHOLD) {
                rmsAboveThresholdCount++

                if (rmsAboveThresholdCount >= RMS_SPEECH_COUNT_THRESHOLD && !speechDetectedByRms) {
                    speechDetectedByRms = true
                    Log.d(TAG, "Soft VAD: Speech detected via RMS")
                    // Note: We don't transition state here, we wait for onBeginningOfSpeech
                    // But this could be used for earlier UI feedback
                }
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio data - we don't use this as per requirements
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            state = State.PROCESSING
            listener.onSpeechEnded()
        }

        override fun onError(error: Int) {
            val asrError = mapError(error)
            Log.e(TAG, "onError: ${asrError.message} (code: $error)")

            resetSoftVad()

            // Notify listener
            listener.onError(asrError)

            // Handle restart based on error type
            if (asrError.isPhysiological) {
                // Physiological errors - automatically restart
                Log.d(TAG, "Physiological error, scheduling restart")
                scheduleRestart(ERROR_RESTART_DELAY_MS)
            } else if (asrError is AsrError.RecognizerBusy) {
                // Recognizer busy - wait longer and retry
                Log.d(TAG, "Recognizer busy, scheduling delayed restart")
                scheduleRestart(ERROR_RESTART_DELAY_MS * 2)
            } else {
                // Blocking error - stop completely
                Log.d(TAG, "Blocking error, stopping")
                state = State.IDLE
            }
        }

        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults")

            resetSoftVad()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val bestMatch = matches[0]
                Log.i(TAG, "Final result: $bestMatch")
                listener.onFinalResult(bestMatch)
            }

            // Automatically restart for next utterance
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partialText = matches[0]
                Log.d(TAG, "Partial result: $partialText")
                listener.onPartialResult(partialText)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent: $eventType")
        }
    }
}

