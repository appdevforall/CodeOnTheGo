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
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import androidx.appcompat.widget.ListPopupWindow
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutLogFilterBarBinding
import com.itsaky.androidide.editor.ui.EditorSearchLayout
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.utils.BasicBuildInfo
import com.itsaky.androidide.utils.dpToPx
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BuildOutputFragment :
	NonEditableEditorFragment(),
	SearchableOutputFragment,
	ViewOptionsOutputFragment {
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

	private var editorContentGeneration = 0
	private val noMatchTracker = FilterNoMatchTracker()

	// Reads view state (bar visibility), so evaluate it on the main thread.
	private val isFilterActive: Boolean
		get() = buildOutputViewModel.filterText.value.isNotEmpty() || filterBar?.isVisible == true

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		editor?.tag = TooltipTag.PROJECT_BUILD_OUTPUT
		emptyStateViewModel.setEmptyMessage(getString(R.string.msg_emptyview_buildoutput))
		setLineNumbersEnabled(buildOutputViewModel.showLineNumbers.value)
		setupSearchLayout()

		viewLifecycleOwner.lifecycleScope.launch {
			restoreWindowFromViewModel()
			launch(Dispatchers.Default) { processLogs() }
			launch {
				val content = buildOutputViewModel.getFullContent()
				buildOutputViewModel.setCachedSnapshot(content)
			}
			launch {
				combine(
					buildOutputViewModel.filterText,
					buildOutputViewModel.showTimestamps,
					buildOutputViewModel.showDeltas,
				) { query, ts, deltas ->
					Triple(query, ts, deltas)
				}.drop(1).collectLatest { (query, ts, deltas) ->
					renderFiltered(query, ts, deltas)
				}
			}
		}
	}

	/** Re-renders the editor window from the session file, filtered by [query] and visibility options. */
	private suspend fun renderFiltered(
		query: String = buildOutputViewModel.filterText.value,
		showTimestamps: Boolean = buildOutputViewModel.showTimestamps.value,
		showDeltas: Boolean = buildOutputViewModel.showDeltas.value,
	) {
		editorContentMutex.withLock {
			editorContentGeneration++
			val window = withContext(Dispatchers.IO) { buildOutputViewModel.getWindowForEditor() }
			val filtered =
				withContext(Dispatchers.Default) {
					BuildOutputViewModel.filterLines(window, query, showTimestamps, showDeltas)
				}
			withContext(Dispatchers.Main) {
				editor?.setText(filtered)
				val isSourceEmpty = window.isBlank()
				updateEmptyState(isSourceEmpty = isSourceEmpty, isFilterActive = isFilterActive)
				if (noMatchTracker.onRender(isSourceEmpty = isSourceEmpty, isFilteredEmpty = filtered.isBlank())) {
					flashInfo(R.string.msg_no_filter_matches)
				}
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

	fun setLineNumbersEnabled(enabled: Boolean) {
		val ed = editor ?: return
		ed.setLineNumberEnabled(enabled)
		ed.setDividerWidth((if (enabled) requireContext().dpToPx(2f) else 0).toFloat())
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

	private data class ViewOptionItem(
		val title: String,
		var isChecked: Boolean,
		val onToggle: (Boolean) -> Unit,
	)

	override fun showViewOptions(anchorView: View) {
		val context = anchorView.context
		val options =
			listOf(
				ViewOptionItem(
					title = context.getString(R.string.log_filter_line_numbers),
					isChecked = buildOutputViewModel.showLineNumbers.value,
					onToggle = { enabled ->
						buildOutputViewModel.showLineNumbers.value = enabled
						setLineNumbersEnabled(enabled)
					},
				),
				ViewOptionItem(
					title = context.getString(R.string.log_filter_timestamps),
					isChecked = buildOutputViewModel.showTimestamps.value,
					onToggle = { enabled ->
						buildOutputViewModel.showTimestamps.value = enabled
					},
				),
				ViewOptionItem(
					title = context.getString(R.string.log_filter_deltas),
					isChecked = buildOutputViewModel.showDeltas.value,
					onToggle = { enabled ->
						buildOutputViewModel.showDeltas.value = enabled
					},
				),
			)

		val adapter =
			object : ArrayAdapter<String>(
				context,
				android.R.layout.simple_list_item_multiple_choice,
				options.map { it.title },
			) {
				override fun getView(
					position: Int,
					convertView: View?,
					parent: ViewGroup,
				): View {
					val view = super.getView(position, convertView, parent)
					if (view is CheckedTextView) {
						view.isChecked = options[position].isChecked
					}
					return view
				}
			}

		val popup = ListPopupWindow(context)
		popup.anchorView = anchorView
		popup.setAdapter(adapter)
		popup.width = context.dpToPx(200f)
		popup.isModal = true
		popup.setOnItemClickListener { _, _, position, _ ->
			val item = options[position]
			item.isChecked = !item.isChecked
			item.onToggle(item.isChecked)
			adapter.notifyDataSetChanged()
		}
		popup.show()
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
			onVisibilityChanged = {
				// The cached snapshot is an O(1) stand-in for the session file's emptiness.
				updateEmptyState(
					isSourceEmpty = buildOutputViewModel.getCachedContentSnapshot().isEmpty(),
					isFilterActive = isFilterActive,
				)
			},
		) { _, text ->
			buildOutputViewModel.filterText.value = text.trim()
		}.also { filterBar = it }
	}

	private suspend fun restoreWindowFromViewModel() {
		val window = withContext(Dispatchers.IO) { buildOutputViewModel.getWindowForEditor() }
		val query = buildOutputViewModel.filterText.value
		val content =
			BuildOutputViewModel.filterLines(
				window,
				query,
				buildOutputViewModel.showTimestamps.value,
				buildOutputViewModel.showDeltas.value,
			)
		val isSourceEmpty = window.isBlank()
		val isFilteredEmpty = content.isBlank()

		withContext(Dispatchers.Main) {
			updateEmptyState(isSourceEmpty = isSourceEmpty, isFilterActive = isFilterActive)
			noMatchTracker.prime(isFilteredEmpty)
			if (!isSourceEmpty && isFilteredEmpty) {
				editor?.setText("")
				onContentReplaced()
			}
		}

		if (content.isEmpty()) return
		withContext(Dispatchers.Main) {
			val editor = this@BuildOutputFragment.editor ?: return@withContext
			val layoutCompleted =
				withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
					editor.awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
				}
			if (layoutCompleted != null) {
				editor.appendBatch(content)
				updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
			} else {
				// Timeout: defer append until layout is ready so content is not lost
				val generationAtRestore = editorContentGeneration
				val job =
					viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
						editor.run {
							awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
							editorContentMutex.withLock {
								if (editorContentGeneration == generationAtRestore) {
									appendBatch(content)
									updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
								}
							}
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
		noMatchTracker.reset()
		buildOutputViewModel.clear()
		super.clearOutput()
		// super sets the empty state unconditionally; re-apply the invariant so an
		// active filter keeps the content layout (and the filter bar) reachable.
		updateEmptyState(isSourceEmpty = true, isFilterActive = isFilterActive)
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

			withContext(Dispatchers.Main) {
				updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
				if (visibleText.isEmpty()) {
					return@withContext
				}
				editor?.run {
					val layoutCompleted =
						withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
							awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
						}
					if (layoutCompleted != null) {
						appendBatch(visibleText)
						updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
					} else {
						// Timeout: defer append until layout is ready (same as restoreWindowFromViewModel)
						val generationAtFlush = editorContentGeneration
						viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
							editor?.run {
								awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
								editorContentMutex.withLock {
									if (editorContentGeneration == generationAtFlush) {
										appendBatch(visibleText)
										updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
									}
								}
							}
						}
					}
				}
			}
		}
	}
}
