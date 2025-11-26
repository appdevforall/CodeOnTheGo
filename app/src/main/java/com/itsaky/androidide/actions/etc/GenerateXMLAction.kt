package com.itsaky.androidide.actions.etc
import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.extractPathData
import com.blankj.utilcode.util.KeyboardUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorRelatedAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.resources.R

import java.io.File

class GenerateXMLAction(context: Context, override val order: Int) : EditorRelatedAction() {

  override val id: String = ID
  override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = ""
  override var requiresUIThread: Boolean = false

  companion object{
    const val ID = "ide.editor.generatexml"
  }

  init {
    label = context.getString(R.string.title_preview_layout)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_preview_layout)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    val viewModel = data.requireActivity().editorViewModel
    if (viewModel.isInitializing) {
      visible = true
      enabled = false
      return
    }

    if (!visible) {
      return
    }

    val editor = data.requireEditor()
    val file = editor.file!!

    val isXml = file.name.endsWith(".xml")

    if (!isXml) {
      markInvisible()
      return
    }

    val type = try {
      extractPathData(file).type
    } catch (err: Throwable) {
      markInvisible()
      return
    }

    visible = type == LAYOUT
    enabled = visible
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    val activity = data.getActivity() ?: return super.getShowAsActionFlags(data)
    return if (KeyboardUtils.isSoftInputVisible(activity)) {
      MenuItem.SHOW_AS_ACTION_IF_ROOM
    } else {
      MenuItem.SHOW_AS_ACTION_ALWAYS
    }
  }

  override suspend fun execAction(data: ActionData): Boolean {
    val activity = data.requireActivity()
    activity.saveAll()
    return true
  }

  override fun postExec(data: ActionData, result: Any) {
    val activity = data.requireActivity()
    activity.navigateComputerVisionActivity(data.requireEditor().file!!)
  }

  private fun EditorHandlerActivity.navigateComputerVisionActivity(file: File) {
//    //close any open xml files first
//    val openEditors = editorViewModel.getOpenedFileCount()
//    for(index in 1..openEditors) {
//      closeFile(index-1) //zero based
//    }
//    invalidateOptionsMenu()

    val intent = Intent(this, ComputerVisionActivity::class.java)
    uiDesignerResultLauncher?.launch(intent)
  }

  private fun ActionData.requireEditor(): IDEEditor {
    return this.getEditor() ?: throw IllegalArgumentException(
      "An editor instance is required but none was provided")
  }
}