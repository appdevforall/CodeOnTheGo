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
package com.itsaky.androidide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat.Type.statusBars
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.blankj.utilcode.util.SizeUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.viewholders.FileTreeViewHolder
import com.itsaky.androidide.databinding.LayoutEditorFileTreeBinding
import com.itsaky.androidide.dnd.FileDragError
import com.itsaky.androidide.dnd.FileDragResult
import com.itsaky.androidide.dnd.FileDragStarter
import com.itsaky.androidide.eventbus.events.filetree.FileClickEvent
import com.itsaky.androidide.eventbus.events.filetree.FileLongClickEvent
import com.itsaky.androidide.events.CollapseTreeNodeRequestEvent
import com.itsaky.androidide.events.ExpandTreeNodeRequestEvent
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.tasks.TaskExecutor.executeAsync
import com.itsaky.androidide.tasks.callables.FileTreeCallable
import com.itsaky.androidide.tasks.callables.FileTreeCallable.SortFileName
import com.itsaky.androidide.tasks.callables.FileTreeCallable.SortFolder
import com.itsaky.androidide.utils.doOnApplyWindowInsets
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.FileTreeViewModel
import com.unnamed.b.atv.model.TreeNode
import com.unnamed.b.atv.model.TreeNode.TreeNodeClickListener
import com.unnamed.b.atv.model.TreeNode.TreeNodeLongClickListener
import com.unnamed.b.atv.view.AndroidTreeView
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import java.io.File
import java.util.Arrays

