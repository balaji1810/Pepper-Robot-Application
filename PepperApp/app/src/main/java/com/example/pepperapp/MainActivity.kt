package com.example.pepperapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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

class MainActivity : ComponentActivity(), RobotLifecycleCallbacks {

    private lateinit var statusTextView: TextView
    private lateinit var recognizedTextView: TextView

    private var qiContext: QiContext? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var hasRobotFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        recognizedTextView = findViewById(R.id.recognizedTextView)

        // Check and request audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }

        QiSDK.register(this, this)
    }

    override fun onDestroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        QiSDK.unregister(this, this)
        super.onDestroy()
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
                Log.i(TAG, "Audio permission granted")
                if (hasRobotFocus) {
                    startListening()
                }
            } else {
                Log.e(TAG, "Audio permission denied")
                statusTextView.text = getString(R.string.status_error, "Audio permission denied")
            }
        }
    }

    override fun onRobotFocusGained(qiContext: QiContext?) {
        this.qiContext = qiContext
        hasRobotFocus = true
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null")
            return
        }
        Log.i(TAG, "Robot focus gained")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_ready)
            startListening()
        }
    }

    override fun onRobotFocusLost() {
        Log.i(TAG, "Robot focus lost")
        hasRobotFocus = false
        qiContext = null
        runOnUiThread {
            stopListening()
            statusTextView.text = getString(R.string.status_waiting)
        }
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.e(TAG, "Robot focus refused: $reason")
        runOnUiThread {
            statusTextView.text = getString(R.string.status_error, reason ?: "Unknown")
        }
    }

    private fun initSpeechRecognizer() {
        if (speechRecognizer != null) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available on this device")
            statusTextView.text = getString(R.string.status_error, "Speech recognition not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    runOnUiThread {
                        statusTextView.text = getString(R.string.status_listening)
                    }
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Audio level changed - could use for visual feedback
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech")
                    runOnUiThread {
                        statusTextView.text = getString(R.string.status_processing)
                    }
                }

                override fun onError(error: Int) {
                    val errorMessage = getErrorMessage(error)
                    Log.e(TAG, "Speech recognition error: $errorMessage")
                    runOnUiThread {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                statusTextView.text = getString(R.string.status_no_speech)
                            }
                            else -> {
                                statusTextView.text = getString(R.string.status_error, errorMessage)
                            }
                        }
                        // Restart listening after a short delay
                        if (hasRobotFocus && isListening) {
                            statusTextView.postDelayed({ startListening() }, 500)
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val recognizedText = matches[0]
                        Log.i(TAG, "Recognized: $recognizedText")
                        runOnUiThread {
                            recognizedTextView.text = recognizedText
                            statusTextView.text = getString(R.string.status_ready)
                        }
                    }
                    // Continue listening
                    if (hasRobotFocus && isListening) {
                        runOnUiThread {
                            statusTextView.postDelayed({ startListening() }, 300)
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        Log.d(TAG, "Partial: ${matches[0]}")
                        // Optionally show partial results
                        runOnUiThread {
                            recognizedTextView.text = buildString {
                                append(matches[0].toString())
                                append("...")
                            }
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    Log.d(TAG, "Speech event: $eventType")
                }
            })
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No audio permission, cannot start listening")
            return
        }

        initSpeechRecognizer()

        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer is null")
            return
        }

        isListening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Adjust silence detection
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.i(TAG, "Started listening...")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            statusTextView.text = getString(R.string.status_error, e.message)
        }
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error ($errorCode)"
        }
    }
}