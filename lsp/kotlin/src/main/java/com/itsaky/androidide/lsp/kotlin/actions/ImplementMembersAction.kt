package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.membersToImplement
import com.itsaky.androidide.lsp.kotlin.utils.renderOverrideStub
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.resources.R
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import java.nio.file.Path

class ImplementMembersAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_implement_members
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_OVERRIDE_SUPER
	override val id: String = "ide.editor.lsp.kt.implementMembers"
	override var label: String = ""

	companion object {
		private val logger = LoggerFactory.getLogger(ImplementMembersAction::class.java)
	}

	// Intentionally no prepare() visibility gate: the action is visible on any Kotlin file (BaseKotlinCodeAction
	// only checks the file type) and simply produces no edit when the enclosing class/object has nothing to
	// implement. Deciding that up front needs a K2 analysis session, which is too costly for prepare() (UI
	// thread). Matches OrganizeImportsAction.

	override suspend fun execAction(data: ActionData): List<TextEdit> {
		val server = data.get<KotlinLanguageServer>() ?: return emptyList()
		val nioPath = data.requireFile().toPath()
		val offset = data.requireEditor().cursor.left
		val env = server.compilationEnvironmentFor(nioPath) ?: return emptyList()
		return computeImplementMembersEdit(env, nioPath, offset)
	}

	/**
	 * Computes the edit that inserts stubs for the abstract members left unimplemented by the class or
	 * object enclosing [offset] in the file at [nioPath]. The current [KtFile] is fetched BEFORE
	 * entering [read] (deadlock rule: never block on `getCurrentKtFile(...).get()` inside `project.read`).
	 *
	 * Returns an empty list when there is nothing to do (cursor not in a class/object, the declaration
	 * is abstract/an interface/enum, or every required member is already implemented) *and* whenever
	 * anything in this pipeline throws: the action framework only catches [IllegalArgumentException] and
	 * this runs on a scope with no exception handler, so an uncaught throw here would crash the app.
	 * Degrading to zero edits is always safe -- it inserts nothing rather than a partial rewrite.
	 */
	internal fun computeImplementMembersEdit(
		env: AbstractCompilationEnvironment,
		nioPath: Path,
		offset: Int,
	): List<TextEdit> =
		runCatching {
			val ktFile = env.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return emptyList()
			env.project.read {
				val classOrObject = findEnclosingClassOrObject(ktFile, offset) ?: return@read emptyList()
				analyzeMaybeDangling(ktFile) {
					val classSymbol = classOrObject.symbol as? KaClassSymbol ?: return@analyzeMaybeDangling emptyList()
					if (!isImplementable(classSymbol)) return@analyzeMaybeDangling emptyList()

					val classIndent = classIndentOf(ktFile, classOrObject)
					val unit = detectIndentUnit(ktFile.text)
					val memberIndent = memberIndentOf(ktFile, classOrObject, classIndent, unit)
					val stubs = membersToImplement(classSymbol).mapNotNull { renderOverrideStub(it, memberIndent, unit) }
					if (stubs.isEmpty()) return@analyzeMaybeDangling emptyList()

					buildInsertionEdit(ktFile, classOrObject, stubs, classIndent)
				}
			}
		}.getOrElse { e ->
			logger.warn("Failed to compute implement-members edit", e)
			emptyList()
		}

	override fun postExec(
		data: ActionData,
		result: Any,
	) {
		super.postExec(data, result)
		if (result !is List<*> || result.isEmpty()) return

		@Suppress("UNCHECKED_CAST")
		result as List<TextEdit>

		val client =
			data.languageClient ?: run {
				logger.warn("No language client set. Cannot implement members.")
				return
			}
		val file = data.requireFile()
		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes = listOf(DocumentChange(file = file.toPath(), edits = result)),
				kind = CodeActionKind.QuickFix,
				command = Command("", ""), // stubs are emitted fully indented; no CMD_FORMAT_CODE (a Kotlin no-op)
			),
		)
	}
}

