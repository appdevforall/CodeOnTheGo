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

    companion object {

        private val log = LoggerFactory.getLogger(EditorCompletionWindow::class.java)
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
                    if (category == TooltipCategory.CATEGORY_XML && attrName.isNotEmpty() && !label.contains('.')) {
                        "xml.attr.$attrName"
                    } else {
                        attrName.ifEmpty { null }
                    }
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
        if (completionThread != null) {
            ProgressManager.instance.cancel(completionThread)
        }
        super.cancelCompletion()
    }

    override fun requireCompletion() {
        if (cancelShowUp || !isEnabled || !editor.isAttachedToWindow) {
            return
        }

        val text = editor.text
        if (text.cursor.isSelected || checkNoCompletion()) {
            hide()
            return
        }

        if (System.nanoTime() - requestTime < editor.props.cancelCompletionNs) {
            hide()
            requestTime = System.nanoTime()
            return
        }

        cancelCompletion()
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
