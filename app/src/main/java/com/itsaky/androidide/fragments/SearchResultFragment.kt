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
package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.adapters.SearchListAdapter
import com.itsaky.androidide.databinding.FragmentSearchResultsReplaceBarBinding
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.search.replace.ReplaceHost
import com.itsaky.androidide.search.replace.ReplaceSession
import com.itsaky.androidide.viewmodel.EditorViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import com.itsaky.androidide.resources.R as ResR

class SearchResultFragment : RecyclerViewFragment<SearchListAdapter>() {
	override val fragmentTooltipTag: String? = TooltipTag.PROJECT_SEARCH_RESULTS

	private val editorViewModel: EditorViewModel by activityViewModels()

	private val editorActivity: BaseEditorActivity?
		get() = activity as? BaseEditorActivity

	private val onFileClick: (File) -> Unit = { file ->
		editorActivity?.doOpenFile(file, null)
		editorActivity?.hideBottomSheet()
	}

	private val onMatchClick: (SearchResult) -> Unit = { match ->
		editorActivity?.doOpenFile(match.file, match)
		editorActivity?.hideBottomSheet()
	}

	private var replaceBar: FragmentSearchResultsReplaceBarBinding? = null

	private fun newAdapter() =
		SearchListAdapter(
			onFileClick = onFileClick,
			onMatchClick = onMatchClick,
			onToggleMatch = editorViewModel::toggleReplaceMatch,
			onToggleFile = editorViewModel::toggleReplaceFile,
		)

	override fun onCreateAdapter(): RecyclerView.Adapter<*> = newAdapter()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val content = super.onCreateView(inflater, container, savedInstanceState)
		val bar = FragmentSearchResultsReplaceBarBinding.inflate(inflater, container, false)
		replaceBar = bar
		return LinearLayout(inflater.context).apply {
			orientation = LinearLayout.VERTICAL
			addView(bar.root, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
			addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
		}
	}

	override fun onDestroyView() {
		replaceBar = null
		super.onDestroyView()
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				combine(editorViewModel.searchResultSections, editorViewModel.replaceSession) { sections, session ->
					sections to session
				}.collectLatest { (sections, session) ->
					if (isAdded && _binding != null) {
						// Reuse the attached adapter so re-publishes diff instead of resetting
						// scroll and re-running highlights; only create one if none is present.
						val adapter =
							binding.root.adapter as? SearchListAdapter
								?: newAdapter().also { binding.root.adapter = it }
						adapter.submit(sections, session) { isEmpty = adapter.itemCount == 0 }
						bindReplaceBar(session)
					}
				}
			}
		}
	}

	private fun bindReplaceBar(session: ReplaceSession?) {
		val bar = replaceBar ?: return
		bar.root.isVisible = session != null
		session ?: return
		bar.summary.text =
			resources.getQuantityString(
				ResR.plurals.msg_replace_n_matches,
				session.includedCount,
				session.includedCount,
				session.includedFileCount,
			)
		bar.replace.isEnabled = session.includedCount > 0
		bar.replace.setOnClickListener { (activity as? ReplaceHost)?.onReplaceRequested(session) }
		bar.cancel.setOnClickListener { editorViewModel.clearReplaceSession() }
	}
}
