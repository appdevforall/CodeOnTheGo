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

package com.itsaky.androidide.actions.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.activities.editor.startVoiceRecording
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.speech.VoicePreferences

/**
 * Action for voice-to-code functionality.
 * Appears in editor toolbar next to search icon.
 *
 * Interaction:
 * - Quick tap: Opens voice mode panel for longer dictation
 * - Long press: Immediate recording (WhatsApp-style)
 */
class VoiceCodeAction() : EditorActivityAction() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override var requiresUIThread: Boolean = true
    override var order: Int = 0

    constructor(context: Context, order: Int) : this() {
        this.label = context.getString(R.string.voice_code)
        this.icon = ContextCompat.getDrawable(context, R.drawable.ic_voice_mic)
        this.order = order
    }

    override val id: String = "ide.editor.voice_code"

    override fun prepare(data: ActionData) {
        super.prepare(data)
        val activity = data.getActivity()

        // First check if voice code feature is enabled in settings
        enabled = activity?.let {
            VoicePreferences.isVoiceCodeEnabled(it)
        } ?: false

        // Check if microphone permission is granted
        if (enabled) {
            enabled = activity?.let {
                ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            } ?: false
        }

        // Disable if no editor open
        if (enabled) {
            enabled = data.get(com.itsaky.androidide.editor.ui.IDEEditor::class.java) != null
        }
    }

    override suspend fun execAction(data: ActionData): Boolean {
        val activity = data.getActivity() as? EditorHandlerActivity ?: return false

        // Check if voice code is enabled
        if (!VoicePreferences.isVoiceCodeEnabled(activity)) {
            activity.runOnUiThread {
                android.widget.Toast.makeText(
                    activity,
                    "Voice code is disabled. Enable it in AI Settings.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }

        // Check permission
        if (!checkMicrophonePermission(activity)) {
            requestMicrophonePermission(activity)
            return false
        }

        // Start voice recording
        activity.startVoiceRecording()

        return true
    }

    /**
     * Check if microphone permission is granted.
     */
    private fun checkMicrophonePermission(activity: EditorHandlerActivity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request microphone permission.
     */
    private fun requestMicrophonePermission(activity: EditorHandlerActivity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }
}
