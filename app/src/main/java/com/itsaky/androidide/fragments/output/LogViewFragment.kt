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
import androidx.annotation.UiThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentLogBinding
import com.itsaky.androidide.databinding.LayoutLogFilterBarBinding
import com.itsaky.androidide.editor.language.treesitter.LogLanguage
import com.itsaky.androidide.editor.language.treesitter.TreeSitterLanguageProvider
import com.itsaky.androidide.editor.schemes.IDEColorScheme
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.editor.ui.EditorSearchLayout
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.models.LogLine
import com.itsaky.androidide.utils.BasicBuildInfo
import com.itsaky.androidide.utils.isTestMode
import com.itsaky.androidide.utils.jetbrainsMono
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.LogViewModel
import io.github.rosemoe.sora.widget.style.CursorAnimator
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Fragment to show logs.
 *
 * @author Akash Yadav
 */
abstract class LogViewFragment<V : LogViewModel> :
	EmptyStateFragment<FragmentLogBinding>(R.layout.fragment_log, FragmentLogBinding::bind),
	ShareableOutputFragment,
	SearchableOutputFragment {
	companion object {
		private val log = LoggerFactory.getLogger(LogViewFragment::class.java)
	}

	override val currentEditor: IDEEditor? get() = _binding?.editor

	open val tooltipTag = ""

	abstract val viewModel: V

	private var searchLayout: EditorSearchLayout? = null
	private var filterBar: LogFilterBarController? = null

	/**
	 * Append a log line to the log view.
	 *
	 * @param line The log line to append.
	 */
	fun appendLog(line: LogLine) = viewModel.submit(line = line, simpleFormattingEnabled = isSimpleFormattingEnabled())

	/**
	 * Append a log line to the log view.
	 *
	 * @param line The log line to append.
	 */
	protected fun appendLine(line: String): Unit = viewModel.submit(line)

	abstract fun isSimpleFormattingEnabled(): Boolean

	override fun onDestroyView() {
		searchLayout = null
		filterBar = null
		_binding?.editor?.release()
		super.onDestroyView()
	}

	override fun beginSearch() {
		searchLayout?.beginSearchMode()
	}

	override fun toggleFilterBar() {
		val existing = filterBar
		existing?.toggle() ?: createFilterBar()
	}

	private fun createFilterBar(): LogFilterBarController? {
		val stub = _binding?.filterBarStub ?: return null
		val barBinding = LayoutLogFilterBarBinding.bind(stub.inflate())
		val currentFilter = viewModel.filter.value
		return LogFilterBarController(
			binding = barBinding,
			coroutineScope = viewLifecycleScope,
			showLevelChips = true,
			initialText = currentFilter.text,
			initialLevels = currentFilter.enabledLevels,
		) { levels, text ->
			viewModel.setFilter(LogFilter(levels, text.trim()))
		}.also { filterBar = it }
	}

	override fun getShareableContent(): String {
		// Share the full retained history, not the (possibly filtered) editor text
		val logText = viewModel.snapshotUnfiltered()
		return "${BasicBuildInfo.shareableBuildInfo()}${System.lineSeparator()}$logText"
	}

	override fun clearOutput() {
		viewModel.clear()
		_binding?.editor?.setText("")?.also {
			emptyStateViewModel.setEmpty(true)
		}
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		setupEditor()
		setupSearchLayout()

		viewLifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					observeLogs()
				}
			}
		}
	}

	private suspend fun observeLogs() {
		// Wait for the editor's first layout pass. The sora-editor's
		// LineBreakLayout populates its line-width tracker asynchronously after
		// layout; appending before that races BlockIntList.set on an empty list.
		_binding?.editor?.awaitLayout(
			onForceVisible = { emptyStateViewModel.setEmpty(false) },
		)

		viewModel.uiEvents.collect { event ->
			when (event) {
				is LogViewModel.UiEvent.SetText -> {
					setText(event.text)
				}

				is LogViewModel.UiEvent.Append -> {
					append(event.text)
					trimLinesAtStart()
				}
			}
		}
	}

	/** Called after the editor content has been replaced wholesale (e.g. on a filter change). */
	private fun onContentReplaced() {
		val searchLayout = this.searchLayout ?: return
		if (searchLayout.isSearchModeActive()) {
			searchLayout.refreshSearch()
		} else {
			_binding?.editor?.searcher?.stopSearch()
		}
	}

	private fun setupSearchLayout() {
		val searchLayout =
			EditorSearchLayout(
				context = requireContext(),
				editor = binding.editor,
				showReplaceAction = false,
				applyCollapsedSheetMargin = false,
			)
		binding.root.addView(
			searchLayout,
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			),
		)
		this.searchLayout = searchLayout
	}

	private fun setupEditor() {
		val editor = this.binding.editor
		editor.props.autoIndent = false
		editor.isEditable = false
		editor.dividerWidth = 0f
		editor.isWordwrap = true
		editor.isUndoEnabled = false
		editor.typefaceLineNumber = jetbrainsMono()
		editor.setTextSize(12f)
		editor.typefaceText = jetbrainsMono()
		editor.isEnsurePosAnimEnabled = false
		editor.tag = tooltipTag
		editor.cursorAnimator = NoOpCursorAnimator

		// Skip tree-sitter language setup during tests to avoid native library issues
		if (!isTestMode()) {
			IDEColorSchemeProvider.readSchemeAsync(
				context = requireContext(),
				coroutineScope = editor.editorScope,
				type = LogLanguage.TS_TYPE,
			) { scheme ->
				val language =
					TreeSitterLanguageProvider.forType(LogLanguage.TS_TYPE, requireContext())
				if (language != null) {
					if (scheme is IDEColorScheme) {
						language.setupWith(scheme)
					}
					editor.applyTreeSitterLang(language, LogLanguage.TS_TYPE, scheme)
				}
			}
		}
	}

	@UiThread
	private fun setText(text: String) {
		val editor = _binding?.editor ?: return
		editor.setText(text)
		emptyStateViewModel.setEmpty(text.isBlank())
		onContentReplaced()
	}

	@UiThread
	private fun append(chars: CharSequence?) {
		if (chars == null) {
			return
		}

		val editor = _binding?.editor ?: return
		if (!editor.isReadyToAppend) return
		editor.appendBatch(chars.toString())
		emptyStateViewModel.setEmpty(false)
	}

	@UiThread
	private fun trimLinesAtStart() {
		_binding?.editor?.text?.apply {
			if (lineCount <= LogViewModel.TRIM_ON_LINE_COUNT) {
				return@apply
			}

			val lastLine = lineCount - LogViewModel.MAX_LINE_COUNT
			log.debug("Deleting log text till line {}", lastLine)
			delete(0, 0, lastLine, getColumnCount(lastLine))
		}
	}
}

private object NoOpCursorAnimator : CursorAnimator {
	override fun markStartPos() {}

	override fun markEndPos() {}

	override fun start() {}

	override fun cancel() {}

	override fun isRunning(): Boolean = false

	override fun animatedX(): Float = 0f

	override fun animatedY(): Float = 0f

	override fun animatedLineHeight(): Float = 0f

	override fun animatedLineBottom(): Float = 0f
}
