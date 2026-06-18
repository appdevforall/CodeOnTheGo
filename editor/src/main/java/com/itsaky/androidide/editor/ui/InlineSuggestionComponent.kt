/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor.ui

import android.graphics.Canvas
import android.view.KeyEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.component.EditorBuiltinComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Sora editor component for inline code suggestions.
 *
 * Monitors text changes, triggers LLM requests after 3 characters + 300ms idle,
 * renders ghost text, and handles Tab/Esc keyboard events.
 */
class InlineSuggestionComponent(private val editor: IDEEditor) : EditorBuiltinComponent {

    private val log = LoggerFactory.getLogger(InlineSuggestionComponent::class.java)

    private var currentSuggestion: SuggestionData? = null
    private var suggestionState: SuggestionState = SuggestionState.IDLE
    private var charactersSinceLastRequest: Int = 0
    private var debounceJob: Job? = null

    private val renderer = GhostTextRenderer(editor)
    private val provider = SuggestionProvider(editor)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var enabled: Boolean = true
    private var temporarilyHidden: Boolean = false

    init {
        log.info("InlineSuggestionComponent initialized")
    }

    override fun isEnabled(): Boolean = enabled

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            dismissSuggestion()
        }
    }

    fun onAttachedToWindow() {
        log.debug("Component attached to window")
    }

    fun onDetachedFromWindow() {
        log.debug("Component detached from window")
        cleanup()
    }

    /**
     * Called when editor content changes (user types).
     */
    fun onContentChange(event: ContentChangeEvent) {
        if (!enabled || !editor.isEditable) {
            return
        }

        // If showing suggestion, check if new text compatible
        if (suggestionState == SuggestionState.SHOWING) {
            val compatible = isNewTextCompatible(event)
            if (!compatible) {
                dismissSuggestion()
                charactersSinceLastRequest = 0
            }
            return
        }

        // Increment character counter
        charactersSinceLastRequest++

        // If we've typed 3+ characters, start debounce timer
        if (charactersSinceLastRequest >= 3) {  // Global constraint: 3 chars
            scheduleRequest()
        }
    }

    /**
     * Called when editor selection changes (cursor moves).
     */
    fun onSelectionChange(event: SelectionChangeEvent) {
        if (suggestionState == SuggestionState.SHOWING) {
            // Dismiss suggestion when cursor moves
            dismissSuggestion()
        }
    }

    /**
     * Called for key events. Intercepts Tab and Esc.
     *
     * @return true if event consumed, false to pass through
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                if (suggestionState == SuggestionState.SHOWING) {
                    acceptSuggestion()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (suggestionState == SuggestionState.SHOWING) {
                    dismissSuggestion()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun scheduleRequest() {
        // Cancel previous debounce
        debounceJob?.cancel()

        suggestionState = SuggestionState.WAITING

        // Start 300ms debounce timer (Global constraint)
        debounceJob = scope.launch {
            delay(300)

            if (enabled && editor.isEditable) {
                suggestionState = SuggestionState.REQUESTING
                requestSuggestion()
            }
        }
    }

    private suspend fun requestSuggestion() {
        // Will implement in next task with actual LLM call
        log.debug("Request suggestion (not implemented yet)")
        suggestionState = SuggestionState.IDLE
    }

    private fun acceptSuggestion() {
        val suggestion = currentSuggestion ?: return

        suggestionState = SuggestionState.ACCEPTING

        // Insert text at cursor
        val text = editor.text
        val cursor = text.cursor
        text.insert(cursor.leftLine, cursor.rightLine, suggestion.text)

        // Move cursor to end of inserted text
        val lines = suggestion.text.split("\n")
        val lastLine = lines.last()
        val newLine = cursor.leftLine + lines.size - 1
        val newColumn = if (lines.size == 1) {
            cursor.leftColumn + lastLine.length
        } else {
            lastLine.length
        }
        editor.setSelection(newLine, newColumn)

        // Clean up
        dismissSuggestion()

        log.info("Suggestion accepted")
    }

    private fun isNewTextCompatible(event: ContentChangeEvent): Boolean {
        // Simple check: if suggestion still starts with what we have, it's compatible
        val suggestion = currentSuggestion?.text ?: return false
        // For now, any change dismisses. We can make this smarter later.
        return false
    }

    private fun cleanup() {
        debounceJob?.cancel()
        provider.cancelActiveRequest()
        provider.clearCache()
        scope.cancel()
    }

    /**
     * Get current state (for testing).
     */
    internal fun getState(): SuggestionState = suggestionState

    private fun dismissSuggestion() {
        currentSuggestion = null
        suggestionState = SuggestionState.IDLE
        charactersSinceLastRequest = 0
        renderer.hide()
        editor.invalidate()
    }
}