/** Innermost [KtClassOrObject] containing [offset], or null. Tries [offset] then [offset]-1 so a caret sitting just after a token still resolves. */
private fun findEnclosingClassOrObject(
	ktFile: KtFile,
	offset: Int,
): KtClassOrObject? {
	val element = ktFile.findElementAt(offset) ?: ktFile.findElementAt((offset - 1).coerceAtLeast(0))
	return element?.let { PsiTreeUtil.getParentOfType(it, KtClassOrObject::class.java, false) }
}

/** Only concrete classes and objects are *required* to implement inherited members; abstract/sealed classes, interfaces, enums and annotations are not. */
private fun isImplementable(classSymbol: KaClassSymbol): Boolean {
	if (classSymbol.modality == KaSymbolModality.ABSTRACT || classSymbol.modality == KaSymbolModality.SEALED) return false
	return classSymbol.classKind == KaClassKind.CLASS || classSymbol.classKind.isObject
}

/** Leading whitespace of the line the declaration starts on (its base indentation). */
private fun classIndentOf(
	ktFile: KtFile,
	classOrObject: KtClassOrObject,
): String = leadingIndentAt(ktFile.text, classOrObject.textRange.startOffset)

/** The indentation each generated member should carry, matched to the surrounding code. */
private fun memberIndentOf(
	ktFile: KtFile,
	classOrObject: KtClassOrObject,
	classIndent: String,
	unit: String,
): String {
	// Prefer an existing member's exact indentation so stubs line up with hand-written code.
	val firstDecl = classOrObject.body?.declarations?.firstOrNull()
	if (firstDecl != null) return leadingIndentAt(ktFile.text, firstDecl.textRange.startOffset)
	return classIndent + unit
}

/** Leading run of spaces/tabs on the line containing [offset]. */
private fun leadingIndentAt(
	text: String,
	offset: Int,
): String {
	val lineStart = text.lastIndexOf('\n', offset - 1) + 1
	return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
}

/**
 * One indentation level for [text], inferred from its existing lines: a tab if any line is
 * tab-indented, otherwise the smallest positive run of leading spaces, defaulting to a single tab
 * when nothing is indented (the project convention). This keeps generated stubs consistent with the
 * file's own style rather than assuming tabs -- TextEdits bypass the editor's reindent, so the text
 * must be final.
 */
private fun detectIndentUnit(text: String): String {
	var minSpaces = Int.MAX_VALUE
	for (line in text.splitToSequence('\n')) {
		if (line.isEmpty() || line[0] == '\t') {
			if (line.isNotEmpty()) return "\t"
			continue
		}
		if (line[0] != ' ') continue
		val spaces = line.takeWhile { it == ' ' }.length
		if (spaces in 1 until minSpaces) minSpaces = spaces
	}
	return if (minSpaces == Int.MAX_VALUE) "\t" else " ".repeat(minSpaces)
}

/**
 * A single [TextEdit] that drops [stubs] into [classOrObject]'s body:
 * - no body -> append ` { ... }` after the declaration;
 * - empty body -> replace the whitespace between the braces;
 * - non-empty body -> insert after the last existing member (leaving existing code untouched).
 */
private fun buildInsertionEdit(
	ktFile: KtFile,
	classOrObject: KtClassOrObject,
	stubs: List<String>,
	classIndent: String,
): List<TextEdit> {
	val memberBlock = stubs.joinToString("\n\n")
	val body = classOrObject.body

	val edit =
		when {
			body == null -> {
				val at = classOrObject.textRange.endOffset
				TextEdit(TextRange(at, at).toRange(ktFile), " {\n$memberBlock\n$classIndent}")
			}

			body.declarations.isNotEmpty() -> {
				val at =
					body.declarations
						.last()
						.textRange.endOffset
				TextEdit(TextRange(at, at).toRange(ktFile), "\n\n$memberBlock")
			}

			else -> {
				val l = body.lBrace?.textRange?.endOffset ?: return emptyList()
				val r = body.rBrace?.textRange?.startOffset ?: return emptyList()
				TextEdit(TextRange(l, r).toRange(ktFile), "\n$memberBlock\n$classIndent")
			}
		}

	return if (edit.range == Range.NONE) emptyList() else listOf(edit)
}
