package com.itsaky.androidide.fragments.sidebar

import android.app.Activity
import android.view.DragEvent
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.viewholders.FileTreeViewHolder
import com.itsaky.androidide.dnd.DragEventRouter
import com.itsaky.androidide.dnd.DropTargetCallback
import com.itsaky.androidide.dnd.hasImportableContent
import com.itsaky.androidide.utils.DropHighlighter
import com.itsaky.androidide.utils.FileImporter
import com.unnamed.b.atv.model.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


internal class FileTreeDropController(
    private val activity: Activity,
    private val onDropCompleted: (TreeNode?, File, Int) -> Unit,
    private val onDropFailed: (String) -> Unit,
) {

    private data class DropTarget(val node: TreeNode?, val file: File)

    val nodeBinder = FileTreeViewHolder.ExternalDropHandler { node, file, view ->
        bindDropTarget(view, DropTarget(node, file))
    }

    fun bindRootTarget(containerView: View, projectRootDirectory: File) {
        bindDropTarget(containerView, DropTarget(null, projectRootDirectory))
    }

    private fun bindDropTarget(view: View, target: DropTarget) {
        val dropCallback = object : DropTargetCallback {
            override fun canAcceptDrop(event: DragEvent): Boolean {
                return event.hasImportableContent(activity)
            }

            override fun onDragEntered(view: View) {
                DropHighlighter.highlight(view, activity)
            }

            override fun onDragExited(view: View) {
                DropHighlighter.clear(view)
            }

            override fun onDrop(event: DragEvent): Boolean {
                return importDroppedFiles(target, event)
            }
        }

        view.setOnDragListener(DragEventRouter(dropCallback))
    }

    private fun importDroppedFiles(target: DropTarget, event: DragEvent): Boolean {
        val context = activity.applicationContext
        val clipData = event.clipData ?: return false
        val dragPermissions = activity.requestDragAndDropPermissions(event)

        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner == null) {
            dragPermissions?.release()
            return false
        }

        lifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    FileImporter(context).copyDroppedFiles(clipData, target.file)
                }
            }

            dragPermissions?.release()

            handleImportResult(
                target = target,
                result = result.getOrNull(),
                error = result.exceptionOrNull()
            )
        }

        return true
    }

    private fun handleImportResult(
        target: DropTarget,
        result: FileImporter.ImportResult?,
        error: Throwable?
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (error != null) {
            onDropFailed(error.toReadableMessage())
            return
        }

        when (result) {
            is FileImporter.ImportResult.Success -> handleSuccess(target, result)
            is FileImporter.ImportResult.PartialSuccess -> handlePartialSuccess(target, result)
            is FileImporter.ImportResult.Failure -> onDropFailed(result.error.toReadableMessage())
            else -> {}
        }
    }

    private fun handleSuccess(target: DropTarget, result: FileImporter.ImportResult.Success) {
        if (result.count > 0) {
            onDropCompleted(target.node, target.file, result.count)
        } else {
            val noFilesMsg = activity.getString(R.string.msg_file_tree_drop_no_files)
            onDropFailed(noFilesMsg)
        }
    }

    private fun handlePartialSuccess(
        target: DropTarget,
        result: FileImporter.ImportResult.PartialSuccess
    ) {
        onDropCompleted(target.node, target.file, result.count)
        onDropFailed(result.error.toReadableMessage())
    }

    private fun Throwable.toReadableMessage(): String {
        return cause?.message
            ?: message
            ?: activity.getString(R.string.msg_file_tree_drop_import_failed)
    }
}
