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
import com.itsaky.androidide.preferences.internal.EditorPreferences
import com.itsaky.androidide.utils.BasicBuildInfo
import com.itsaky.androidide.utils.dpToPx
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

	override val currentEditor: IDEEditor? get() = editor

	private val outputBuffer = BuildOutputBuffer()

	private var searchLayout: EditorSearchLayout? = null
	private var filterBar: LogFilterBarController? = null

	// Serializes editor-content mutations (filtered re-renders vs live batch appends)
	// so a re-render never misses or duplicates a concurrently flushed batch.
	private val editorContentMutex = Mutex()

	// Keeps producer-side disk appends ordered and provides an atomic restore snapshot boundary.
	private val appendMutex = Mutex()

	// Bumped on every wholesale content replacement (filtered re-render or clear) so an
	// in-flight batch flush drained before the replacement can detect it and drop itself.
	@Volatile
	private var editorContentGeneration = 0

	// Written on Main and read by the background batch processor.
	@Volatile
	private var editorSourceChars = 0
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
			launch {
				restoreWindowFromViewModel()
				withContext(Dispatchers.Default) { processLogs() }
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
		val renderGeneration =
			withContext(Dispatchers.Main) {
				editorContentGeneration++
				editorContentGeneration
			}
		val window =
			snapshotEditorWindow()
		val filtered =
			withContext(Dispatchers.Default) {
				BuildOutputViewModel.filterLines(window, query, showTimestamps, showDeltas)
			}
		withContext(Dispatchers.Main) {
			editorContentMutex.withLock {
				if (renderGeneration != editorContentGeneration) return@withLock
				val editor = editor ?: return@withLock
				editor.setText(filtered)
				editorSourceChars = BuildOutputViewModel.editorSourceCharsAfterRefresh(window.length)
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

	/**
	 * Enables or disables editor line numbers and updates the gutter divider width accordingly.
	 *
	 * @param enabled `true` to display line numbers and gutter divider, `false` to hide them.
	 */
	fun setLineNumbersEnabled(enabled: Boolean) {
		val ed = editor ?: return
		ed.setLineNumberEnabled(enabled)
		// Zero the divider with the gutter, otherwise a stray 2dp rule remains.
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
						EditorPreferences.outputLineNumbers = enabled
						buildOutputViewModel.showLineNumbers.value = enabled
						setLineNumbersEnabled(enabled)
					},
				),
				ViewOptionItem(
					title = context.getString(R.string.log_filter_timestamps),
					isChecked = buildOutputViewModel.showTimestamps.value,
					onToggle = { enabled ->
						EditorPreferences.outputTimestamps = enabled
						buildOutputViewModel.showTimestamps.value = enabled
						viewLifecycleOwner.lifecycleScope.launch {
							renderFiltered()
						}
					},
				),
				ViewOptionItem(
					title = context.getString(R.string.log_filter_deltas),
					isChecked = buildOutputViewModel.showDeltas.value,
					onToggle = { enabled ->
						EditorPreferences.outputDeltas = enabled
						buildOutputViewModel.showDeltas.value = enabled
						viewLifecycleOwner.lifecycleScope.launch {
							renderFiltered()
						}
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
				updateEmptyState(
					isSourceEmpty = buildOutputViewModel.getCachedContentSnapshot().isEmpty(),
					isFilterActive = isFilterActive,
				)
			},
		) { _, text ->
			buildOutputViewModel.filterText.value = text.trim()
		}.also { filterBar = it }
	}

	private suspend fun restoreWindowFromViewModel() =
		withContext(Dispatchers.Default) {
			val generationAtRestore = editorContentGeneration
			val window = snapshotEditorWindow()
			val content =
				BuildOutputViewModel.filterLines(
					window,
					buildOutputViewModel.filterText.value,
					buildOutputViewModel.showTimestamps.value,
					buildOutputViewModel.showDeltas.value,
				)
			fun isRestoreCurrent() = editorContentGeneration == generationAtRestore
			val isSourceEmpty = window.isBlank()
			val isFilteredEmpty = content.isBlank()

			withContext(Dispatchers.Main) {
				updateEmptyState(isSourceEmpty = isSourceEmpty, isFilterActive = isFilterActive)
				noMatchTracker.prime(isFilteredEmpty)
				if (!isSourceEmpty && isFilteredEmpty) {
					editorContentMutex.withLock {
						if (isRestoreCurrent()) {
							editor?.run {
								setText("")
								editorSourceChars =
									BuildOutputViewModel.editorSourceCharsAfterRefresh(window.length)
								onContentReplaced()
							}
						}
					}
				}
			}

			if (content.isEmpty()) return@withContext
			withContext(Dispatchers.Main) {
				val editor = this@BuildOutputFragment.editor ?: return@withContext
				val layoutCompleted =
					withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
						editor.awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
					}
				if (layoutCompleted != null) {
					editorContentMutex.withLock {
						if (isRestoreCurrent() && editor.appendBatchIfReady(content)) {
							editorSourceChars =
								BuildOutputViewModel.editorSourceCharsAfterRefresh(window.length)
							updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
						}
					}
				} else {
					// Layout timed out; keep waiting so the restored content is not lost.
					editor.awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
					editorContentMutex.withLock {
						if (isRestoreCurrent() && editor.appendBatchIfReady(content)) {
							editorSourceChars =
								BuildOutputViewModel.editorSourceCharsAfterRefresh(window.length)
							updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
						}
					}
				}
			}
		}

	override fun onDestroyView() {
		searchLayout = null
		filterBar = null
		editorContentGeneration++
		editorSourceChars = 0
		editor?.release()
		super.onDestroyView()
	}

	/** Clears the build output, guarding against access while the fragment is detached. */
	override fun clearOutput() {
		// Avoid forcing the activityViewModels lazy init (which calls requireActivity())
		// when the fragment is detached, otherwise an IllegalStateException is thrown.
		if (!isAdded || activity == null) return
		outputBuffer.clear()
		editorContentGeneration++
		editorSourceChars = 0
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
		val text = output ?: return
		if (text.isEmpty()) return
		val sessionToken = buildOutputViewModel.currentSessionToken
		lifecycleScope.launch {
			appendMutex.withLock {
				val normalized =
					withContext(Dispatchers.Default) {
						if (text.endsWith('\n')) text else "$text\n"
					}
				if (
					buildOutputViewModel.append(normalized, sessionToken) &&
					buildOutputViewModel.isCurrentSession(sessionToken)
				) {
					outputBuffer.offer(normalized, sessionToken)
				}
			}
		}
	}

	private suspend fun snapshotEditorWindow(): String =
		appendMutex.withLock {
			val snapshot = withContext(Dispatchers.IO) { buildOutputViewModel.getWindowForEditor() }
			// The snapshot already contains everything persisted before this boundary.
			outputBuffer.clear()
			buildOutputViewModel.setCachedSnapshot(snapshot)
			snapshot
		}

	/**
	 * Main log orchestrator: Consumes, Batches, and Dispatches.
	 *
	 * Suspends until bounded output is available, then sends one bounded batch to the UI.
	 */
	private suspend fun processLogs() {
		while (true) {
			val batch = outputBuffer.takeBatch()
			val editorGenAtDrain = editorContentGeneration
			try {
				flushToEditor(
					batch.text,
					batch.sourceChars,
					batch.sessionToken,
					editorGenAtDrain,
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to flush a build output batch to the editor", e)
			}
		}
	}

	/**
	 * Performs the safe UI update on the Main Thread.
	 *
	 * Applies output already persisted by the producer before switching to Main.
	 * Uses [IDEEditor.awaitLayout] to guarantee the editor has physical dimensions (width > 0)
	 * before attempting to insert text, preventing the Sora library's `ArrayIndexOutOfBoundsException`.
	 */
	private suspend fun flushToEditor(
		text: String,
		sourceChars: Int,
		sessionToken: Int,
		editorGen: Int,
	) {
		if (!buildOutputViewModel.isCurrentSession(sessionToken)) return
		val refreshEditorWindow =
			BuildOutputViewModel.wouldExceedEditorWindow(editorSourceChars, sourceChars)
		val visibleText =
			BuildOutputViewModel.filterLines(
				text,
				buildOutputViewModel.filterText.value,
				buildOutputViewModel.showTimestamps.value,
				buildOutputViewModel.showDeltas.value,
			)
		val refreshedWindow =
			if (refreshEditorWindow) {
				val window =
					appendMutex.withLock {
						val snapshot = buildOutputViewModel.getCachedContentSnapshot()
						outputBuffer.clear()
						snapshot
					}
				withContext(Dispatchers.Default) {
					Pair(
						BuildOutputViewModel.filterLines(
							window,
							buildOutputViewModel.filterText.value,
							buildOutputViewModel.showTimestamps.value,
							buildOutputViewModel.showDeltas.value,
						),
						window.length,
					)
				}
			} else {
				null
			}

		withContext(Dispatchers.Main) {
			editorContentMutex.withLock {
				if (
					editorGen != editorContentGeneration ||
					!buildOutputViewModel.isCurrentSession(sessionToken)
				) {
					return@withLock
				}
				val editor = editor ?: return@withLock
				updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
				if (refreshedWindow != null) {
					editorContentGeneration++
					editor.setText(refreshedWindow.first)
					editorSourceChars =
						BuildOutputViewModel.editorSourceCharsAfterRefresh(refreshedWindow.second)
					onContentReplaced()
					return@withLock
				}
				if (visibleText.isEmpty()) {
					editorSourceChars += sourceChars
					return@withLock
				}

				val layoutCompleted =
					withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
						editor.awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
					}
				if (layoutCompleted != null) {
					if (editor.appendBatchIfReady(visibleText)) {
						editorSourceChars += sourceChars
					}
				} else {
					editor.awaitLayout(onForceVisible = { updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive) })
					if (
						editorGen == editorContentGeneration &&
						buildOutputViewModel.isCurrentSession(sessionToken) &&
						editor.appendBatchIfReady(visibleText)
					) {
						editorSourceChars += sourceChars
						updateEmptyState(isSourceEmpty = false, isFilterActive = isFilterActive)
					}
				}
			}
		}
	}

	private fun IDEEditor.appendBatchIfReady(text: String): Boolean {
		if (!isReadyToAppend) return false
		val previousLength = this.text.length
		appendBatch(text)
		return this.text.length == previousLength + text.length
	}

	companion object {
		private const val LAYOUT_TIMEOUT_MS = 2000L
		private val log = org.slf4j.LoggerFactory.getLogger(BuildOutputFragment::class.java)
	}
}
