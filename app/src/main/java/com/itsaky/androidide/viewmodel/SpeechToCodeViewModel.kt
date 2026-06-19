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
import kotlinx.coroutines.delay
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
    private var llamaController: com.itsaky.androidide.llamacpp.api.ILlamaController? = null
    private var recordingStartTime: Long = 0L

    init {
        Log.d(TAG, "SpeechToCodeViewModel initialized")
    }

    /**
     * Set the LLaMA controller for code generation.
     */
    fun setController(controller: com.itsaky.androidide.llamacpp.api.ILlamaController) {
        llamaController = controller
        Log.d(TAG, "LLaMA controller set")
    }

    /**
     * Initialize audio recorder and pipeline.
     * Call this when permissions are granted.
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initialize(): Boolean {
        return try {
            audioRecorder = AudioRecorder(getApplication())
            val initialized = audioRecorder?.initialize() ?: false

            if (initialized) {
                Log.d(TAG, "AudioRecorder initialized successfully")
                // Initialize pipeline with STT and LLM
                pipeline = SpeechToCodePipeline(
                    context = getApplication(),
                    llamaController = llamaController ?: run {
                        Log.w(TAG, "No LLaMA controller available")
                        return false
                    },
                    intentRecognizer = VoiceCommandRecognizer()
                )
                pipeline?.initialize() ?: false
                true
            } else {
                Log.e(TAG, "Failed to initialize AudioRecorder")
                _error.value = VoiceError.Unknown("Failed to initialize audio recorder")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            _error.value = VoiceError.Unknown(e.message ?: "Initialization failed")
            false
        }
    }

    /**
     * Start recording audio.
     */
    fun startRecording() {
        try {
            Log.d(TAG, "Starting recording...")
            recordingStartTime = System.currentTimeMillis()

            val started = audioRecorder?.startRecording() ?: false
            if (started) {
                _recordingState.value = RecordingState.Recording(0L, emptyList())
                Log.d(TAG, "Recording started successfully")

                // Update duration periodically
                viewModelScope.launch {
                    while (_recordingState.value is RecordingState.Recording) {
                        val duration = System.currentTimeMillis() - recordingStartTime
                        _recordingState.value = RecordingState.Recording(duration, emptyList())
                        kotlinx.coroutines.delay(100) // Update every 100ms
                    }
                }
            } else {
                Log.e(TAG, "Failed to start recording")
                _error.value = VoiceError.Unknown("Failed to start recording")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording start error", e)
            _error.value = VoiceError.Unknown(e.message ?: "Recording failed")
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

                // Process through pipeline
                val result = pipeline?.processAudio(audioBytes)

                if (result != null) {
                    _previewData.value = PreviewData(
                        transcription = result.command,
                        generatedCode = result.code,
                        intent = result.command,
                        confidence = result.confidence,
                        latencyMs = result.totalLatencyMs
                    )
                    Log.d(TAG, "Processing complete: ${result.command}")
                } else {
                    _error.value = VoiceError.Unknown("Pipeline not initialized")
                }

                _recordingState.value = RecordingState.Idle
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
