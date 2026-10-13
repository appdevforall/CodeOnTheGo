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

package org.appdevforall.codeonthego.lsp.java.actions

import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.actions.CommentLineAction
import org.appdevforall.codeonthego.lsp.actions.IActionsMenuProvider
import org.appdevforall.codeonthego.lsp.actions.SurroundWithTryCatchAction
import org.appdevforall.codeonthego.lsp.actions.UncommentLineAction
import org.appdevforall.codeonthego.lsp.java.JavaLanguageServer
import org.appdevforall.codeonthego.lsp.java.actions.common.FindReferencesAction
import org.appdevforall.codeonthego.lsp.java.actions.common.GoToDefinitionAction
import org.appdevforall.codeonthego.lsp.java.actions.common.OrganizeImportsAction
import org.appdevforall.codeonthego.lsp.java.actions.common.RemoveUnusedImportsAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.AddImportAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.AddThrowsAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.AutoFixImportsAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.CreateMissingMethodAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.FieldToBlockAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.ImplementAbstractMethodsAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.RemoveClassAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.RemoveMethodAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.RemoveUnusedThrowsAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.SuppressUncheckedWarningAction
import org.appdevforall.codeonthego.lsp.java.actions.diagnostics.VariableToStatementAction
import org.appdevforall.codeonthego.lsp.java.actions.generators.GenerateConstructorAction
import org.appdevforall.codeonthego.lsp.java.actions.generators.GenerateMissingConstructorAction
import org.appdevforall.codeonthego.lsp.java.actions.generators.GenerateSettersAndGettersAction
import org.appdevforall.codeonthego.lsp.java.actions.generators.GenerateToStringMethodAction
import org.appdevforall.codeonthego.lsp.java.actions.generators.OverrideSuperclassMethodsAction

/**
 * Java code actions.
 * @author Akash Yadav
 */
object JavaCodeActionsMenu : IActionsMenuProvider {
	private const val LANG = "java"
	private const val EXT = "java"
	private const val LINE_COMMENT_TOKEN = "//"
	private const val CATCH_CLAUSE = "catch (Exception e)"
	private const val CATCH_BODY = "e.printStackTrace();"

	override val actions: List<ActionItem> =
		listOf(
			CommentLineAction(LANG, EXT, LINE_COMMENT_TOKEN, TooltipTag.EDITOR_CODE_ACTIONS_COMMENT),
			UncommentLineAction(
				LANG,
				EXT,
				LINE_COMMENT_TOKEN,
				TooltipTag.EDITOR_CODE_ACTIONS_UNCOMMENT,
			),
			GoToDefinitionAction(),
			FindReferencesAction(),
			AddImportAction(),
			AutoFixImportsAction(),
			ImplementAbstractMethodsAction(),
			VariableToStatementAction(),
			FieldToBlockAction(),
			RemoveClassAction(),
			RemoveMethodAction(),
			RemoveUnusedThrowsAction(),
			CreateMissingMethodAction(),
			SuppressUncheckedWarningAction(),
			AddThrowsAction(),
			GenerateSettersAndGettersAction(),
			OverrideSuperclassMethodsAction(),
			GenerateMissingConstructorAction(),
			GenerateConstructorAction(),
			GenerateToStringMethodAction(),
			RemoveUnusedImportsAction(),
			OrganizeImportsAction(),
			SurroundWithTryCatchAction(
				LANG,
				EXT,
				JavaLanguageServer.SERVER_ID,
				CATCH_CLAUSE,
				CATCH_BODY,
				TooltipTag.EDITOR_CODE_ACTIONS_TRY_CATCH,
			),
			ExtractVariableAction(),
			ExtractMethodAction(),
		)
}
