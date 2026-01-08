package com.itsaky.androidide.actions.etc

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import org.appdevforall.codeonthego.computervision.ui.ComputerVisionActivity
import com.android.aaptcompiler.AaptResourceType.LAYOUT
import com.android.aaptcompiler.extractPathData
import com.blankj.utilcode.util.KeyboardUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorRelatedAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.resources.R
import io.sentry.Sentry


import java.io.File

class GenerateXMLAction(context: Context, override val order: Int) : EditorRelatedAction() {

    override val id: String = ID
    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = ""
    override var requiresUIThread: Boolean = false

    companion object {
        const val ID = "ide.editor.generatexml"
        const val EXTRA_LAYOUT_FILE_PATH = "com.example.images.LAYOUT_FILE_PATH"
        const val EXTRA_LAYOUT_FILE_NAME = "com.example.images.LAYOUT_FILE_NAME"
    }

    init {
        label = context.getString(R.string.title_image_to_layout)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_computer_vision)
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)

        runCatching {
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
            val file = editor.file
            file?.let {
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
        }.onFailure {
            Sentry.captureException(it)
        }

    }

    override suspend fun execAction(data: ActionData): Any {
        return true
    }

    override fun getShowAsActionFlags(data: ActionData): Int {
        val activity = data.getActivity() ?: return super.getShowAsActionFlags(data)
        return if (KeyboardUtils.isSoftInputVisible(activity)) {
            MenuItem.SHOW_AS_ACTION_IF_ROOM
        } else {
            MenuItem.SHOW_AS_ACTION_ALWAYS
        }
    }


    override fun postExec(data: ActionData, result: Any) {
        val activity = data.requireActivity()
        activity.let {
            activity.navigateComputerVisionActivity(data.requireEditor().file!!)
        }
    }

    private fun EditorHandlerActivity.navigateComputerVisionActivity(file: File) {
        val intent = Intent(this, ComputerVisionActivity::class.java).apply {
            putExtra(EXTRA_LAYOUT_FILE_PATH, file.absolutePath)
            putExtra(EXTRA_LAYOUT_FILE_NAME, file.name)
        }
        uiDesignerResultLauncher?.launch(intent)
    }

    private fun ActionData.requireEditor(): IDEEditor {
        return this.getEditor() ?: throw IllegalArgumentException(
            "An editor instance is required but none was provided"
        )
    }
}