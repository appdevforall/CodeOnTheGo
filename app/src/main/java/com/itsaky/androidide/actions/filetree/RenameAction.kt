/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.actions.filetree

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.blankj.utilcode.util.FileUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.adapters.viewholders.FileTreeViewHolder
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.tasks.doAsyncWithProgress
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.FlashType
import com.itsaky.androidide.utils.flashMessage
import com.itsaky.androidide.utils.showWithLongPressTooltip
import com.unnamed.b.atv.model.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * Action to rename the selected file.
 *
 * @author Akash Yadav
 */
class RenameAction(
    context: Context,
    override val order: Int,
) : BaseFileTreeAction(
    context,
    labelRes = R.string.rename_file,
    iconRes = R.drawable.ic_file_rename,
) {
    override val id: String = "ide.editor.fileTree.rename"

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
        TooltipTag.PROJECT_ITEM_RENAME

    @SuppressLint("ClickableViewAccessibility")
    override suspend fun execAction(data: ActionData) {
        val context = data.requireActivity()
        val file = data.requireFile()
        val lastHeld = data.getTreeNode()
        val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(context))
        val builder = DialogUtils.newMaterialDialogBuilder(context)

        val editText = binding.name.editText!!
        editText.hint = context.getString(R.string.new_name)
        editText.setText(file.name)
        editText.selectAll()

        builder.setTitle(R.string.rename_file)
        builder.setMessage(R.string.msg_rename_file)
        builder.setView(binding.root)
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.setPositiveButton(R.string.rename_file) { dialogInterface, _ ->
            dialogInterface.dismiss()
            actionScope.launch {
                doAsyncWithProgress(
                    configureFlashbar = { builder, _ ->
                        builder.message(R.string.please_wait)
                    },
                    action = { _, _ ->
                        val name: String = editText.text.toString().trim()
                        val renamed = name.length in 1..40 && FileUtils.rename(file, name)

                        if (renamed) {
                            notifyFileRenamed(file, name, context)
                        }

                        withContext(Dispatchers.Main) {
                            flashMessage(
                                if (renamed) R.string.renamed else R.string.rename_failed,
                                if (renamed) FlashType.SUCCESS else FlashType.ERROR,
                            )
                            if (!renamed) {
                                return@withContext
                            }

                            if (lastHeld != null) {
                                val parent = lastHeld.parent
                                parent.deleteChild(lastHeld)
                                val node = TreeNode(File(file.parentFile, name))
                                node.viewHolder = FileTreeViewHolder(context)
                                parent.addChild(node)
                                requestExpandNode(parent)
                            } else {
                                requestFileListing()
                            }
                        }
                    },
                )
            }
        }

        // Create and show the dialog
        val dialog = builder.showWithLongPressTooltip(
            context = context,
            tooltipTag = TooltipTag.PROJECT_RENAME_DIALOG
        )

        // Show keyboard when the dialog appears
        dialog.setOnShowListener {
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }

        // Add touch listener to the dialog's window to detect outside clicks
        dialog.window?.decorView?.setOnTouchListener { view, event ->
            // Use ACTION_DOWN to act at the beginning of the gesture
            if (event.action == MotionEvent.ACTION_DOWN) {
                val outRect = Rect()
                editText.getGlobalVisibleRect(outRect)

                // Check if the touch event is outside the bounds of the EditText
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    editText.clearFocus()
                    // Hide the keyboard
                    val imm =
                        view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
            // Return false to allow the event to be handled by other views
            false
        }
    }

    private fun notifyFileRenamed(
        file: File,
        name: String,
        context: Context,
    ) {
        val renameEvent = FileRenameEvent(file, File(file.parent, name))

        // Notify FileManager first
        FileManager.onFileRenamed(renameEvent)

        EventBus.getDefault().post(renameEvent.apply { putData(context) })
    }
}
