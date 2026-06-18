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

package com.itsaky.androidide.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.speech.AudioRecorder
import com.itsaky.androidide.speech.SpeechToCodePipeline
import com.itsaky.androidide.speech.VoiceCommandRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SpeechToCodeViewModel"

/**
 * ViewModel for managing speech-to-code functionality.
 * Handles recording, processing, and code preview states.
 */
class SpeechToCodeViewModel(application: Application) : AndroidViewModel(application) {

    // Recording states
    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(
            val durationMs: Long,
            val amplitudes: List<Float> = emptyList()
        ) : RecordingState()
        object Processing : RecordingState()
    }

    // Preview data
    data class PreviewData(
        val transcription: String,
        val generatedCode: String,
        val intent: String,
        val confidence: Float,
        val latencyMs: Long
    )

    // Error states
    sealed class VoiceError {
        object NoSpeech : VoiceError()
        object RecognitionFailed : VoiceError()
        object GenerationTimeout : VoiceError()
        object PermissionDenied : VoiceError()
        data class Unknown(val message: String) : VoiceError()
    }

    // LiveData observables
    private val _recordingState = MutableLiveData<RecordingState>(RecordingState.Idle)
    val recordingState: LiveData<RecordingState> = _recordingState

    private val _previewData = MutableLiveData<PreviewData?>()
    val previewData: LiveData<PreviewData?> = _previewData

    private val _error = MutableLiveData<VoiceError?>()
    val error: LiveData<VoiceError?> = _error

    // Core components (placeholders for now)
    private var audioRecorder: AudioRecorder? = null
    private var pipeline: SpeechToCodePipeline? = null
    private var recordingStartTime: Long = 0L

    init {
        Log.d(TAG, "SpeechToCodeViewModel initialized")
    }

    /**
     * Initialize audio recorder and pipeline.
     * Call this when permissions are granted.
     */
    fun initialize() {
        viewModelScope.launch {
            try {
                audioRecorder = AudioRecorder(getApplication())
                val initialized = audioRecorder?.initialize() ?: false

                if (initialized) {
                    Log.d(TAG, "AudioRecorder initialized successfully")
                    // TODO: Initialize pipeline with STT and LLM
                    // pipeline = SpeechToCodePipeline(stt, llamaController, recognizer)
                } else {
                    Log.e(TAG, "Failed to initialize AudioRecorder")
                    _error.value = VoiceError.Unknown("Failed to initialize audio recorder")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization error", e)
                _error.value = VoiceError.Unknown(e.message ?: "Initialization failed")
            }
        }
    }

    /**
     * Start recording audio.
     */
    fun startRecording() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting recording...")
                recordingStartTime = System.currentTimeMillis()

                val started = audioRecorder?.startRecording() ?: false
                if (started) {
                    _recordingState.value = RecordingState.Recording(0L, emptyList())
                    Log.d(TAG, "Recording started")
                } else {
                    Log.e(TAG, "Failed to start recording")
                    _error.value = VoiceError.Unknown("Failed to start recording")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording start error", e)
                _error.value = VoiceError.Unknown(e.message ?: "Recording failed")
            }
        }
    }

    /**
     * Stop recording and process audio through pipeline.
     */
    fun stopRecordingAndProcess() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Stopping recording and processing...")
                val recordingDuration = System.currentTimeMillis() - recordingStartTime

                _recordingState.value = RecordingState.Processing

                val audioBytes = audioRecorder?.stopRecording() ?: byteArrayOf()
                Log.d(TAG, "Audio captured: ${audioBytes.size} bytes")

                if (audioBytes.isEmpty()) {
                    _error.value = VoiceError.NoSpeech
                    _recordingState.value = RecordingState.Idle
                    return@launch
                }

                // TODO: Process through pipeline
                // For now, create placeholder preview
                _previewData.value = PreviewData(
                    transcription = "Voice command placeholder",
                    generatedCode = "// Generated code will appear here\n// Duration: ${recordingDuration}ms",
                    intent = "PLACEHOLDER",
                    confidence = 0.9f,
                    latencyMs = recordingDuration
                )

                _recordingState.value = RecordingState.Idle
                Log.d(TAG, "Processing complete")
            } catch (e: Exception) {
                Log.e(TAG, "Processing error", e)
                _error.value = VoiceError.Unknown(e.message ?: "Processing failed")
                _recordingState.value = RecordingState.Idle
            }
        }
    }

    /**
     * Cancel recording without processing.
     */
    fun cancelRecording() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Cancelling recording...")
                audioRecorder?.stopRecording()
                _recordingState.value = RecordingState.Idle
                Log.d(TAG, "Recording cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Cancel error", e)
            }
        }
    }

    /**
     * Insert generated code into editor with typing animation.
     */
    fun insertCode(code: String, editor: IDEEditor) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "Inserting code: ${code.take(50)}...")

                // TODO: Implement typing animation
                // For now, insert directly
                val cursor = editor.cursor
                editor.text.insert(cursor.leftLine, cursor.leftColumn, code)

                // Move cursor to end of inserted text
                val lines = code.split('\n')
                if (lines.size == 1) {
                    editor.cursor.set(cursor.leftLine, cursor.leftColumn + code.length)
                } else {
                    val lastLineLen = lines.last().length
                    editor.cursor.set(cursor.leftLine + lines.size - 1, lastLineLen)
                }

                // Clear preview
                _previewData.value = null

                Log.d(TAG, "Code inserted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Insert error", e)
                _error.value = VoiceError.Unknown(e.message ?: "Failed to insert code")
            }
        }
    }

    /**
     * Dismiss preview without inserting code.
     */
    fun dismissPreview() {
        _previewData.value = null
        Log.d(TAG, "Preview dismissed")
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Release resources.
     */
    override fun onCleared() {
        super.onCleared()
        audioRecorder?.release()
        Log.d(TAG, "SpeechToCodeViewModel cleared")
    }
}
