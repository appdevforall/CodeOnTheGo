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

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.FileUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.FlashType
import com.itsaky.androidide.utils.TooltipUtils
import com.itsaky.androidide.utils.flashMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * File tree action to delete files.
 *
 * @author Akash Yadav
 */
class DeleteAction(context: Context, override val order: Int) :
  BaseFileTreeAction(context, labelRes = R.string.delete_file, iconRes = R.drawable.ic_delete) {

  override val id: String = "ide.editor.fileTree.delete"
  override var tooltipTag: String = TooltipTag.PROJECT_ITEM_DELETE

  override suspend fun execAction(data: ActionData) {
    val context = data.requireActivity()
    val file = data.requireFile()
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    builder
      .setNegativeButton(R.string.no, null)
      .setPositiveButton(R.string.yes) { dialogInterface, _ ->
        dialogInterface.dismiss()
      }
      .setTitle(R.string.title_confirm_delete)
      .setMessage(
        context.getString(
          R.string.msg_confirm_delete,
          String.format("%s [%s]", file.name, file.absolutePath)
        )
      )
      .setCancelable(false)

    val dialog = builder.create()

    // We need to call dialog.show() first to ensure the buttons are created
    // and can be found. We'll attach the listeners immediately after.
    dialog.show()

    // 1. Create the Gesture Detector (same as before).
    val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
      override fun onLongPress(e: MotionEvent) {
        dialog.dismiss()

        val tooltipTag = TooltipTag.PROJECT_CONFIRM_DELETE
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

    // 2. Define a reusable OnTouchListener.
    val universalTouchListener = View.OnTouchListener { view, event ->
      // Let the gesture detector try to handle the event first.
      // It will return 'true' if it consumed the event (e.g., a long press was detected).
      val wasGestureHandled = gestureDetector.onTouchEvent(event)

      // If the gesture detector did NOT handle the event, and the user just
      // lifted their finger, we can treat it as a click.
      if (event.action == MotionEvent.ACTION_UP && !wasGestureHandled) {
        view.performClick()
      }

      // Return true to indicate that we've handled the touch event.
      true
    }

    // 3. Find the specific views within the dialog.
    val messageView = dialog.findViewById<TextView>(android.R.id.message)
    val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
    val negativeButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)

    // 4. Apply the listener to all target views.
    dialog.window?.decorView?.setOnTouchListener(universalTouchListener)
    messageView?.setOnTouchListener(universalTouchListener) // Custom view `TextView` has setOnTouchListener called on it but does not override performClick Toggle info (⌘F1)
    positiveButton?.setOnTouchListener(universalTouchListener)
    negativeButton?.setOnTouchListener(universalTouchListener)

    // Note: We already called dialog.show(), so we don't call it again.
  }

  private fun notifyFileDeleted(file: File, context: Context) {
    val deletionEvent = FileDeletionEvent(file)

    // Notify FileManager first
    FileManager.onFileDeleted(deletionEvent)

    EventBus.getDefault().post(deletionEvent.putData(context))
  }
}
