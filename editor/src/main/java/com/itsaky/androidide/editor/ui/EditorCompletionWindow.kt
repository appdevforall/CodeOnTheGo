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

import android.util.Log
import android.widget.ListView
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.lsp.util.DocumentationReferenceProvider
import com.itsaky.androidide.progress.ProgressManager
import com.itsaky.androidide.utils.KeyboardUtils
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.widget.component.CompletionLayout
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import kotlin.math.min

/**
 * Completion window for the editor.
 *
 * @author Akash Yadav
 */
class EditorCompletionWindow(val editor: IDEEditor) : EditorAutoCompletion(editor) {

    private var listView: ListView? = null
    private val items: MutableList<CompletionItem> = mutableListOf()

    /**
     * A scheduled-but-not-yet-started completion request, kept so a newer keystroke can cancel it.
     * See [requireCompletion].
     */
    private var pendingCompletion: Runnable? = null

    companion object {

        private val log = LoggerFactory.getLogger(EditorCompletionWindow::class.java)

        /**
         * Quiet period used to coalesce a burst of keystrokes into a single completion request. Rapid
         * typing (re)schedules the start; only after the user pauses this long does one analysis run,
         * for the latest cursor position. Keeps at most one completion in flight.
         */
        private const val COMPLETION_DEBOUNCE_MS = 80L
    }

    init {
        setLayout(EditorCompletionLayout())
        setEnabledAnimation(true)
    }

    override fun isShowing(): Boolean {
        @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
        return popup?.isShowing ?: false
    }

    override fun setLayout(layout: CompletionLayout) {
        super.setLayout(layout)
        (layout.completionList as? ListView)?.let {
            listView = it
            it.adapter = this.adapter
            it.setOnItemLongClickListener { _, view, position, _ ->

                val category = when (editor.file?.extension) {
                    "java" -> TooltipCategory.CATEGORY_JAVA
                    "kt" -> TooltipCategory.CATEGORY_KOTLIN
                    "xml" -> TooltipCategory.CATEGORY_XML
                    else -> TooltipCategory.CATEGORY_IDE
                }

                val completionItem =
                    items[position] as? com.itsaky.androidide.lsp.models.CompletionItem
                val completionData = completionItem?.data

                val tag = if (completionData == null) {
                    val label = completionItem?.ideLabel ?: ""
                    val attrName = if (label.contains(':')) label.substringAfterLast(':') else label
                    attrName.ifEmpty { null }
                } else {
                    DocumentationReferenceProvider.getTag(completionData)
                }

                // Dismiss the completion window before showing tooltip
                hide()

                Log.d("EditorCompletionWindow", "Showing tooltip for tag: $tag category: $category")
                TooltipManager.showTooltip(
                    context = editor.context,
                    anchorView = editor,
                    category = category,
                    tag = tag ?: "",
                )
                true
            }
        }
    }

    override fun select(pos: Int): Boolean {
        if (pos > adapter!!.count) {
            return false
        }
        return try {
            super.select(pos)
        } catch (e: Throwable) {
            log.warn("Unable to select completion item at {}", pos, e)
            false
        }
    }

    override fun select(): Boolean {
        return try {
            super.select()
        } catch (e: Throwable) {
            log.warn("Unable to select completion item", e)
            false
        }
    }

    override fun cancelCompletion() {
        // Drop any request that was scheduled but hasn't started yet.
        pendingCompletion?.let { editor.handler.removeCallbacks(it) }
        pendingCompletion = null
        if (completionThread != null) {
            ProgressManager.instance.cancel(completionThread)
        }
        super.cancelCompletion()
    }

    /**
     * Whether a completion may be shown for the current editor state. Hides the window (matching the
     * prior inline behaviour) when the cursor is selected or completion is otherwise not applicable.
     */
    private fun canStartCompletion(): Boolean {
        if (cancelShowUp || !isEnabled || !editor.isAttachedToWindow) {
            return false
        }
        if (editor.text.cursor.isSelected || checkNoCompletion()) {
            hide()
            return false
        }
        return true
    }

    override fun requireCompletion() {
        if (!canStartCompletion()) {
            return
        }

        // Coalesce a burst of keystrokes into a single completion. Cancel the in-flight completion and
        // any pending (not-yet-started) one, then (re)schedule one start after a short quiet period.
        // This guarantees at most one completion analysis in flight and that only the latest cursor
        // position is computed — preventing the CompletionThread/allocation pile-up that saturated the
        // heap and froze the editor during fast typing. cancelCompletion() clears any pending request,
        // so we always schedule exactly one.
        cancelCompletion()

        val request = Runnable { startCompletion() }
        pendingCompletion = request
        editor.handler.postDelayed(request, COMPLETION_DEBOUNCE_MS)
    }

    /** Starts a single completion for the current cursor position. Runs on the UI thread. */
    private fun startCompletion() {
        pendingCompletion = null

        // The editor state may have changed during the debounce delay; re-check the guards.
        if (!canStartCompletion()) {
            return
        }

        requestTime = System.nanoTime()
        currentSelection = -1

        publisher =
            IDECompletionPublisher(
                editor.handler,
                {
                    val items = publisher.items

                    this.items.apply {
                        clear()
                        addAll(items)
                    }

                    if (lastAttachedItems == null || lastAttachedItems.get() != items) {
                        adapter.attachValues(this, items)
                        adapter.notifyDataSetInvalidated()
                        lastAttachedItems = WeakReference(items)
                    } else {
                        adapter.notifyDataSetChanged()
                    }

                    val newHeight = (adapter!!.itemHeight * adapter!!.count).toFloat()
                    if (newHeight == 0F) {
                        hide()
                    }

                    editor.getComponent(EditorAutoCompletion::class.java)
                        .updateCompletionWindowPosition()
                    setSize(width, min(newHeight, maxHeight.toFloat()).toInt())
                    if (!isShowing) {
                        show()
                    }

                    if (adapter!!.count >= 1
                        && KeyboardUtils.isHardKeyboardConnected(context)
                    ) {
                        currentSelection = 0
                    }
                },
                editor.editorLanguage.interruptionLevel
            )

        publisher.setUpdateThreshold(1)

        completionThread = CompletionThread(requestTime, publisher)
        completionThread.name = "CompletionThread-$requestTime"

        setLoading(true)

        completionThread.start()
    }

}
