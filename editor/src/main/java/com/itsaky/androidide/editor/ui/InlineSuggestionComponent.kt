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
import com.itsaky.androidide.resources.R
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

    private val log = LoggerFactory.getLogger(InlineSuggestionComponent::class.java)

    private var currentSuggestion: SuggestionData? = null
    private var suggestionState: SuggestionState = SuggestionState.IDLE
    private var debounceJob: Job? = null
    private var requestJob: Job? = null

    private val renderer = GhostTextRenderer(editor)
    private val provider = SuggestionProvider(editor)

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

        // Ignore programmatic whole-document changes (file load / set text) so we don't auto-trigger
        // on open.
        if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
            return
        }

        // Any user edit invalidates a visible/pending suggestion and (re)starts the idle timer, so
        // a request fires shortly after the user stops writing.
        scheduleRequest()
    }

    /**
     * Called when editor selection changes (cursor moves).
     */
    fun onSelectionChange(event: SelectionChangeEvent) {
        // Dismiss when the cursor moves so a shown suggestion or in-flight loading placeholder is
        // never left on a line the cursor has left — it must always belong to the cursor's row.
        if (suggestionState == SuggestionState.SHOWING || suggestionState == SuggestionState.LOADING) {
            dismissSuggestion()
        }
    }

    /**
     * Called for key events. Intercepts Tab, Esc, and Ctrl+Alt+C.
     *
     * @return true if event consumed, false to pass through
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        // Check for Ctrl+Alt+C (manual trigger)
        if (event.keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed && event.isAltPressed) {
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

        debounceJob?.cancel()
        launchRequest()

        log.info("Manual trigger activated")
    }

    private fun scheduleRequest() {
        // Restart the idle timer: cancel the pending timer AND any in-flight request, and clear a
        // visible loading placeholder/suggestion, so typing again restarts the 500ms wait cleanly
        // and a superseded result can't pop in.
        debounceJob?.cancel()
        requestJob?.cancel()
        if (renderer.isVisible()) {
            renderer.hide()
            editor.invalidate()
        }

        suggestionState = SuggestionState.WAITING

        // Start debounce timer using preference
        debounceJob = scope.launch {
            delay(InlineSuggestionPreferences.debounceMs.toLong())

            if (enabled && editor.isEditable) {
                launchRequest()
            }
        }
    }

    /**
     * Cancel any in-flight request and start a fresh one. Cancelling the previous request stops
     * its (now superseded) generation so it stops occupying the single LLM run-loop thread and
     * delaying this one — the main cause of suggestions arriving seconds late.
     */
    private fun launchRequest() {
        requestJob?.cancel()
        provider.cancelActiveRequest()
        // Show a grey "Loading suggestion..." placeholder at the end of the current line while the
        // request runs; it is replaced by the real suggestion (or hidden) when the request returns.
        suggestionState = SuggestionState.LOADING
        showLoadingPlaceholder()
        requestJob = scope.launch {
            requestSuggestion()
        }
    }

    /**
     * Renders a grey "Loading suggestion..." placeholder at the end of the current line, using the
     * same ghost-text style as real suggestions.
     */
    private fun showLoadingPlaceholder() {
        val cursor = editor.cursor
        val line = cursor.leftLine
        val lineEndColumn = editor.text.getColumnCount(line)
        val placeholder = SuggestionData(
            text = editor.context.getString(R.string.inline_suggestion_loading),
            startPosition = com.itsaky.androidide.models.Position(line, lineEndColumn),
            cursorLine = line,
            cursorColumn = lineEndColumn,
            requestTimestamp = System.currentTimeMillis()
        )
        renderer.show(placeholder)
        editor.invalidate()
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

            // Ignore a result that arrived after this request was superseded or dismissed.
            if (suggestionState != SuggestionState.LOADING) {
                return
            }

            if (suggestion != null) {
                currentSuggestion = suggestion
                suggestionState = SuggestionState.SHOWING
                renderer.show(suggestion)
                editor.postInvalidate()
                log.info("Suggestion shown: ${suggestion.text.take(30)}...")
            } else {
                // No suggestion — clear the loading placeholder.
                renderer.hide()
                suggestionState = SuggestionState.IDLE
                editor.postInvalidate()
                log.debug("No suggestion returned")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Superseded by a newer request — let cancellation propagate so generation stops.
            throw e
        } catch (e: Exception) {
            log.error("Error requesting suggestion", e)
            suggestionState = SuggestionState.IDLE
        }
    }

    private fun acceptSuggestion() {
        val suggestion = currentSuggestion ?: return

        suggestionState = SuggestionState.ACCEPTING

        // Capture the insertion point BEFORE inserting — text.insert advances the live cursor,
        // so reading cursor.leftLine/Column afterward double-counts and yields an out-of-bounds
        // selection.
        val text = editor.text
        val cursor = text.cursor
        val startLine = cursor.leftLine
        val startColumn = cursor.leftColumn

        text.insert(startLine, startColumn, suggestion.text)

        // Move cursor to end of inserted text, clamped to the document so an unexpected
        // suggestion shape can never crash setSelection.
        val lines = suggestion.text.split("\n")
        val endLine = (startLine + lines.size - 1).coerceIn(0, text.lineCount - 1)
        val rawEndColumn = if (lines.size == 1) {
            startColumn + lines.last().length
        } else {
            lines.last().length
        }
        val endColumn = rawEndColumn.coerceIn(0, text.getColumnCount(endLine))
        editor.setSelection(endLine, endColumn)

        // Clean up
        dismissSuggestion()

        log.info("Suggestion accepted")
    }

    private fun cleanup() {
        debounceJob?.cancel()
        requestJob?.cancel()
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

        if ((suggestionState == SuggestionState.SHOWING || suggestionState == SuggestionState.LOADING) &&
            renderer.isVisible()
        ) {
            renderer.onDraw(canvas)
        }
    }

    /**
     * Get current state (for testing).
     */
    internal fun getState(): SuggestionState = suggestionState

    private fun dismissSuggestion() {
        debounceJob?.cancel()
        requestJob?.cancel()
        currentSuggestion = null
        suggestionState = SuggestionState.IDLE
        renderer.hide()
        editor.invalidate()
    }
}
