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
