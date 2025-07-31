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
import com.itsaky.androidide.models.HeaderItem
import com.itsaky.androidide.models.SelectableItem

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private lateinit var chipAdapter: ContextChipAdapter
    private lateinit var selectionAdapter: ContextSelectionAdapter

    // Renamed for clarity
    private val selectedContextItems = mutableListOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        setupRecyclerViews()
        populateInitialList()
        setupListeners()
        updateSelectedChips()

        parentFragmentManager.setFragmentResultListener(
            "file_selection_request",
            viewLifecycleOwner
        ) { _, bundle ->
            bundle.getStringArrayList("selected_paths")?.let { paths ->
                // Add new paths, avoiding duplicates
                paths.forEach { path ->
                    if (!selectedContextItems.contains(path)) {
                        selectedContextItems.add(path)
                    }
                }
                updateSelectedChips()
            }
        }
    }

    private fun populateInitialList() {
        val staticItems = listOf(
            HeaderItem("FILES AND FOLDERS"),
            SelectableItem(
                "browse_files",
                "Select Files/Folders from Project...",
                R.drawable.ic_folder_open
            ),
            HeaderItem("WEB SEARCH"),
            SelectableItem("web", "Search the web for an answer", R.drawable.ic_search),
            HeaderItem("GIT"),
            SelectableItem("git_status", "Git Status", R.drawable.ic_git)
        )
        selectionAdapter.submitList(staticItems)
    }

    private fun setupRecyclerViews() {
        chipAdapter = ContextChipAdapter { itemToRemove ->
            // This now handles both files and tools
            selectedContextItems.remove(itemToRemove)
            updateSelectedChips()

            // Also, uncheck the item in the main list
            val item =
                selectionAdapter.currentList.find { it is SelectableItem && it.text == itemToRemove } as? SelectableItem
            if (item != null) {
                toggleSelection(item)
            }
        }
        binding.selectedContextRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
        }

        selectionAdapter = ContextSelectionAdapter { item ->
            if (item.id == "browse_files") {
                findNavController().navigate(R.id.action_contextSelectionFragment_to_fileTreeSelectionFragment)
            } else {
                // This is the new logic to handle selection for other items
                toggleSelection(item)
            }
        }
        binding.contextItemsRecyclerView.adapter = selectionAdapter
    }

    // New helper function to handle toggling selection for tools
    private fun toggleSelection(item: SelectableItem) {
        val isNowSelected = !item.isSelected

        // Update the main list by submitting a new, modified list
        val newList = selectionAdapter.currentList.map { listItem ->
            if (listItem is SelectableItem && listItem.id == item.id) {
                listItem.copy(isSelected = isNowSelected)
            } else {
                listItem
            }
        }
        selectionAdapter.submitList(newList)

        // Update the data for the chip list
        if (isNowSelected) {
            if (!selectedContextItems.contains(item.text)) {
                selectedContextItems.add(item.text)
            }
        } else {
            selectedContextItems.remove(item.text)
        }
        updateSelectedChips()
    }

    private fun setupListeners() {
        binding.contextToolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCancelContext.setOnClickListener { findNavController().popBackStack() }
        binding.btnClearContext.setOnClickListener {
            clearAllSelections()
        }
        binding.btnConfirmContext.setOnClickListener {
            setFragmentResult("context_selection_request", Bundle().apply {
                // Use the renamed list
                putStringArrayList("selected_context", ArrayList(selectedContextItems))
            })
            findNavController().popBackStack()
        }
    }

    private fun updateSelectedChips() {
        val hasSelections = selectedContextItems.isNotEmpty()
        binding.selectedContextRecyclerView.isVisible = hasSelections
        binding.selectedContextHeader.isVisible = hasSelections
        binding.btnClearContext.isVisible = hasSelections
        chipAdapter.submitList(selectedContextItems.toList())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // New function to clear all selections
    private fun clearAllSelections() {
        selectedContextItems.clear()

        val newList = selectionAdapter.currentList.map { listItem ->
            if (listItem is SelectableItem) {
                listItem.copy(isSelected = false)
            } else {
                listItem
            }
        }
        selectionAdapter.submitList(newList)
        updateSelectedChips()
    }
}