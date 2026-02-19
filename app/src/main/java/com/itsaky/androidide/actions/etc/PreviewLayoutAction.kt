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

package com.itsaky.androidide.actions.etc

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.extractPathData
import com.blankj.utilcode.util.KeyboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorRelatedAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.compose.preview.ComposePreviewActivity
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import org.appdevforall.codeonthego.layouteditor.activities.EditorActivity
import org.appdevforall.codeonthego.layouteditor.editor.convert.ConvertImportedXml
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import com.itsaky.androidide.projects.IProjectManager
import org.appdevforall.codeonthego.layouteditor.tools.ValidationResult
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutParser
import org.slf4j.LoggerFactory
import java.io.File

/** @author Akash Yadav */
class PreviewLayoutAction(context: Context, override val order: Int) : EditorRelatedAction() {

  override val id: String = ID
  override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = when (previewType) {
    PreviewType.COMPOSE -> TooltipTag.EDITOR_TOOLBAR_PREVIEW_COMPOSE
    else -> TooltipTag.EDITOR_TOOLBAR_PREVIEW_LAYOUT
  }
  override var requiresUIThread: Boolean = false

  private var previewType: PreviewType = PreviewType.NONE

  private enum class PreviewType {
    NONE,
    XML_LAYOUT,
    COMPOSE
  }

  companion object {
    const val ID = "ide.editor.previewLayout"
    private val LOG = LoggerFactory.getLogger(PreviewLayoutAction::class.java)
  }

  init {
    label = context.getString(R.string.title_preview_layout)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_preview_layout)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    previewType = PreviewType.NONE

    if (data.getActivity() == null) {
      markInvisible()
      return
    }

    val viewModel = data.requireActivity().editorViewModel
    val editor = data.getEditor()
    val file = editor?.file

    if (file != null && !viewModel.isInitializing) {
      when {
        file.name.endsWith(".xml") -> {
          val type = try {
            extractPathData(file).type
          } catch (err: Throwable) {
            markInvisible()
            return
          }

          if (type == LAYOUT) {
            previewType = PreviewType.XML_LAYOUT
            visible = true
            enabled = true
          } else {
            markInvisible()
          }
        }
        file.name.endsWith(".kt") && moduleUsesCompose(file) -> {
          previewType = PreviewType.COMPOSE
          visible = true
          enabled = true
        }
        else -> {
          markInvisible()
        }
      }
    } else {
      if (moduleUsesCompose()) {
        previewType = PreviewType.COMPOSE
        visible = true
        enabled = false
      } else {
        markInvisible()
      }
    }
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

    when (previewType) {
      PreviewType.XML_LAYOUT -> {
        val editor = data.getEditor() ?: return
        val file = editor.file ?: return
        val sourceCode = editor.text.toString()

        try {
          val converted = ConvertImportedXml(sourceCode).getXmlConverted(activity)
          if (converted == null) {
            showXmlValidationError(activity, activity.getString(R.string.xml_validation_error_invalid_file))
            return
          }

          val validator = XmlLayoutParser(activity)

          val result = validator.validateXml(converted, activity)
          when (result) {
            is ValidationResult.Success -> activity.previewXmlLayout(file)
            is ValidationResult.Error -> showXmlValidationError(activity, result.formattedMessage)
          }
        } catch (e: Exception) {
          showXmlValidationError(activity, "Unexpected error: ${e.message}")
        }
      }
      PreviewType.COMPOSE -> {
        val editor = data.getEditor() ?: return
        val file = editor.file ?: return
        activity.showComposePreviewSheet(file, editor.text.toString())
      }
      PreviewType.NONE -> {}
    }
  }

  private fun EditorHandlerActivity.previewXmlLayout(file: File) {
    val intent = Intent(this, EditorActivity::class.java)
    intent.putExtra(Constants.EXTRA_KEY_FILE_PATH, file.absolutePath.substringBefore("layout"))
    intent.putExtra(Constants.EXTRA_KEY_LAYOUT_FILE_NAME, file.name.substringBefore("."))
    uiDesignerResultLauncher?.launch(intent)
  }

  private fun EditorHandlerActivity.showComposePreviewSheet(file: File, sourceCode: String) {
    ComposePreviewActivity.start(this, sourceCode, file.absolutePath)
  }

  private fun showXmlValidationError(activity: Context, message: String?) {
    val safeMessage =
      message?.takeIf { it.isNotBlank() }
        ?: activity.getString(R.string.xml_validation_error_generic)
    (activity as? EditorHandlerActivity)?.runOnUiThread {
      MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.xml_validation_error_title)
        .setMessage(safeMessage)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    }
  }

  private fun moduleUsesCompose(): Boolean {
    val workspace = IProjectManager.getInstance().workspace ?: return false
    return workspace.findAndroidModules().any { module ->
      module.hasExternalDependency("androidx.compose.runtime", "runtime")
    }
  }

  private fun moduleUsesCompose(file: File): Boolean {
    val module = IProjectManager.getInstance().findModuleForFile(file) ?: return false
    return module.hasExternalDependency("androidx.compose.runtime", "runtime")
  }
}
