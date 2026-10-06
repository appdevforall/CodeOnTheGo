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
package org.appdevforall.codeonthego.lsp.java.actions.common

import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.hasRequiredData
import org.appdevforall.codeonthego.actions.markInvisible
import org.appdevforall.codeonthego.editor.api.ILspEditor
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.java.actions.BaseJavaCodeAction
import com.itsaky.androidide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/**
 * Action that allows the user to navigate to the definition of a variable, field, method, class,
 * etc.
 *
 * @author Akash Yadav
 */
class GoToDefinitionAction : BaseJavaCodeAction() {

  override val titleTextRes: Int = R.string.action_goto_definition
  override val id: String = "ide.editor.lsp.java.gotoDefinition"
  override var label: String = ""
  override var requiresUIThread: Boolean = true
  override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_GOTO_DEF

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (!visible || !data.hasRequiredData(CodeEditor::class.java, File::class.java)) {
      markInvisible()
      return
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val editor = data[CodeEditor::class.java]!!
    return (editor as? ILspEditor)?.findDefinition() ?: false
  }
}
