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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.adapters.SearchListAdapter
import com.itsaky.androidide.databinding.FragmentSearchResultsBinding
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.viewmodel.EditorViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class SearchResultFragment : Fragment() {
  val fragmentTooltipTag: String? = TooltipTag.PROJECT_SEARCH_RESULTS

  private var _binding: FragmentSearchResultsBinding? = null
  private val binding get() = _binding!!

  private var isKeywordExpanded = true
  private var isVectorExpanded = true

  private val editorViewModel: EditorViewModel by activityViewModels()

  private val editorActivity: BaseEditorActivity?
    get() = activity as? BaseEditorActivity

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = FragmentSearchResultsBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    setupKeywordSection()
    setupVectorSection()

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          editorViewModel.searchResults.collectLatest { results ->
            updateKeywordResults(results)
          }
        }
        launch {
          editorViewModel.vectorSearchResults.collectLatest { results ->
            updateVectorResults(results)
          }
        }
      }
    }
  }

  private fun setupKeywordSection() {
    binding.keywordHeader.sectionTitle.text = getString(R.string.search_section_keyword_results)
    binding.keywordHeader.chevronIcon.contentDescription = getString(R.string.search_section_keyword_results)
    binding.keywordHeader.root.setOnClickListener {
      isKeywordExpanded = !isKeywordExpanded
      updateKeywordVisibility()
    }

    binding.keywordResults.layoutManager = LinearLayoutManager(requireContext())
    updateKeywordVisibility()
  }

  private fun setupVectorSection() {
    binding.vectorHeader.sectionTitle.text = getString(R.string.search_section_vector_results)
    binding.vectorHeader.chevronIcon.contentDescription = getString(R.string.search_section_vector_results)
    binding.vectorHeader.root.setOnClickListener {
      isVectorExpanded = !isVectorExpanded
      updateVectorVisibility()
    }

    binding.vectorResults.layoutManager = LinearLayoutManager(requireContext())
    updateVectorVisibility()
  }

  private fun updateKeywordVisibility() {
    binding.keywordResults.visibility = if (isKeywordExpanded) View.VISIBLE else View.GONE
    binding.keywordHeader.chevronIcon.setImageResource(
      if (isKeywordExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
    )
  }

  private fun updateVectorVisibility() {
    val hasResults = binding.vectorResults.adapter?.itemCount ?: 0 > 0
    binding.vectorResults.visibility = if (isVectorExpanded && hasResults) View.VISIBLE else View.GONE
    binding.vectorPlaceholder.visibility = if (isVectorExpanded && !hasResults) View.VISIBLE else View.GONE
    binding.vectorHeader.chevronIcon.setImageResource(
      if (isVectorExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
    )
  }

  private fun updateKeywordResults(results: Map<File, List<SearchResult>>) {
    if (isAdded && _binding != null) {
      binding.keywordResults.adapter = SearchListAdapter(
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
      updateKeywordVisibility()
    }
  }

  private fun updateVectorResults(results: Map<File, List<SearchResult>>) {
    if (isAdded && _binding != null) {
      binding.vectorResults.adapter = SearchListAdapter(
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
      updateVectorVisibility()
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }

  // Backward compatibility - deprecated
  @Deprecated("No longer used with ViewModel-based approach")
  var isEmpty: Boolean = false

  @Deprecated("No longer used with ViewModel-based approach")
  fun setAdapter(adapter: SearchListAdapter) {
    // No-op: adapter is now managed by ViewModel
  }
}