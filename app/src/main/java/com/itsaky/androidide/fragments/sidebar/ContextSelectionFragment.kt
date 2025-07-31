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

    // This is our main data source now
    private val contextItems = mutableListOf<ContextListItem>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        setupRecyclerViews()
        loadProjectFiles()
        setupListeners()
        updateSelectedChips()
    }

    private fun loadProjectFiles() {
        // Perform file operations on a background thread to keep the UI responsive
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val projectRoot = IProjectManager.getInstance().projectDir
            if (projectRoot == null || !projectRoot.exists()) {
                // Handle case where no project is open
                return@launch
            }

            val fileItems = mutableListOf<ContextListItem>()
            fileItems.add(HeaderItem("FILES AND FOLDERS"))

            // Walk the project directory and create an item for each file/folder
            projectRoot.walkTopDown()
                .maxDepth(4) // Limit recursion depth to avoid too many files
                .filter { it != projectRoot } // Don't include the root folder itself
                .forEach { file ->
                    fileItems.add(
                        SelectableItem(
                            id = file.absolutePath, // Use absolute path as a unique ID
                            text = file.relativeTo(projectRoot).path, // Display the relative path
                            icon = FileExtension.Factory.forFile(file).icon
                        )
                    )
                }

            // Add your other static items
            fileItems.addAll(
                listOf(
                HeaderItem("WEB SEARCH"),
                SelectableItem("web", "Search the web for an answer", R.drawable.ic_search),
                HeaderItem("GIT"),
                SelectableItem("git_status", "Git Status", R.drawable.ic_git)
                )
            )

            // Switch back to the Main thread to update the UI
            withContext(Dispatchers.Main) {
                contextItems.clear()
                contextItems.addAll(fileItems)
                selectionAdapter.notifyDataSetChanged()
            }
        }
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