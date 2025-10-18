package com.itsaky.androidide.agent.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.agent.ContextChipAdapter
import com.itsaky.androidide.agent.ContextSelectionAdapter
import com.itsaky.androidide.agent.R
import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.databinding.FragmentContextSelectionBinding
import com.itsaky.androidide.models.HeaderItem
import com.itsaky.androidide.models.SelectableItem
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val BUILD_OUTPUT_TITLE = "Build output"

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private lateinit var chipAdapter: ContextChipAdapter
    private lateinit var selectionAdapter: ContextSelectionAdapter

    private val selectedContextItems = mutableListOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        // Ensure the progress bar is initially hidden
        binding.loadingProgress.isVisible = false // Use the ID from your XML

        setupRecyclerViews()
        populateInitialList()
        setupListeners()
        updateSelectedChips()

        parentFragmentManager.setFragmentResultListener(
            "file_selection_request",
            viewLifecycleOwner
        ) { _, bundle ->
            bundle.getStringArrayList("selected_paths")?.let { paths ->
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
        val staticItems =
            listOf(
                HeaderItem("FILES AND FOLDERS"),
                SelectableItem(
                    "browse_files",
                    "Select Files/Folders from Project...",
                    R.drawable.ic_folder_open
                ),
                HeaderItem("WEB SEARCH"),
                SelectableItem("web", "Search the web for an answer", R.drawable.ic_search),
                HeaderItem("GIT"),
                SelectableItem("git_status", "Git Status", R.drawable.ic_git),
                HeaderItem(BUILD_OUTPUT_TITLE),
                SelectableItem("build_output", "Build Output", R.drawable.ic_hammer)
            )
        selectionAdapter.submitList(staticItems)
    }

    private fun setupRecyclerViews() {
        chipAdapter = ContextChipAdapter { itemToRemove ->
            selectedContextItems.remove(itemToRemove)
            updateSelectedChips()

            val item =
                selectionAdapter.currentList.find { it is SelectableItem && it.text == itemToRemove }
                        as? SelectableItem
            item?.let { toggleSelection(it) }
        }
        binding.selectedContextRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
        }

        selectionAdapter = ContextSelectionAdapter { item ->
            when (item.id) {
                "browse_files" -> {
                    findNavController()
                        .navigate(R.id.action_contextSelectionFragment_to_fileTreeSelectionFragment)
                }

                else -> {
                    toggleSelection(item)
                }
            }
        }
        binding.contextItemsRecyclerView.adapter = selectionAdapter
    }

    private fun toggleSelection(item: SelectableItem) {
        val isNowSelected = !item.isSelected
        val newList =
            selectionAdapter.currentList.map { listItem ->
                if (listItem is SelectableItem && listItem.id == item.id) {
                    listItem.copy(isSelected = isNowSelected)
                } else {
                    listItem
                }
            }
        selectionAdapter.submitList(newList)

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
        binding.btnClearContext.setOnClickListener { clearAllSelections() }

        // The confirm button now triggers the processing function
        binding.btnConfirmContext.setOnClickListener { processAndConfirmSelection() }
    }

    /**
     * Shows a loader, processes selected items to gather context (file paths and build output),
     * and then returns the result to the previous fragment.
     */
    private fun processAndConfirmSelection() {
        // 1. Show loader and disable UI to prevent interaction
        binding.loadingProgress.isVisible = true
        binding.btnConfirmContext.isEnabled = false
        binding.btnCancelContext.isEnabled = false
        binding.btnClearContext.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            // 2. Process selections on a background thread (Dispatchers.IO)
            val processedContext =
                withContext(Dispatchers.IO) {
                    val finalContextList = mutableListOf<String>()
                    val baseDir = IProjectManager.getInstance().projectDir

                    selectedContextItems.forEach { itemText ->
                        when (itemText) {
                            "Build Output" -> {
                                AgentDependencies.requireToolingApi()
                                    .getBuildOutputContent()
                                    ?.let { content ->
                                        if (content.isNotBlank()) {
                                            val formattedOutput =
                                                "--- BUILD OUTPUT ---\n$content\n--- END BUILD OUTPUT ---"
                                            finalContextList.add(formattedOutput)
                                        }
                                    }
                            }

                            else -> {
                                // This is the original logic for handling file/folder paths
                                val file = File(baseDir, itemText)
                                if (file.exists() && file.isDirectory) {
                                    file.walkTopDown()
                                        .filter { it.isFile && it.canRead() }
                                        .forEach {
                                            finalContextList.add(it.relativeTo(baseDir).path)
                                        }
                                } else if (file.exists() && file.isFile && file.canRead()) {
                                    finalContextList.add(itemText)
                                }
                            }
                        }
                    }
                    // Return a distinct list of context strings
                    finalContextList.distinct()
                }

            // 3. On the main thread, set the fragment result and navigate back
            setFragmentResult(
                "context_selection_request",
                Bundle().apply {
                    putStringArrayList("selected_context", ArrayList(processedContext))
                }
            )
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

    private fun clearAllSelections() {
        selectedContextItems.clear()
        val newList =
            selectionAdapter.currentList.map { listItem ->
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
