package com.itsaky.androidide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.viewholders.MultiSelectFileTreeViewHolder
import com.itsaky.androidide.databinding.FragmentFileTreeSelectionBinding
import com.itsaky.androidide.projects.IProjectManager
import com.unnamed.b.atv.model.TreeNode
import com.unnamed.b.atv.view.AndroidTreeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileTreeSelectionFragment : Fragment(R.layout.fragment_file_tree_selection) {

    private var _binding: FragmentFileTreeSelectionBinding? = null
    private val binding get() = _binding!!

    private val selectedFiles = mutableSetOf<File>()
    private var treeView: AndroidTreeView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFileTreeSelectionBinding.bind(view)
        loadTreeData()
        setupListeners()
    }

    private fun loadTreeData() {
        binding.loadingIndicator.isVisible = true
        val safeContext = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val projectRootFile = IProjectManager.getInstance().projectDir
            if (!projectRootFile.exists()) {
                binding.loadingIndicator.isVisible = false
                return@launch
            }

            val rootNode = withContext(Dispatchers.IO) {
                val node = TreeNode.root()
                buildTreeNodes(node, projectRootFile, safeContext)
                node
            }
            if (isAdded) {
                setupTreeView(rootNode)
            }
        }
    }

    private fun setupTreeView(rootNode: TreeNode) {
        binding.loadingIndicator.isVisible = false
        treeView = AndroidTreeView(requireContext(), rootNode, R.drawable.bg_ripple)
        binding.fileTreeContainer.addView(treeView!!.view)
        rootNode.children?.forEach { treeView?.expandNode(it) }
    }

    private suspend fun buildTreeNodes(parentNode: TreeNode, dir: File, context: Context) {
        dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?.forEach { file ->
                currentCoroutineContext().ensureActive()
                val node = TreeNode(file).apply {
                    viewHolder = MultiSelectFileTreeViewHolder(context, selectedFiles)
                }
                parentNode.addChild(node)
                if (file.isDirectory) {
                    buildTreeNodes(node, file, context)
                }
        }
    }

    private fun setupListeners() {
        binding.fileTreeToolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCancelSelection.setOnClickListener { findNavController().popBackStack() }
        binding.btnConfirmSelection.setOnClickListener {

            val finalContextList = mutableListOf<String>()

            val baseDir = IProjectManager.getInstance().projectDir

            selectedFiles.forEach { selectedPath ->
                val relativePath = selectedPath.relativeTo(baseDir).path
                // Create a File object by joining the project root with the relative path
                val target = File(baseDir, relativePath)

                if (target.exists() && target.isDirectory) {
                    // It's a valid directory, so walk through it and add all readable files
                    target.walkTopDown()
                        .filter { walkedFile -> walkedFile.isFile && walkedFile.canRead() }
                        .forEach { walkedFile ->
                            finalContextList.add(walkedFile.relativeTo(baseDir).path)
                        }
                } else if (target.exists() && target.isFile && target.canRead()) {
                    // It's a single, readable file
                    finalContextList.add(relativePath)
                }
            }

            setFragmentResult("file_selection_request", Bundle().apply {
                putStringArrayList("selected_paths", ArrayList(finalContextList.distinct()))
            })
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}