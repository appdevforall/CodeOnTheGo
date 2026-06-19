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

package com.itsaky.androidide.activities.editor

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.speech.VoicePreferences
import com.itsaky.androidide.ui.voice.VoicePreviewBottomSheet
import com.itsaky.androidide.ui.voice.VoiceRecordingOverlay
import com.itsaky.androidide.viewmodel.SpeechToCodeViewModel
import kotlinx.coroutines.launch

private const val TAG = "VoiceCodeIntegration"

/**
 * Extension functions for integrating voice-to-code into ProjectHandlerActivity.
 *
 * Usage in ProjectHandlerActivity onCreate():
 * ```kotlin
 * setupVoiceCode()
 * ```
 */

/**
 * Setup voice-to-code functionality.
 * Call this in onCreate() after other initializations.
 */
fun ProjectHandlerActivity.setupVoiceCode() {
    // Only setup if voice code is enabled
    if (!VoicePreferences.isVoiceCodeEnabled(this)) {
        Log.d(TAG, "Voice code disabled in preferences")
        return
    }

    // Check permission
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "Microphone permission not granted, skipping voice code setup")
        return
    }

    try {
        // Get or create ViewModel
        val viewModel = ViewModelProvider(this)[SpeechToCodeViewModel::class.java]

        // Set LLM controller if available
        val llmEngine = LlmInferenceEngineProvider.instance
        llmEngine.getLlamaController()?.let { controller ->
            viewModel.setController(controller)
            Log.d(TAG, "LLM controller set for voice code")
        } ?: run {
            Log.w(TAG, "LLM controller not available for voice code")
        }

        // Initialize components
        lifecycleScope.launch {
            val initialized = viewModel.initialize()
            if (initialized) {
                Log.d(TAG, "Voice code initialized successfully")
                setupVoiceCodeObservers(viewModel)
            } else {
                Log.e(TAG, "Failed to initialize voice code")
            }
        }

    } catch (e: Exception) {
        Log.e(TAG, "Error setting up voice code", e)
    }
}

/**
 * Setup observers for voice code ViewModel.
 */
private fun ProjectHandlerActivity.setupVoiceCodeObservers(viewModel: SpeechToCodeViewModel) {
    var recordingOverlay: VoiceRecordingOverlay? = null
    var previewSheet: VoicePreviewBottomSheet? = null

    // Observe recording state
    viewModel.recordingState.observe(this) { state ->
        when (state) {
            is SpeechToCodeViewModel.RecordingState.Idle -> {
                // Hide and remove overlay
                recordingOverlay?.let { overlay ->
                    overlay.hide()
                    (window.decorView as? android.view.ViewGroup)?.removeView(overlay)
                }
                recordingOverlay = null
            }
            is SpeechToCodeViewModel.RecordingState.Recording -> {
                if (recordingOverlay == null) {
                    // Create and add overlay to activity's content view
                    recordingOverlay = VoiceRecordingOverlay(this).also { overlay ->
                        (window.decorView as? android.view.ViewGroup)?.addView(overlay)
                        overlay.show()
                    }
                    Log.d(TAG, "Voice recording overlay added to view")
                }
                recordingOverlay?.updateDuration(state.durationMs)
                recordingOverlay?.updateWaveform(state.amplitudes.toFloatArray())
            }
            is SpeechToCodeViewModel.RecordingState.Processing -> {
                recordingOverlay?.showProcessing()
            }
        }
    }

    // Observe preview data
    viewModel.previewData.observe(this) { preview ->
        if (preview != null) {
            // Get current editor
            val editor = getCurrentEditor()

            if (editor != null) {
                // Show preview bottom sheet
                previewSheet = VoicePreviewBottomSheet.newInstance(preview)
                previewSheet?.onInsertClicked = { code ->
                    viewModel.insertCode(code, editor)
                    previewSheet?.dismiss()
                }
                previewSheet?.onTryAgainClicked = {
                    previewSheet?.dismiss()
                    viewModel.dismissPreview()
                    // Optionally start recording again
                }
                previewSheet?.show(supportFragmentManager, "voice_preview")
            } else {
                Log.w(TAG, "No editor available for code insertion")
                viewModel.dismissPreview()
            }
        } else {
            previewSheet?.dismiss()
            previewSheet = null
        }
    }

    // Observe errors
    viewModel.error.observe(this) { error ->
        if (error != null) {
            val message = when (error) {
                is SpeechToCodeViewModel.VoiceError.NoSpeech ->
                    "No speech detected. Please try again."
                is SpeechToCodeViewModel.VoiceError.RecognitionFailed ->
                    "Could not recognize speech. Please speak clearly."
                is SpeechToCodeViewModel.VoiceError.GenerationTimeout ->
                    "Code generation timed out. Please try again."
                is SpeechToCodeViewModel.VoiceError.PermissionDenied ->
                    "Microphone permission required for voice code."
                is SpeechToCodeViewModel.VoiceError.Unknown ->
                    "Error: ${error.message}"
            }

            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }
}

/**
 * Toggle voice recording (start/stop).
 * This method is called by VoiceCodeAction.
 */
fun ProjectHandlerActivity.startVoiceRecording() {
    try {
        val viewModel = ViewModelProvider(this)[SpeechToCodeViewModel::class.java]

        // Check current state and toggle
        val currentState = viewModel.recordingState.value
        if (currentState is SpeechToCodeViewModel.RecordingState.Recording) {
            // Already recording, stop it
            viewModel.stopRecordingAndProcess()
            Log.d(TAG, "Voice recording stopped from action")
        } else {
            // Not recording, start it
            viewModel.startRecording()
            Log.d(TAG, "Voice recording started from action")
        }

    } catch (e: Exception) {
        Log.e(TAG, "Error toggling voice recording", e)
        Toast.makeText(this, "Failed to toggle voice recording", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Stop voice recording and process.
 * Can be called manually or from UI.
 */
fun ProjectHandlerActivity.stopVoiceRecording() {
    try {
        val viewModel = ViewModelProvider(this)[SpeechToCodeViewModel::class.java]
        viewModel.stopRecordingAndProcess()
        Log.d(TAG, "Voice recording stopped")

    } catch (e: Exception) {
        Log.e(TAG, "Error stopping voice recording", e)
    }
}

/**
 * Get the currently active editor.
 */
private fun ProjectHandlerActivity.getCurrentEditor(): com.itsaky.androidide.editor.ui.IDEEditor? {
    return try {
        // Access current editor from activity
        // This may need adjustment based on actual activity structure
        val method = this.javaClass.getMethod("getCurrentEditor")
        method.invoke(this) as? com.itsaky.androidide.editor.ui.IDEEditor
    } catch (e: Exception) {
        Log.e(TAG, "Could not get current editor", e)
        null
    }
}