class FileTreeFragment : BottomSheetDialogFragment(), TreeNodeClickListener,
    TreeNodeLongClickListener, TreeNode.TreeNodeDragListener {

    private var binding: LayoutEditorFileTreeBinding? = null
    private var fileTreeView: AndroidTreeView? = null

    private val viewModel by viewModels<FileTreeViewModel>(ownerProducer = { requireActivity() })
    private val fileDragStarter by lazy(LazyThreadSafetyMode.NONE) {
        FileDragStarter(requireContext())
    }
    private var _dropController: FileTreeDropController? = null
    private val dropController: FileTreeDropController
        get() {
            if (_dropController == null) {
                _dropController = FileTreeDropController(
                    activity = requireActivity(),
                    onDropCompleted = ::onExternalDropCompleted,
                    onDropFailed = ::flashError,
                )
            }
            return _dropController!!
        }

    private val externalDropHandler: FileTreeViewHolder.ExternalDropHandler
        get() = dropController.nodeBinder

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        binding = LayoutEditorFileTreeBinding.inflate(inflater, container, false)
        binding?.root?.doOnApplyWindowInsets { view, insets, _, _ ->
            insets.getInsets(statusBars())
                .apply { view.updatePadding(top = top + SizeUtils.dp2px(8f)) }
        }
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listProjectFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        EventBus.getDefault().unregister(this)

        saveTreeState()

        binding = null
        fileTreeView = null
        _dropController = null
    }

    fun saveTreeState() {
        viewModel.saveState(fileTreeView)
    }

    override fun onClick(node: TreeNode, value: Any) {
        val file = value as File
        if (!file.exists()) {
            return
        }
        if (file.isDirectory) {
            if (node.isExpanded) {
                collapseNode(node)
            } else {
                setLoading(node)
                listNode(node) { expandNode(node) }
            }
        }
        val event = FileClickEvent(file)
        event.put(Context::class.java, requireContext())
        EventBus.getDefault().post(event)
    }

    private fun updateChevron(node: TreeNode) {
        if (node.viewHolder is FileTreeViewHolder) {
            (node.viewHolder as FileTreeViewHolder).updateChevron(node.isExpanded)
        }
    }

    private fun expandNode(node: TreeNode, animate: Boolean = true) {
        if (fileTreeView == null) {
            return
        }
        if (animate) {
            TransitionManager.beginDelayedTransition(binding!!.root, ChangeBounds())
        }
        fileTreeView!!.expandNode(node)
        updateChevron(node)
    }

    private fun collapseNode(
        node: TreeNode,
        animate: Boolean = true,
        includeSubnodes: Boolean = false
    ) {
        if (fileTreeView == null) {
            return
        }
        if (animate) {
            TransitionManager.beginDelayedTransition(binding!!.root, ChangeBounds())
        }
        fileTreeView!!.collapseNode(node, includeSubnodes)
        updateChevron(node)
    }

    private fun setLoading(node: TreeNode) {
        if (node.viewHolder is FileTreeViewHolder) {
            (node.viewHolder as FileTreeViewHolder).setLoading(true)
        }
    }

    private fun listNode(node: TreeNode, onListed: () -> Unit) {
        val safeContext = context ?: return
        val safeDropHandler = externalDropHandler

        node.children.clear()
        node.isExpanded = false
        executeAsync({
            listFilesForNode(
                node.value.listFiles() ?: return@executeAsync null,
                node,
                safeContext,
                safeDropHandler
            )
            var temp = node
            while (temp.size() == 1) {
                temp = temp.childAt(0)
                if (!temp.value.isDirectory) {
                    break
                }
                listFilesForNode(
                    temp.value.listFiles() ?: continue,
                    temp,
                    safeContext,
                    safeDropHandler
                )
                temp.isExpanded = true
            }
            null
        }) {
            onListed()
        }
    }

    private fun listFilesForNode(
        files: Array<File>,
        parent: TreeNode,
        context: Context,
        dropHandler: FileTreeViewHolder.ExternalDropHandler
    ) {
        Arrays.sort(files, SortFileName())
        Arrays.sort(files, SortFolder())
        for (file in files) {
            parent.addChild(createFileNode(file, context, dropHandler), false)
        }
    }

    private fun createFileNode(
        file: File,
        context: Context,
        dropHandler: FileTreeViewHolder.ExternalDropHandler
    ): TreeNode {
        return TreeNode(file).apply {
            viewHolder = FileTreeViewHolder(context, dropHandler)
        }
    }

    override fun onLongClick(node: TreeNode, value: Any): Boolean {
        val event = FileLongClickEvent((value as File))
        event.put(Context::class.java, requireContext())
        event.put(TreeNode::class.java, node)
        EventBus.getDefault().post(event)
        return true
    }

    override fun onStartDrag(node: TreeNode, value: Any) {
        val file = value as? File ?: return
        val sourceView = node.viewHolder?.view ?: return

        when (val result = fileDragStarter.startDrag(sourceView, file)) {
            FileDragResult.Started -> Unit

            is FileDragResult.Failed -> {
                val message = when (result.error) {
                    FileDragError.FileNotFound -> getString(R.string.msg_file_tree_drag_file_not_found)
                    FileDragError.NotAFile -> getString(R.string.msg_file_tree_drag_not_a_file)
                    FileDragError.SystemRejected -> getString(R.string.msg_file_tree_drag_failed)
                    is FileDragError.Exception -> getString(R.string.msg_file_tree_drag_error)
                }
                flashError(message)
            }
        }
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    @Subscribe(threadMode = MAIN)
    fun onGetListFilesRequested(event: ListProjectFilesRequestEvent?) {
        if (!isVisible || context == null) {
            return
        }
        listProjectFiles()
    }

    @Suppress("unused")
    @Subscribe(threadMode = MAIN)
    fun onGetExpandTreeNodeRequest(event: ExpandTreeNodeRequestEvent) {
        if (!isVisible || context == null) {
            return
        } else {
            event.node
        }
        expandNode(event.node)
    }

    @Suppress("unused")
    @Subscribe(threadMode = MAIN)
    fun onGetCollapseTreeNodeRequest(event: CollapseTreeNodeRequestEvent) {
        if (!isVisible || context == null) {
            return
        } else {
            event.node
        }
        collapseNode(event.node, event.includeSubnodes)

        setLoading(event.node)
        listNode(event.node) { expandNode(event.node) }
    }

    fun listProjectFiles() {
        if (binding == null) {
            // Fragment has been destroyed
            return
        }
        val projectDirPath = IProjectManager.getInstance().projectDirPath
        val projectDir = File(projectDirPath)
        val rootNode = TreeNode(File(""))
        rootNode.viewHolder = FileTreeViewHolder(requireContext(), externalDropHandler)

        val projectRoot = TreeNode.root(projectDir)
        projectRoot.viewHolder = FileTreeViewHolder(context, externalDropHandler)
        rootNode.addChild(projectRoot, false)

        binding!!.horizontalCroll.visibility = View.GONE
        binding!!.horizontalCroll.visibility = View.VISIBLE
        executeAsync(FileTreeCallable(context, projectRoot, projectDir, externalDropHandler)) {
            if (binding == null) {
                // Fragment has been destroyed
                return@executeAsync
            }
            binding!!.horizontalCroll.visibility = View.VISIBLE
            binding!!.loading.visibility = View.GONE
            val tree = createTreeView(rootNode)
            if (tree != null) {
                tree.setUseAutoToggle(false)
                tree.setDefaultNodeClickListener(this@FileTreeFragment)
                tree.setDefaultNodeLongClickListener(this@FileTreeFragment)
                binding!!.horizontalCroll.removeAllViews()
                val view = tree.view
                binding!!.horizontalCroll.addView(view)
                dropController.bindRootTarget(binding!!.horizontalCroll, projectDir)

                view.post { tryRestoreState(rootNode) }
            }
        }
    }

    private fun createTreeView(node: TreeNode): AndroidTreeView? {
        return if (context == null) {
            null
        } else AndroidTreeView(context, node, drawable.bg_ripple).also {
            fileTreeView = it
            it.setDefaultNodeDragListener(this)
        }
    }

    private fun tryRestoreState(rootNode: TreeNode, state: String? = viewModel.savedState) {
        if (!TextUtils.isEmpty(state) && fileTreeView != null) {
            fileTreeView!!.collapseAll()
            val openNodes =
                state!!.split(AndroidTreeView.NODES_PATH_SEPARATOR.toRegex())
                    .dropLastWhile { it.isEmpty() }
            restoreNodeState(rootNode, HashSet(openNodes))
        }

        if (rootNode.children.isNotEmpty()) {
            rootNode.childAt(0)?.let { projectRoot -> expandNode(projectRoot, false) }
        }
    }

    private fun restoreNodeState(root: TreeNode, openNodes: Set<String>) {
        for (node in root.children) {
            if (openNodes.contains(node.path)) {
                listNode(node) {
                    expandNode(node, false)
                    restoreNodeState(node, openNodes)
                }
            }
        }
    }

    private fun onExternalDropCompleted(
        targetNode: TreeNode?,
        targetFile: File,
        importedCount: Int
    ) {
        refreshNodeAfterDrop(targetNode, targetFile)
        flashSuccess(
            if (importedCount == 1) {
                getString(R.string.msg_file_tree_drop_imported_single)
            } else {
                getString(R.string.msg_file_tree_drop_imported_multiple, importedCount)
            }
        )
    }

    private fun refreshNodeAfterDrop(targetNode: TreeNode?, targetFile: File) {
        if (targetNode == null) {
            listProjectFiles()
            return
        }

        if (targetFile.isDirectory) {
            setLoading(targetNode)
            listNode(targetNode) { expandNode(targetNode) }
            return
        }

        val parentNode = targetNode.parent
        if (parentNode?.value?.isDirectory == true) {
            setLoading(parentNode)
            listNode(parentNode) { expandNode(parentNode) }
        } else {
            listProjectFiles()
        }
    }

    companion object {

        // Should be same as defined in layout/activity_layouteditor.xml
        const val TAG = "editor.fileTree"

        @JvmStatic
        fun newInstance(): FileTreeFragment {
            return FileTreeFragment()
        }
    }
}
