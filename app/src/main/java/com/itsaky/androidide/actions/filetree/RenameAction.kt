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

package com.itsaky.androidide.actions.filetree

import android.content.Context
import android.content.DialogInterface
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.FileUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.adapters.viewholders.FileTreeViewHolder
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.tasks.launchAsyncWithProgress
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.FlashType
import com.itsaky.androidide.utils.TooltipUtils
import com.itsaky.androidide.utils.flashMessage
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
class RenameAction(context: Context, override val order: Int) :
  BaseFileTreeAction(
    context,
    labelRes = R.string.rename_file,
    iconRes = R.drawable.ic_file_rename
  ) {

  override val id: String = "ide.editor.fileTree.rename"

  override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.PROJECT_ITEM_RENAME

  override suspend fun execAction(data: ActionData) {
    val context = data.requireActivity()
    val file = data.requireFile()
    val lastHeld = data.getTreeNode()
    val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(context))
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    binding.name.editText!!.hint =
      context.getString(com.itsaky.androidide.resources.R.string.new_name)
    binding.name.editText!!.setText(file.name)
    builder.setTitle(com.itsaky.androidide.resources.R.string.rename_file)
    builder.setMessage(com.itsaky.androidide.resources.R.string.msg_rename_file)
    builder.setView(binding.root)
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.setPositiveButton(com.itsaky.androidide.resources.R.string.rename_file) {
      dialogInterface,
      _ ->
      dialogInterface.dismiss()
      actionScope.launchAsyncWithProgress(
          configureFlashbar = { builder, cancelChecker ->
            builder.message(com.itsaky.androidide.resources.R.string.please_wait)
          },
          action = { _, _ ->
            val name: String = binding.name.editText!!.text.toString().trim()
            val renamed = name.length in 1..40 && FileUtils.rename(file, name)

            if (renamed) {
              notifyFileRenamed(file, name, context)
            }

            withContext(Dispatchers.Main) {
              flashMessage(
                  if (renamed) com.itsaky.androidide.resources.R.string.renamed
                  else com.itsaky.androidide.resources.R.string.rename_failed,
                  if (renamed) FlashType.SUCCESS else FlashType.ERROR)
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
          })
    }
    val dialog = builder.create()
    dialog.show()
    // 1. Create the Gesture Detector.
    val gestureDetector =
      GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
          dialog.dismiss()

          val tooltipTag = TooltipTag.PROJECT_RENAME_DIALOG
          val lifecycleOwner = context as? LifecycleOwner ?: return
          lifecycleOwner.lifecycleScope.launch {
            try {
              val tooltipData = withContext(Dispatchers.IO) {
                TooltipManager.getTooltip(context, TooltipCategory.CATEGORY_IDE, tooltipTag)
              }
              tooltipData?.let {
                TooltipUtils.showIDETooltip(
                  context = context,
                  level = 0,
                  tooltipItem = tooltipData,
                  anchorView = context.window.decorView
                )
              }
            } catch (e: Exception) {
              Log.e("Tooltip", "Error showing tooltip for $tooltipTag", e)
            }
          }
        }
      })

    // 2. Define the accessibility-friendly OnTouchListener.
    val universalTouchListener = View.OnTouchListener { view, event ->
      val wasGestureHandled = gestureDetector.onTouchEvent(event)
      if (event.action == MotionEvent.ACTION_UP && !wasGestureHandled) {
        // Manually call performClick to trigger the standard click action
        // and satisfy accessibility requirements.
        view.performClick()
      }
      true // Consume the event
    }

    // 3. Find the specific views within the dialog.
    val messageView = dialog.findViewById<TextView>(android.R.id.message)
    val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    val negativeButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)

    // 4. Apply the listener to all target views.
    dialog.window?.decorView?.setOnTouchListener(universalTouchListener)
    messageView?.setOnTouchListener(universalTouchListener)
    positiveButton?.setOnTouchListener(universalTouchListener)
    negativeButton?.setOnTouchListener(universalTouchListener)
  }

  private fun notifyFileRenamed(file: File, name: String, context: Context) {
    val renameEvent = FileRenameEvent(file, File(file.parent, name))

    // Notify FileManager first
    FileManager.onFileRenamed(renameEvent)

    EventBus.getDefault().post(renameEvent.apply { putData(context) })
  }
}
