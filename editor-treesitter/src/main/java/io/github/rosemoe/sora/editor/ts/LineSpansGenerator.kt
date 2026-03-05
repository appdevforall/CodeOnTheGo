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

/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2023  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.editor.ts

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSQueryCapture
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.api.TreeSitterQueryCapture
import com.itsaky.androidide.treesitter.api.safeExecQueryCursor
import io.github.rosemoe.sora.editor.ts.spans.TsSpanFactory
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spans generator for tree-sitter. Results are cached.
 *
 * Note that this implementation does not support external modifications.
 *
 * @author Rosemoe
 */
class LineSpansGenerator(internal var tree: TSTree, internal var lineCount: Int,
  private val content: Content, internal var theme: TsTheme,
  private val languageSpec: TsLanguageSpec, var scopedVariables: TsScopedVariables,
  private val spanFactory: TsSpanFactory, private val requestRedraw: () -> Unit) : Spans {

  companion object {

    const val CACHE_THRESHOLD = 60
    const val TAG = "LineSpansGenerator"
    /**
     * Delay in milliseconds to batch UI redraws, preventing frame drops
     * when rapidly calculating multiple lines.
     */
    const val REDRAW_DEBOUNCE_DELAY_MS = 150L
  }

  /**
   * Thread-safe cache for calculated line spans.
   * Automatically evicts the least recently used lines.
   */
  private val caches = LruCache<Int, MutableList<Span>>(CACHE_THRESHOLD)
  private val calculatingLines = ConcurrentHashMap.newKeySet<Int>()

  private val tsExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "TreeSitterWorker")
  }
  private val tsDispatcher = tsExecutor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + tsDispatcher)

  /**
   * Tracks content changes so the worker can instantly abort
   * outdated calculations when the user types.
   */
  private val contentVersion = AtomicInteger(0)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var isRefreshScheduled = AtomicBoolean(false)

  fun edit(edit: TSInputEdit) {
    contentVersion.incrementAndGet()
    scope.launch {
      tree.edit(edit)
      caches.evictAll()
      calculatingLines.clear()
    }
  }

  /**
   * Queues the native tree destruction in the background
   * so it doesn't close while a query is running.
   */
  fun destroy() {
    scope.cancel()
    caches.evictAll()
    calculatingLines.clear()

    tsExecutor.execute { runCatching { tree.close() } }
    tsExecutor.shutdown()
  }

  fun captureRegion(startIndex: Int, endIndex: Int): MutableList<Span> {
    val list = mutableListOf<Span>()

    if (!tree.canAccess() || tree.rootNode.hasChanges()) {
      list.add(emptySpan(0))
      return list
    }

    val captures = mutableListOf<TSQueryCapture>()

    TSQueryCursor.create().use { cursor ->
      cursor.setByteRange(startIndex * 2, endIndex * 2)

      cursor.safeExecQueryCursor(query = languageSpec.tsQuery, tree = tree,
        recycleNodeAfterUse = true, debugLogging = false,
        debugName = "LineSpansGenerator.captureRegion()") { match ->
        if (languageSpec.queryPredicator.doPredicate(languageSpec.predicates, content, match)) {
          captures.addAll(match.captures)
        }
      }

      captures.sortBy { it.node.startByte }
      var lastIndex = 0

      for (capture in captures) {
        val startByte = capture.node.startByte
        val endByte = capture.node.endByte
        val start = (startByte / 2 - startIndex).coerceAtLeast(0)
        val pattern = capture.index
        // Do not add span for overlapping regions and out-of-bounds regions
        if (start >= lastIndex && endByte / 2 >= startIndex && startByte / 2 < endIndex && (pattern !in languageSpec.localsScopeIndices && pattern !in languageSpec.localsDefinitionIndices && pattern !in languageSpec.localsDefinitionValueIndices && pattern !in languageSpec.localsMembersScopeIndices)) {
          if (start != lastIndex) {
            list.addAll(createSpans(capture, lastIndex, start - 1, theme.normalTextStyle))
          }
          var style = 0L
          if (capture.index in languageSpec.localsReferenceIndices) {
            val def = scopedVariables.findDefinition(startByte / 2, endByte / 2,
              content.substring(startByte / 2, endByte / 2))
            if (def != null && def.matchedHighlightPattern != -1) {
              style = theme.resolveStyleForPattern(def.matchedHighlightPattern)
            }
            // This reference can not be resolved to its definition
            // but it can have its own fallback color by other captures
            // so continue to next capture
            if (style == 0L) {
              continue
            }
          }
          if (style == 0L) {
            style = theme.resolveStyleForPattern(capture.index)
          }
          if (style == 0L) {
            style = theme.normalTextStyle
          }
          val end = (endByte / 2 - startIndex).coerceAtMost(endIndex)
          list.addAll(createSpans(capture, start, end, style))
          lastIndex = end
        }

        (capture as? TreeSitterQueryCapture?)?.recycle()
      }

      if (lastIndex != endIndex) {
        list.add(emptySpan(lastIndex))
      }
    }
    if (list.isEmpty()) {
      list.add(emptySpan(0))
    }
    return list
  }

  private fun createSpans(capture: TSQueryCapture, startColumn: Int, endColumn: Int,
    style: Long): List<Span> {
    val spans = spanFactory.createSpans(capture, startColumn, style)
    if (spans.size > 1) {
      var prevCol = spans[0].column
      if (prevCol > endColumn) {
        throw IndexOutOfBoundsException(
          "Span's column is out of bounds! column=$prevCol, endColumn=$endColumn")
      }
      for (i in 1..spans.lastIndex) {
        val col = spans[i].column
        if (col <= prevCol) {
          throw IllegalStateException("Spans must not overlap! prevCol=$prevCol, col=$col")
        }
        if (col > endColumn) {
          throw IndexOutOfBoundsException(
            "Span's column is out of bounds! column=$col, endColumn=$endColumn")
        }
        prevCol = col
      }
    }
    return spans
  }

  private fun emptySpan(column: Int): Span {
    return SpanFactory.obtain(column, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))
  }

  override fun adjustOnInsert(start: CharPosition, end: CharPosition) {

  }

  override fun adjustOnDelete(start: CharPosition, end: CharPosition) {

  }

  override fun read() = object : Spans.Reader {

    private var spans = mutableListOf<Span>()

    override fun moveToLine(line: Int) {
      spans = getSpansForLine(line)
    }

    override fun getSpanCount() = spans.size

    override fun getSpanAt(index: Int) = spans[index]
    override fun getSpansOnLine(line: Int): MutableList<Span> = getSpansForLine(line)

    private fun getSpansForLine(line: Int): MutableList<Span> {
      if (line !in 0..<lineCount) return mutableListOf()

      caches.get(line)?.let { return it }

      if (!calculatingLines.add(line)) return mutableListOf(emptySpan(0))

      val requestedVersion = contentVersion.get()

      scope.launch {
        try {
          if (requestedVersion != contentVersion.get()) return@launch

          val start = content.indexer.getCharPosition(line, 0).index
          val end = start + content.getColumnCount(line)

          val resultSpans = captureRegion(start, end)

          if (requestedVersion == contentVersion.get()) {
            caches.put(line, resultSpans)
            scheduleRefresh()
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error processing spans for line $line", e)
          e.printStackTrace()
        } finally {
          calculatingLines.remove(line)
        }
			}
			return mutableListOf(emptySpan(0))
    }

  }

  /**
   * Groups redraw requests together to avoid overloading the UI thread.
   */
  private fun scheduleRefresh() {
    if (!isRefreshScheduled.compareAndSet(false, true)) return

    mainHandler.postDelayed({
      isRefreshScheduled.set(false)
      requestRedraw()
      Log.d(TAG, "Refreshing UI with newly processed lines")
    }, REDRAW_DEBOUNCE_DELAY_MS)
  }

  override fun supportsModify() = false

  override fun modify(): Spans.Modifier {
    throw UnsupportedOperationException()
  }

  override fun getLineCount() = lineCount
}
