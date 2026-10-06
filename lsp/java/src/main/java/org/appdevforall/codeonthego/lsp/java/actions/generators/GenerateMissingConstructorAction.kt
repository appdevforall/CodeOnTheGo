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
package org.appdevforall.codeonthego.lsp.java.actions.generators

import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.hasRequiredData
import org.appdevforall.codeonthego.actions.markInvisible
import org.appdevforall.codeonthego.actions.requireFile
import org.appdevforall.codeonthego.actions.requirePath
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.java.JavaCompilerProvider
import org.appdevforall.codeonthego.lsp.java.actions.BaseJavaCodeAction
import org.appdevforall.codeonthego.lsp.java.models.DiagnosticCode
import org.appdevforall.codeonthego.lsp.java.rewrite.GenerateRecordConstructor
import org.appdevforall.codeonthego.lsp.java.utils.CodeActionUtils
import org.appdevforall.codeonthego.projects.IProjectManager
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
class GenerateMissingConstructorAction : BaseJavaCodeAction() {

  override val id = "ide.editor.lsp.java.generator.missingConstructor"
  override var label: String = ""
  private val diagnosticCode = DiagnosticCode.MISSING_CONSTRUCTOR.id
  override val titleTextRes: Int = R.string.action_generate_missing_constructor
  override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_GEN_CONSTRUCTOR

  companion object {

    private val log = LoggerFactory.getLogger(GenerateMissingConstructorAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (
      !visible ||
      !data.hasRequiredData(org.appdevforall.codeonthego.lsp.models.DiagnosticItem::class.java)
    ) {
      markInvisible()
      return
    }

    val diagnostic = data[org.appdevforall.codeonthego.lsp.models.DiagnosticItem::class.java]!!
    if (diagnosticCode != diagnostic.code) {
      markInvisible()
      return
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val diagnostic = data[org.appdevforall.codeonthego.lsp.models.DiagnosticItem::class.java]!!
    val compiler =
      JavaCompilerProvider.get(
        IProjectManager.getInstance().findModuleForFile(data.requireFile(), false) ?: return Any())
    val file = data.requirePath()
    return compiler.compile(file).get { task ->
      val needsConstructor =
        CodeActionUtils.findClassNeedingConstructor(task, diagnostic.range) ?: return@get false
      return@get GenerateRecordConstructor(needsConstructor)
    }
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result !is GenerateRecordConstructor) {
      log.warn("Unable to generate constructor")
      return
    }

    performCodeAction(data, result)
  }
}
