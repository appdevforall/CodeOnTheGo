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
import com.itsaky.androidide.viewmodel.EditorViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SearchResultFragment : RecyclerViewFragment<SearchListAdapter>() {
  override val fragmentTooltipTag: String? = TooltipTag.PROJECT_SEARCH_RESULTS

    private val editorViewModel: EditorViewModel by activityViewModels()

    private val editorActivity: BaseEditorActivity?
        get() = activity as? BaseEditorActivity

  override fun onCreateAdapter(): RecyclerView.Adapter<*> {
    val noOp: (Any) -> Unit = {}
    return SearchListAdapter(emptyMap(), noOp, noOp)
  }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                editorViewModel.searchResults.collectLatest { results ->
                    if (isAdded && _binding != null) {
                        binding.root.adapter = SearchListAdapter(
                            results,
                            onFileClick = { file ->
                                editorActivity?.doOpenFile(file, null)
                                editorActivity?.hideBottomSheet()
                            },
                            onMatchClick = { match ->
                                editorActivity?.doOpenFile(match.file, match)
                                editorActivity?.hideBottomSheet()
                            }
                        )
                        val itemCount = binding.root.adapter?.itemCount ?: 0
                        isEmpty = itemCount == 0
                    }
                }
            }
        }
    }
}