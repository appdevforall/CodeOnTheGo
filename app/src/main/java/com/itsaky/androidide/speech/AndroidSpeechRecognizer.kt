/*
 *  This file is part of CodeOnTheGo.
 *
 *  CodeOnTheGo is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CodeOnTheGo is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with CodeOnTheGo.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "AndroidSpeechRecognizer"

/**
 * Wrapper around Android's SpeechRecognizer with language support.
 * Uses cloud-based speech recognition (requires internet).
 */
class AndroidSpeechRecognizer(private val context: Context) {

    data class TranscriptionResult(
        val text: String,
        val confidence: Float = 0.9f,
        val latencyMs: Long = 0L
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguage: String = "en-US"
    private var activeRecognition = false

    /**
     * Initialize the speech recognizer.
     */
    fun initialize(language: String = "en-US"): Boolean {
        currentLanguage = language

        return try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "Speech recognition not available on this device")
                return false
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            Log.d(TAG, "AndroidSpeechRecognizer initialized for language: $language")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
            false
        }
    }

    /**
     * Transcribe audio using Android's speech recognition.
     * Note: audioBytes parameter is ignored - Android handles recording internally.
     *
     * @param audioBytes Ignored (kept for compatibility with Moonshine interface)
     * @param sampleRate Ignored (kept for compatibility with Moonshine interface)
     * @return TranscriptionResult with recognized text
     */
    suspend fun transcribe(audioBytes: ByteArray, sampleRate: Int = 16000): TranscriptionResult {
        return recognizeSpeech()
    }

    /**
     * Start speech recognition and wait for result.
     */
    private suspend fun recognizeSpeech(): TranscriptionResult = suspendCancellableCoroutine { continuation ->
        val startTime = System.currentTimeMillis()

        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer not initialized")
            continuation.resume(TranscriptionResult("", 0f, System.currentTimeMillis() - startTime))
            return@suspendCancellableCoroutine
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

            // Shorter silence timeout for better UX
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        }

        val recognitionListener = object : RecognitionListener {
            private var deliveredResult = false

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech (language: $currentLanguage)")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Could be used for waveform visualization
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d(TAG, "Buffer received: ${buffer?.size} bytes")
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }

            override fun onError(error: Int) {
                if (deliveredResult) {
                    return
                }
                deliveredResult = true
                activeRecognition = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error: $error"
                }
                Log.e(TAG, "Speech recognition error: $errorMessage")
                val latencyMs = System.currentTimeMillis() - startTime
                continuation.resume(TranscriptionResult("", 0f, latencyMs))
            }

            override fun onResults(results: Bundle?) {
                if (deliveredResult) {
                    return
                }
                deliveredResult = true
                activeRecognition = false
                val recognizedTexts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                val text = recognizedTexts?.firstOrNull() ?: ""
                val confidence = confidences?.firstOrNull() ?: 0.9f
                val latencyMs = System.currentTimeMillis() - startTime

                Log.d(TAG, "Speech recognition result: '$text' (confidence: $confidence, latency: ${latencyMs}ms)")
                continuation.resume(TranscriptionResult(text, confidence, latencyMs))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                Log.d(TAG, "Partial results received")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Recognition event: $eventType")
            }
        }

        speechRecognizer?.setRecognitionListener(recognitionListener)
        activeRecognition = true
        speechRecognizer?.startListening(intent)

        continuation.invokeOnCancellation {
            Log.d(TAG, "Recognition cancelled")
            speechRecognizer?.cancel()
            activeRecognition = false
        }
    }

    /**
     * Start live cloud recognition. Android's SpeechRecognizer owns microphone capture,
     * so this must be started when the UI enters recording state, not after PCM capture ends.
     */
    fun startListening(
        onResult: (TranscriptionResult) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Log.e(TAG, "SpeechRecognizer not initialized")
            onError("Speech recognizer is not initialized")
            return false
        }

        if (activeRecognition) {
            Log.w(TAG, "Recognition already active")
            return false
        }

        val startTime = System.currentTimeMillis()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        }

        val recognitionListener = object : RecognitionListener {
            private var deliveredResult = false

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for live speech (language: $currentLanguage)")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of live speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d(TAG, "Live buffer received: ${buffer?.size} bytes")
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of live speech")
            }

            override fun onError(error: Int) {
                if (deliveredResult) {
                    return
                }
                deliveredResult = true
                activeRecognition = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error: $error"
                }
                Log.e(TAG, "Live speech recognition error: $errorMessage")
                onError(errorMessage)
            }

            override fun onResults(results: Bundle?) {
                if (deliveredResult) {
                    return
                }
                deliveredResult = true
                activeRecognition = false

                val recognizedTexts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val text = recognizedTexts?.firstOrNull().orEmpty()
                val confidence = confidences?.firstOrNull() ?: 0.9f
                val latencyMs = System.currentTimeMillis() - startTime

                Log.d(TAG, "Live speech result: '$text' (confidence: $confidence, latency: ${latencyMs}ms)")
                onResult(TranscriptionResult(text, confidence, latencyMs))
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Live recognition event: $eventType")
            }
        }

        return try {
            recognizer.setRecognitionListener(recognitionListener)
            activeRecognition = true
            recognizer.startListening(intent)
            true
        } catch (e: Exception) {
            activeRecognition = false
            Log.e(TAG, "Failed to start live speech recognition", e)
            onError(e.message ?: "Failed to start speech recognition")
            false
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            activeRecognition = false
            Log.e(TAG, "Failed to stop speech recognition", e)
        }
    }

    /**
     * Set language for next recognition.
     */
    fun setLanguage(language: String) {
        currentLanguage = language
        Log.d(TAG, "Language changed to: $language")
    }

    /**
     * Release resources.
     */
    fun release() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            Log.d(TAG, "AndroidSpeechRecognizer released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing SpeechRecognizer", e)
        }
    }
}
