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
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.adapters.SearchListAdapter
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.viewmodel.EditorViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

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

	override fun onCreateAdapter(): RecyclerView.Adapter<*> = SearchListAdapter(onFileClick, onMatchClick)

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				editorViewModel.searchResultSections.collectLatest { sections ->
					if (isAdded && _binding != null) {
						// Reuse the attached adapter so re-publishes diff instead of resetting
						// scroll and re-running highlights; only create one if none is present.
						val adapter =
							binding.root.adapter as? SearchListAdapter
								?: SearchListAdapter(onFileClick, onMatchClick).also { binding.root.adapter = it }
						adapter.submit(sections) { isEmpty = adapter.itemCount == 0 }
					}
				}
			}
		}
	}
}
