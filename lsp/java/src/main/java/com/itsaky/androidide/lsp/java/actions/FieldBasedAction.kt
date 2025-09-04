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

package com.itsaky.androidide.lsp.java.actions

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.blankj.utilcode.util.ThreadUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.hasRequiredData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.newDialogBuilder
import com.itsaky.androidide.actions.requirePath
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.lsp.java.actions.FieldBasedAction.ActionId.CONSTRUCTOR
import com.itsaky.androidide.lsp.java.actions.FieldBasedAction.ActionId.GETTER_SETTER
import com.itsaky.androidide.lsp.java.actions.FieldBasedAction.ActionId.TO_STRING
import com.itsaky.androidide.lsp.java.compiler.CompileTask
import com.itsaky.androidide.lsp.java.visitors.FindTypeDeclarationAt
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.applyLongPressRecursively
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import io.github.rosemoe.sora.widget.CodeEditor
import jdkx.lang.model.element.Modifier.STATIC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import openjdk.source.tree.ClassTree
import openjdk.source.tree.Tree.Kind.VARIABLE
import openjdk.source.tree.VariableTree
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Any action that has to work with fields in the current class can inherit this action.
 *
 * @author Akash Yadav
 */
abstract class FieldBasedAction : BaseJavaCodeAction() {


    companion object {

        private val log = LoggerFactory.getLogger(FieldBasedAction::class.java)
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)

        if (
            !visible ||
            !data.hasRequiredData(
                Range::class.java,
                CodeEditor::class.java
            ) ||
            IProjectManager.getInstance().rootProject == null
        ) {
            markInvisible()
            return
        }

        visible = true
        enabled = true
    }

    override suspend fun execAction(data: ActionData): Any {
        val range = data[Range::class.java]!!
        val file = data.requirePath()
        val module = IProjectManager.getInstance().findModuleForFile(file, false) ?: return Any()

        return JavaCompilerProvider.get(module).compile(file).get { task ->
            val triple = findFields(task, file, range)
            val type = triple?.second
            val fields = triple?.third
            val fieldNames = fields?.map { "${it.name}: ${it.type}" } // Get the names

            log.debug("Found {} fields in class {}", fieldNames?.size, type?.simpleName)

            return@get fieldNames ?: emptyList<String>()
        }
    }

    protected fun findFields(
        task: CompileTask,
        file: Path,
        range: Range
    ): Triple<FindTypeDeclarationAt, ClassTree, MutableList<VariableTree>>? {
        // 1-based line and column index
        val startLine = range.start.line + 1
        val startColumn = range.start.column + 1
        val endLine = range.end.line + 1
        val endColumn = range.end.column + 1
        val lines = task.root().lineMap
        val start = lines.getPosition(startLine.toLong(), startColumn.toLong())
        val end = lines.getPosition(endLine.toLong(), endColumn.toLong())

        if (start == (-1).toLong() || end == (-1).toLong()) {
            throw CompletionException(
                RuntimeException("Unable to find position for the given selection range")
            )
        }

        val typeFinder = FindTypeDeclarationAt(task.task)
        var type = typeFinder.scan(task.root(file), start)
        if (type == null) {
            type = typeFinder.scan(task.root(file), end)
        }

        if (type == null) {
            return null
        }

        val fields =
            type.members
                .filter { it.kind == VARIABLE }
                .map { it as VariableTree }
                .filter { !it.modifiers.flags.contains(STATIC) }
                .toMutableList()
        return Triple(typeFinder, type, fields)
    }

    protected inline fun withValidFields(
        data: ActionData,
        task: CompileTask,
        file: Path,
        range: Range,
        onValid: (FindTypeDeclarationAt, ClassTree, MutableList<VariableTree>) -> Unit
    ) {
        val triple = findFields(task, file, range)
        if (triple == null) {
            ThreadUtils.runOnUiThread {
                val context = data[Context::class.java]
                if (context != null) {
                    flashError(context.getString(R.string.msg_no_fields_found))
                } else {
                    flashError("No fields found in the selected range")
                }
            }
            return
        }

        val (typeFinder, type, fields) = triple
        onValid(typeFinder, type, fields)
    }

    @Suppress("UNCHECKED_CAST")
    override fun postExec(data: ActionData, result: Any) {
        if (result !is List<*>) {
            log.error("Unable to find fields in the current class")
            return
        }

        if (result.isEmpty()) {
            flashInfo(data[Context::class.java]!!.getString(R.string.msg_no_fields_found))
            return
        }

        onGetFields(result as List<String>, data)
    }

    /**
     * Called when the fields of the current class are found. As this method is called inside
     * [postExec], the current thread is the UI thread.
     */
    abstract fun onGetFields(fields: List<String>, data: ActionData)

    /**
     * Shows the field selector dialog. Returns a [CompletableFuture] which is completed when the user
     * confirms the selected fields.
     */

    object ActionId {
        const val TO_STRING = "ide.editor.lsp.java.generator.toString"
        const val CONSTRUCTOR = "ide.editor.lsp.java.generator.constructor"
        const val GETTER_SETTER = "ide.editor.lsp.java.generator.settersAndGetters"
    }

    protected fun showFieldSelector(
        fields: List<String>,
        data: ActionData,
        listener: OnFieldsSelectedListener?,
        actionId: String
    ) {
        val names = fields.toTypedArray()
        val checkedNames = mutableSetOf<String>()
        val builder = newDialogBuilder(data)
        val context = data[Context::class.java]!!
        builder.setTitle(context.getString(R.string.msg_select_fields))
        builder.setMultiChoiceItems(names, BooleanArray(fields.size)) { _, which, checked ->
            checkedNames.apply {
                val item = names[which]
                if (checked) {
                    add(item)
                } else {
                    remove(item)
                }
            }
        }

        builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
            dialog.dismiss()

            if (checkedNames.isEmpty()) {
                flashInfo(data[Context::class.java]!!.getString(R.string.msg_no_fields_selected))
                return@setPositiveButton
            }

            listener?.onFieldsSelected(checkedNames)
        }
        builder.setNegativeButton(android.R.string.cancel, null)

        val dialog = builder.create()

        dialog.setOnShowListener {
            val root = dialog.window?.decorView?.findViewById<View>(android.R.id.content)
            root?.applyLongPressRecursively { pressedView ->

                TooltipManager.showTooltip(context, root, getToolTipTag(actionId))

                true
            }
        }


        dialog.show()

    }

    fun getToolTipTag(actionId: String): String {
        return when (actionId) {
            TO_STRING -> EDITOR_CODE_ACTIONS_GEN_TO_STRING_DIALOG
            CONSTRUCTOR -> EDITOR_CODE_ACTIONS_GEN_CONSTRUCTOR_DIALOG
            GETTER_SETTER -> EDITOR_CODE_ACTIONS_SETTER_GETTER_DIALOG
            else -> ""
        }
    }

    /** Listener to get callback when fields are selected by the user. */
    fun interface OnFieldsSelectedListener {

        /**
         * Called when the user is done selecting fields.
         *
         * @param fields The selected field names.
         */
        fun onFieldsSelected(fields: MutableSet<String>)
    }
}
