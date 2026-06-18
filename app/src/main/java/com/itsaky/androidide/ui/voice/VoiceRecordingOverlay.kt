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

package com.itsaky.androidide.ui.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.itsaky.androidide.R

/**
 * Overlay that appears during voice recording.
 * Shows waveform visualization and recording hints.
 */
class VoiceRecordingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val waveformVisualizer: WaveformVisualizer
    private val recordingText: TextView
    private val cancelHintText: TextView
    private var isRecording = false

    init {
        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.voice_recording_overlay, this, true)

        // Get view references
        waveformVisualizer = findViewById(R.id.waveformVisualizer)
        recordingText = findViewById(R.id.recordingText)
        cancelHintText = findViewById(R.id.cancelHintText)

        // Initially hidden
        visibility = View.GONE
        alpha = 0f

        // Setup background (semi-transparent)
        setBackgroundColor(0xD9000000.toInt()) // 85% black
        isClickable = true
        isFocusable = true
    }

    /**
     * Show the recording overlay with fade-in animation.
     */
    fun show() {
        if (isRecording) return

        isRecording = true
        visibility = View.VISIBLE

        // Fade in animation
        animate()
            .alpha(1f)
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    waveformVisualizer.startIdleAnimation()
                }
            })
            .start()
    }

    /**
     * Hide the recording overlay with fade-out animation.
     */
    fun hide() {
        if (!isRecording) return

        waveformVisualizer.reset()
        isRecording = false

        // Fade out animation
        animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                }
            })
            .start()
    }

    /**
     * Update waveform with audio amplitudes.
     * @param amplitudes Array of amplitude values (0.0 to 1.0)
     */
    fun updateWaveform(amplitudes: FloatArray) {
        if (isRecording) {
            waveformVisualizer.updateAmplitudes(amplitudes)
        }
    }

    /**
     * Update recording duration text.
     * @param durationMs Recording duration in milliseconds
     */
    fun updateDuration(durationMs: Long) {
        val seconds = durationMs / 1000
        recordingText.text = String.format("Recording... %d:%02d", seconds / 60, seconds % 60)
    }

    /**
     * Show "Processing..." state.
     */
    fun showProcessing() {
        waveformVisualizer.reset()
        recordingText.text = "Processing..."
        cancelHintText.isVisible = false
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        waveformVisualizer.reset()
        recordingText.text = "Recording..."
        cancelHintText.isVisible = true
        hide()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        waveformVisualizer.reset()
    }
}
