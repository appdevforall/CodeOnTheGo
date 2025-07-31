package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.ContextSelectionAdapter
import com.itsaky.androidide.adapters.viewholders.MultiSelectFileTreeViewHolder
import com.itsaky.androidide.databinding.FragmentContextSelectionBinding
import com.itsaky.androidide.models.ContextListItem
import com.itsaky.androidide.models.HeaderItem
import com.itsaky.androidide.models.SelectableItem
import com.itsaky.androidide.projects.IProjectManager
import com.unnamed.b.atv.model.TreeNode
import com.unnamed.b.atv.view.AndroidTreeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private val selectedFiles = mutableSetOf<File>()
    private var treeView: AndroidTreeView? = null
    private lateinit var selectionAdapter: ContextSelectionAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        binding.selectedContextHeader.isVisible = false
        binding.selectedContextRecyclerView.isVisible = false
        setupRecyclerViews()
        loadProjectFiles()
        loadTreeData()
        setupListeners()
    }

    private fun loadProjectFiles() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val projectRoot = IProjectManager.getInstance().projectDir
            if (!projectRoot.exists()) {
                // You can optionally show an empty/error state here
                return@launch
            }

            val newItems = mutableListOf<ContextListItem>()
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
        // Adapter is now instantiated without an initial list
        selectionAdapter = ContextSelectionAdapter { item ->
            toggleSelectionById(item.id)
        }
        binding.contextItemsRecyclerView.adapter = selectionAdapter
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

        }
    }

    private fun loadTreeData() {
        binding.loadingIndicator.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val projectRootFile = IProjectManager.getInstance().projectDir
            if (projectRootFile == null || !projectRootFile.exists()) {
                binding.loadingIndicator.isVisible = false
                return@launch
            }

            val rootNode = withContext(Dispatchers.IO) {
                val node = TreeNode.root()
                buildTreeNodes(node, projectRootFile)
                node
            }

            setupTreeView(rootNode)
        }
    }

    private fun setupTreeView(rootNode: TreeNode) {
        binding.loadingIndicator.isVisible = false

        treeView = AndroidTreeView(requireContext(), rootNode, R.drawable.bg_ripple)
        // The ViewHolder handles all click logic now.

        binding.fileTreeContainer.addView(treeView!!.view)
        rootNode.children?.forEach { treeView?.expandNode(it) }
    }

    private fun buildTreeNodes(parentNode: TreeNode, dir: File) {
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { file ->
            val node = TreeNode(file).apply {
                viewHolder = MultiSelectFileTreeViewHolder(requireContext(), selectedFiles)
            }
            parentNode.addChild(node)

            if (file.isDirectory) {
                buildTreeNodes(node, file)
            }
        }
    }

    private fun setupListeners() {
        binding.contextToolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCancelContext.setOnClickListener { findNavController().popBackStack() }
        binding.btnConfirmContext.setOnClickListener {
            val projectRoot = IProjectManager.getInstance().projectDir
            val selectedPaths = selectedFiles.map { it.relativeTo(projectRoot).path }

            setFragmentResult("context_selection_request", Bundle().apply {
                putStringArrayList("selected_context", ArrayList(selectedPaths))
            })
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}