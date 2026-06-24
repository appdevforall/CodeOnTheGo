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
        val viewModel = ViewModelProvider(this)[SpeechToCodeViewModel::class.java]
        lifecycleScope.launch {
            ensureVoiceCodeReady(viewModel)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error setting up voice code", e)
    }
}

private suspend fun ProjectHandlerActivity.ensureVoiceCodeReady(
    viewModel: SpeechToCodeViewModel
): Boolean {
    if (!viewModel.observersAttached) {
        setupVoiceCodeObservers(viewModel)
        viewModel.observersAttached = true
    }

    val llmEngine = LlmInferenceEngineProvider.instance
    val controller = llmEngine.getLlamaController()
    if (controller == null) {
        Log.w(TAG, "LLM controller not available for voice code")
        return false
    }

    viewModel.setController(controller)
    Log.d(TAG, "LLM controller set for voice code")

    val initialized = viewModel.initialize()
    if (initialized) {
        Log.d(TAG, "Voice code initialized successfully")
    } else {
        Log.e(TAG, "Failed to initialize voice code")
    }
    return initialized
}

/**
 * Setup observers for voice code ViewModel.
 */
private fun ProjectHandlerActivity.setupVoiceCodeObservers(viewModel: SpeechToCodeViewModel) {
    var previewSheet: VoicePreviewBottomSheet? = null
    var lastRecordingStateClass: Class<*>? = null

    // Observe recording state only to refresh the toolbar mic action, which reflects
    // the recording state (animated waveform icon + "Stop voice recording" label).
    // No full-screen overlay is shown.
    //
    // The Recording state re-emits a new Recording(duration) every 100ms; we must only
    // refresh on a state *type* change. invalidateOptionsMenu() is debounced by 150ms,
    // so invalidating on every 100ms tick would perpetually cancel the pending refresh
    // and the toolbar would never swap in the animated waveform icon.
    viewModel.recordingState.observe(this) { state ->
        val stateClass = state?.javaClass
        if (stateClass != lastRecordingStateClass) {
            lastRecordingStateClass = stateClass
            invalidateOptionsMenu()
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
            lifecycleScope.launch {
                if (ensureVoiceCodeReady(viewModel)) {
                    viewModel.startRecording()
                    Log.d(TAG, "Voice recording started from action")
                } else {
                    Toast.makeText(
                        this@startVoiceRecording,
                        "Voice code is not ready. Load an AI model first.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
