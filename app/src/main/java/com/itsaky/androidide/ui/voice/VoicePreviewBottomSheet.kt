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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.itsaky.androidide.R
import com.itsaky.androidide.viewmodel.SpeechToCodeViewModel
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Bottom sheet that displays voice command preview before insertion.
 * Shows transcription, generated code, and action buttons.
 */
class VoicePreviewBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TRANSCRIPTION = "transcription"
        private const val ARG_CODE = "code"
        private const val ARG_INTENT = "intent"
        private const val ARG_CONFIDENCE = "confidence"
        private const val ARG_LATENCY = "latency"

        fun newInstance(previewData: SpeechToCodeViewModel.PreviewData): VoicePreviewBottomSheet {
            return VoicePreviewBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TRANSCRIPTION, previewData.transcription)
                    putString(ARG_CODE, previewData.generatedCode)
                    putString(ARG_INTENT, previewData.intent)
                    putFloat(ARG_CONFIDENCE, previewData.confidence)
                    putLong(ARG_LATENCY, previewData.latencyMs)
                }
            }
        }
    }

    private lateinit var transcriptionChip: Chip
    private lateinit var codePreview: CodeEditor
    private lateinit var insertButton: MaterialButton
    private lateinit var tryAgainButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var confidenceText: TextView
    private lateinit var latencyText: TextView

    var onInsertClicked: ((String) -> Unit)? = null
    var onTryAgainClicked: (() -> Unit)? = null
    var onCancelClicked: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.voice_preview_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get view references
        transcriptionChip = view.findViewById(R.id.transcriptionChip)
        codePreview = view.findViewById(R.id.codePreview)
        insertButton = view.findViewById(R.id.insertButton)
        tryAgainButton = view.findViewById(R.id.tryAgainButton)
        cancelButton = view.findViewById(R.id.cancelButton)
        confidenceText = view.findViewById(R.id.confidenceText)
        latencyText = view.findViewById(R.id.latencyText)

        // Get arguments
        val transcription = arguments?.getString(ARG_TRANSCRIPTION) ?: ""
        val code = arguments?.getString(ARG_CODE) ?: ""
        val intent = arguments?.getString(ARG_INTENT) ?: ""
        val confidence = arguments?.getFloat(ARG_CONFIDENCE) ?: 0f
        val latency = arguments?.getLong(ARG_LATENCY) ?: 0L

        // Setup views
        transcriptionChip.text = transcription
        codePreview.setText(code)
        codePreview.isEditable = false

        // Show confidence and latency (for debugging/transparency)
        val confidencePercent = (confidence * 100).toInt()
        confidenceText.text = "Confidence: $confidencePercent%"
        latencyText.text = "Generated in ${latency}ms"

        // Setup button listeners
        insertButton.setOnClickListener {
            onInsertClicked?.invoke(code)
            dismiss()
        }

        tryAgainButton.setOnClickListener {
            onTryAgainClicked?.invoke()
            dismiss()
        }

        cancelButton.setOnClickListener {
            onCancelClicked?.invoke()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onInsertClicked = null
        onTryAgainClicked = null
        onCancelClicked = null
    }
}
