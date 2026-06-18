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
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.preferences.internal.InlineSuggestionPreferences
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
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

/**
 * Sora editor component for inline code suggestions.
 *
 * Monitors text changes, triggers LLM requests after 3 characters + 300ms idle,
 * renders ghost text, and handles Tab/Esc keyboard events.
 */
class InlineSuggestionComponent(private val editor: IDEEditor) : EditorBuiltinComponent {

    companion object {
        /**
         * LLM inference function to be injected by app module.
         * Set this once at app startup before editors are created.
         */
        @Volatile
        var llmInference: LlmInferenceFunction? = null

        /**
         * LLM model check function to be injected by app module.
         * Set this once at app startup before editors are created.
         */
        @Volatile
        var llmModelCheck: LlmModelCheckFunction? = null
    }

    private val log = LoggerFactory.getLogger(InlineSuggestionComponent::class.java)

    private var currentSuggestion: SuggestionData? = null
    private var suggestionState: SuggestionState = SuggestionState.IDLE
    private var charactersSinceLastRequest: Int = 0
    private var debounceJob: Job? = null

    private val renderer = GhostTextRenderer(editor)
    private val provider = SuggestionProvider(editor, llmInference = llmInference, llmModelCheck = llmModelCheck)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var enabled: Boolean = true
    private var temporarilyHidden: Boolean = false

    init {
        enabled = InlineSuggestionPreferences.enabled
        log.info("InlineSuggestionComponent initialized (enabled: $enabled)")
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
        org.greenrobot.eventbus.EventBus.getDefault().register(this)
    }

    fun onDetachedFromWindow() {
        log.debug("Component detached from window")
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
        cleanup()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPreferenceChanged(event: PreferenceChangeEvent) {
        when (event.key) {
            InlineSuggestionPreferences.ENABLED -> {
                setEnabled(InlineSuggestionPreferences.enabled)
            }
        }
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

        // If we've typed enough characters, start debounce timer
        if (charactersSinceLastRequest >= InlineSuggestionPreferences.charThreshold) {
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
     * Called for key events. Intercepts Tab, Esc, and Ctrl+Space.
     *
     * @return true if event consumed, false to pass through
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        // Check for Ctrl+Space (manual trigger)
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            manualTrigger()
            return true
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

    /**
     * Manually trigger a suggestion request immediately, bypassing the character
     * threshold and debounce delay.
     *
     * Used for Ctrl+Space shortcut and toolbar button.
     */
    fun manualTrigger() {
        if (!enabled || !editor.isEditable) {
            log.debug("Manual trigger ignored (disabled or not editable)")
            return
        }

        if (suggestionState == SuggestionState.SHOWING) {
            // Already showing, dismiss and request new
            dismissSuggestion()
        }

        // Cancel any in-flight request
        debounceJob?.cancel()
        provider.cancelActiveRequest()

        // Request immediately
        suggestionState = SuggestionState.REQUESTING
        scope.launch {
            requestSuggestion()
        }

        log.info("Manual trigger activated")
    }

    private fun scheduleRequest() {
        // Cancel previous debounce
        debounceJob?.cancel()

        suggestionState = SuggestionState.WAITING

        // Start debounce timer using preference
        debounceJob = scope.launch {
            delay(InlineSuggestionPreferences.debounceMs.toLong())

            if (enabled && editor.isEditable) {
                suggestionState = SuggestionState.REQUESTING
                requestSuggestion()
            }
        }
    }

    private suspend fun requestSuggestion() {
        try {
            val cursor = editor.cursor
            val position = com.itsaky.androidide.models.Position(
                cursor.leftLine,
                cursor.leftColumn,
                editor.text.getCharIndex(cursor.leftLine, cursor.leftColumn)
            )

            val fileContent = editor.text.toString()
            val language = editor.file?.extension ?: "txt"

            val suggestion = provider.requestSuggestion(position, fileContent, language)

            if (suggestion != null) {
                currentSuggestion = suggestion
                suggestionState = SuggestionState.SHOWING
                renderer.show(suggestion)
                editor.postInvalidate()
                log.info("Suggestion shown: ${suggestion.text.take(30)}...")
            } else {
                suggestionState = SuggestionState.IDLE
                log.debug("No suggestion returned")
            }
        } catch (e: Exception) {
            log.error("Error requesting suggestion", e)
            suggestionState = SuggestionState.IDLE
        }
    }

    private fun acceptSuggestion() {
        val suggestion = currentSuggestion ?: return

        suggestionState = SuggestionState.ACCEPTING

        // Insert text at cursor
        val text = editor.text
        val cursor = text.cursor
        text.insert(cursor.leftLine, cursor.leftColumn, suggestion.text)

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
     * Draw the inline suggestion. Called by editor's draw cycle.
     */
    fun draw(canvas: Canvas) {
        if (!enabled || temporarilyHidden) {
            return
        }

        if (suggestionState == SuggestionState.SHOWING && renderer.isVisible()) {
            renderer.onDraw(canvas)
        }
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
