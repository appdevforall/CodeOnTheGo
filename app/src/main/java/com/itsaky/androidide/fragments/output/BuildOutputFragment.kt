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
package com.itsaky.androidide.fragments.output

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutLogFilterBarBinding
import com.itsaky.androidide.editor.ui.EditorSearchLayout
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.utils.BasicBuildInfo
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BuildOutputFragment :
	NonEditableEditorFragment(),
	SearchableOutputFragment {
	private val buildOutputViewModel: BuildOutputViewModel by activityViewModels()

	companion object {
		private const val LAYOUT_TIMEOUT_MS = 2000L
	}

	override val currentEditor: IDEEditor? get() = editor

	private val logChannel = Channel<String>(Channel.UNLIMITED)

	private var searchLayout: EditorSearchLayout? = null
	private var filterBar: LogFilterBarController? = null

	// Serializes editor-content mutations (filtered re-renders vs live batch appends)
	// so a re-render never misses or duplicates a concurrently flushed batch.
	private val editorContentMutex = Mutex()

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		editor?.tag = TooltipTag.PROJECT_BUILD_OUTPUT
		emptyStateViewModel.setEmptyMessage(getString(R.string.msg_emptyview_buildoutput))
		setupSearchLayout()

		viewLifecycleOwner.lifecycleScope.launch {
			restoreWindowFromViewModel()
			launch(Dispatchers.Default) { processLogs() }
			launch {
				val content = buildOutputViewModel.getFullContent()
				buildOutputViewModel.setCachedSnapshot(content)
			}
			launch {
				buildOutputViewModel.filterText.drop(1).collectLatest { query ->
					renderFiltered(query)
				}
			}
		}
	}

	/** Re-renders the editor window from the session file, filtered by [query]. */
	private suspend fun renderFiltered(query: String) {
		editorContentMutex.withLock {
			val window = withContext(Dispatchers.IO) { buildOutputViewModel.getWindowForEditor() }
			val filtered =
				withContext(Dispatchers.Default) {
					BuildOutputViewModel.filterLines(window, query)
				}
			withContext(Dispatchers.Main) {
				editor?.setText(filtered)
				emptyStateViewModel.setEmpty(filtered.isBlank())
				onContentReplaced()
			}
		}
	}

	/** Called after the editor content has been replaced wholesale (e.g. on a filter change). */
	private fun onContentReplaced() {
		val searchLayout = this.searchLayout ?: return
		if (searchLayout.isSearchModeActive()) {
			searchLayout.refreshSearch()
		} else {
			editor?.searcher?.stopSearch()
		}
	}

	override fun beginSearch() {
		searchLayout?.beginSearchMode()
	}

	override fun toggleFilterBar() {
		val existing = filterBar
        existing?.toggle() ?: createFilterBar()
	}

	private fun setupSearchLayout() {
		val editor = this.editor ?: return
		val root = _binding?.root ?: return
		val searchLayout =
			EditorSearchLayout(
				context = requireContext(),
				editor = editor,
				showReplaceAction = false,
				applyCollapsedSheetMargin = false,
			)
		root.addView(
			searchLayout,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			),
		)
		this.searchLayout = searchLayout
	}

	private fun createFilterBar(): LogFilterBarController? {
		val stub = _binding?.filterBarStub ?: return null
		val barBinding = LayoutLogFilterBarBinding.bind(stub.inflate())
		return LogFilterBarController(
			binding = barBinding,
			coroutineScope = viewLifecycleOwner.lifecycleScope,
			showLevelChips = false,
			initialText = buildOutputViewModel.filterText.value,
			initialLevels = LogFilter.ALL_LEVELS,
		) { _, text ->
			buildOutputViewModel.filterText.value = text.trim()
		}.also { filterBar = it }
	}

	private suspend fun restoreWindowFromViewModel() {
		val window = withContext(Dispatchers.IO) { buildOutputViewModel.getWindowForEditor() }
		val content = BuildOutputViewModel.filterLines(window, buildOutputViewModel.filterText.value)
		if (content.isEmpty()) return
		withContext(Dispatchers.Main) {
			val editor = this@BuildOutputFragment.editor ?: return@withContext
			val layoutCompleted =
				withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
					editor.awaitLayout(onForceVisible = { emptyStateViewModel.setEmpty(false) })
				}
			if (layoutCompleted != null) {
				editor.appendBatch(content)
				emptyStateViewModel.setEmpty(false)
			} else {
				// Timeout: defer append until layout is ready so content is not lost
				val job =
					viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
						editor.run {
							awaitLayout(onForceVisible = { emptyStateViewModel.setEmpty(false) })
							appendBatch(content)
							emptyStateViewModel.setEmpty(false)
						}
					}
				job.join()
			}
		}
	}

	override fun onDestroyView() {
		searchLayout = null
		filterBar = null
		editor?.release()
		super.onDestroyView()
	}

	/** Clears the build output, guarding against access while the fragment is detached. */
	override fun clearOutput() {
		// Avoid forcing the activityViewModels lazy init (which calls requireActivity())
		// when the fragment is detached, otherwise an IllegalStateException is thrown.
		if (!isAdded || activity == null) return
		buildOutputViewModel.clear()
		super.clearOutput()
	}

	/** Returns the shareable build output, or an empty string when the fragment is detached. */
	override fun getShareableContent(): String {
		// Same guard as clearOutput(): touching buildOutputViewModel while detached
		// triggers requireActivity() via activityViewModels and crashes.
		if (!isAdded || activity == null) return ""
		val snapshot = buildOutputViewModel.getCachedContentSnapshot()
		return if (snapshot.isEmpty()) "" else BasicBuildInfo.shareableBuildInfo() + System.lineSeparator() + snapshot
	}

	fun appendOutput(output: String?) {
		if (!output.isNullOrEmpty()) {
			logChannel.trySend(output)
		}
	}

	/**
	 * Ensures the string ends with a newline character (`\n`).
	 * Useful for maintaining correct formatting when concatenating log lines.
	 */
	private fun String.ensureNewline(): String = if (endsWith('\n')) this else "$this\n"

	/**
	 * Immediately drains (consumes) all available messages from the channel into the [buffer].
	 *
	 * This is a **non-blocking** operation that enables batching, grouping hundreds of pending lines
	 * into a single memory operation to avoid saturating the UI queue.
	 */
	private fun ReceiveChannel<String>.drainTo(buffer: StringBuilder) {
		var result = tryReceive()
		while (result.isSuccess) {
			val line = result.getOrNull()
			if (!line.isNullOrEmpty()) {
				buffer.append(line.ensureNewline())
			}
			result = tryReceive()
		}
	}

	/**
	 * Main log orchestrator: Consumes, Batches, and Dispatches.
	 *
	 * 1. Suspends (zero CPU usage) until the first log arrives.
	 * 2. Wakes up and drains the entire queue (Batching).
	 * 3. Sends the complete block to the UI in a single pass.
	 */
	private suspend fun processLogs() =
		with(StringBuilder()) {
			for (firstLine in logChannel) {
				append(firstLine.ensureNewline())
				logChannel.drainTo(this)

				if (isNotEmpty()) {
					val batchText = toString()
					clear()
					flushToEditor(batchText)
				}
			}
		}

	/**
	 * Performs the safe UI update on the Main Thread.
	 *
	 * Appends to the session file on a background dispatcher before switching to Main.
	 * Uses [IDEEditor.awaitLayout] to guarantee the editor has physical dimensions (width > 0)
	 * before attempting to insert text, preventing the Sora library's `ArrayIndexOutOfBoundsException`.
	 */
	private suspend fun flushToEditor(text: String) {
		editorContentMutex.withLock {
			buildOutputViewModel.append(text)

			// The session file always gets the full text; the editor only shows matching lines
			val visibleText =
				BuildOutputViewModel.filterLines(text, buildOutputViewModel.filterText.value)
			if (visibleText.isEmpty()) {
				return
			}

			withContext(Dispatchers.Main) {
				editor?.run {
					val layoutCompleted =
						withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
							awaitLayout(onForceVisible = { emptyStateViewModel.setEmpty(false) })
						}
					if (layoutCompleted != null) {
						appendBatch(visibleText)
						emptyStateViewModel.setEmpty(false)
					} else {
						// Timeout: defer append until layout is ready (same as restoreWindowFromViewModel)
						viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
							editor?.run {
								awaitLayout(onForceVisible = { emptyStateViewModel.setEmpty(false) })
								appendBatch(visibleText)
								emptyStateViewModel.setEmpty(false)
							}
						}
					}
				}
			}
		}
	}
}
