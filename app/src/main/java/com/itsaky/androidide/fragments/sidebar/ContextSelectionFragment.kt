package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.ContextChipAdapter
import com.itsaky.androidide.adapters.ContextSelectionAdapter
import com.itsaky.androidide.databinding.FragmentContextSelectionBinding
import com.itsaky.androidide.models.ContextListItem
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.HeaderItem
import com.itsaky.androidide.models.SelectableItem
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private lateinit var chipAdapter: ContextChipAdapter
    private lateinit var selectionAdapter: ContextSelectionAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        setupRecyclerViews()
        loadProjectFiles() // Replaced createMockData()
        setupListeners()
        updateSelectedChips()
    }

    private fun loadProjectFiles() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val projectRoot = IProjectManager.getInstance().projectDir
            if (!projectRoot.exists()) {
                // You can optionally show an empty/error state here
                return@launch
            }

            val newItems = mutableListOf<ContextListItem>()
            newItems.add(HeaderItem("FILES AND FOLDERS"))

            val fileItems = projectRoot.walkTopDown()
                .maxDepth(4)
                .filter { it != projectRoot }
                .map { file ->
                    SelectableItem(
                        id = file.absolutePath,
                        text = file.relativeTo(projectRoot).path,
                        icon = FileExtension.Factory.forFile(file).icon
                    )
                }.sortedBy { it.text }
            newItems.addAll(fileItems)

            newItems.addAll(
                listOf(
                    HeaderItem("WEB SEARCH"),
                    SelectableItem("web", "Search the web for an answer", R.drawable.ic_search),
                    HeaderItem("GIT"),
                    SelectableItem("git_status", "Git Status", R.drawable.ic_git)
                )
            )

            withContext(Dispatchers.Main) {
                // Submit the new list. DiffUtil will handle the updates efficiently.
                selectionAdapter.submitList(newItems)
            }
        }
    }

    private fun setupRecyclerViews() {
        chipAdapter = ContextChipAdapter { itemToRemove ->
            toggleSelectionById(findItemIdByText(itemToRemove))
        }
        binding.selectedContextRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
        }

        // Adapter is now instantiated without an initial list
        selectionAdapter = ContextSelectionAdapter { item ->
            toggleSelectionById(item.id)
        }
        binding.contextItemsRecyclerView.adapter = selectionAdapter
    }

    private fun setupListeners() {
        binding.contextToolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCancelContext.setOnClickListener { findNavController().popBackStack() }
        binding.btnConfirmContext.setOnClickListener {
            // Get the list of selected items directly from the adapter
            val selectedPaths = selectionAdapter.currentList
                .filterIsInstance<SelectableItem>()
                .filter { it.isSelected }
                .map { it.text } // text is the relative path

            setFragmentResult("context_selection_request", Bundle().apply {
                putStringArrayList("selected_context", ArrayList(selectedPaths))
            })
            findNavController().popBackStack()
        }
    }

    private fun findItemIdByText(text: String): String? {
        return selectionAdapter.currentList.filterIsInstance<SelectableItem>()
            .find { it.text == text }?.id
    }

    private fun toggleSelectionById(itemId: String?) {
        itemId ?: return

        // Create a new list with the updated item
        val updatedList = selectionAdapter.currentList.map { item ->
            if (item is SelectableItem && item.id == itemId) {
                item.copy(isSelected = !item.isSelected) // Create a new object with the toggled state
            } else {
                item
            }
        }

        // Submit the new list to the adapter
        selectionAdapter.submitList(updatedList) {
            // This callback runs after the list is updated, ensuring chips are in sync.
            updateSelectedChips()
        }
    }

    private fun updateSelectedChips() {
        val selected = selectionAdapter.currentList
            .filterIsInstance<SelectableItem>()
            .filter { it.isSelected }
            .map { it.text }

        binding.selectedContextRecyclerView.isVisible = selected.isNotEmpty()
        binding.selectedContextHeader.isVisible = selected.isNotEmpty()

        chipAdapter.submitList(selected)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}