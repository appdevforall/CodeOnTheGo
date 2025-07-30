package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.ContextChipAdapter
import com.itsaky.androidide.adapters.ContextSelectionAdapter
import com.itsaky.androidide.databinding.FragmentContextSelectionBinding
import com.itsaky.androidide.models.ContextListItem
import com.itsaky.androidide.models.HeaderItem
import com.itsaky.androidide.models.SelectableItem

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private lateinit var chipAdapter: ContextChipAdapter
    private lateinit var selectionAdapter: ContextSelectionAdapter

    // This is our main data source now
    private val contextItems = mutableListOf<ContextListItem>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        createMockData()
        setupRecyclerViews()
        setupListeners()
        updateSelectedChips()
    }

    private fun createMockData() {
        contextItems.clear()
        contextItems.addAll(
            listOf(
                HeaderItem("FILES AND FOLDERS"),
                SelectableItem(
                    "file_1",
                    "app/src/main/java/.../MainActivity.kt",
                    R.drawable.ic_language_kotlin
                ),
                SelectableItem(
                    "file_2",
                    "app/src/main/res/layout/activity_main.xml",
                    R.drawable.ic_language_xml
                ),
                HeaderItem("WEB SEARCH"),
                SelectableItem("web", "Search the web for an answer", R.drawable.ic_search),
                HeaderItem("GIT"),
                SelectableItem("git_status", "Git Status", R.drawable.ic_git)
            )
        )
    }

    private fun setupRecyclerViews() {
        // Adapter for the top row of selected chips
        chipAdapter = ContextChipAdapter { itemToRemove ->
            toggleSelectionById(findItemIdByText(itemToRemove))
        }
        binding.selectedContextRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
        }

        // Adapter for the main list of selectable items
        selectionAdapter = ContextSelectionAdapter(contextItems) { item ->
            toggleSelectionById(item.id)
        }
        binding.contextItemsRecyclerView.adapter = selectionAdapter
    }

    private fun setupListeners() {
        binding.contextToolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCancelContext.setOnClickListener { findNavController().popBackStack() }
        binding.btnConfirmContext.setOnClickListener {
            val selectedIds = contextItems.filterIsInstance<SelectableItem>()
                .filter { it.isSelected }
                .map { it.text } // Pass back the display text

            setFragmentResult("context_selection_request", Bundle().apply {
                putStringArrayList("selected_context", ArrayList(selectedIds))
            })
            findNavController().popBackStack()
        }
    }

    private fun findItemIdByText(text: String): String? {
        return contextItems.filterIsInstance<SelectableItem>().find { it.text == text }?.id
    }

    private fun toggleSelectionById(itemId: String?) {
        itemId ?: return
        val itemIndex = contextItems.indexOfFirst { it is SelectableItem && it.id == itemId }
        if (itemIndex != -1) {
            val item = contextItems[itemIndex] as SelectableItem
            item.isSelected = !item.isSelected
            selectionAdapter.notifyItemChanged(itemIndex)
            updateSelectedChips()
        }
    }

    private fun updateSelectedChips() {
        val selected = contextItems.filterIsInstance<SelectableItem>()
            .filter { it.isSelected }
            .map { it.text }
            .toSet()

        binding.selectedContextRecyclerView.isVisible = selected.isNotEmpty()
        binding.selectedContextHeader.isVisible = selected.isNotEmpty()

        chipAdapter.submitList(selected.toList())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}