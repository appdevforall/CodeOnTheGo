package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.BlockAnchor
import com.itsaky.androidide.lsp.refactor.BracelessBody
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.leadingIndentAt

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R
import openjdk.source.tree.BlockTree
import openjdk.source.tree.CaseTree
import openjdk.source.tree.CatchTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.DoWhileLoopTree
import openjdk.source.tree.EnhancedForLoopTree
import openjdk.source.tree.ExpressionTree
import openjdk.source.tree.ForLoopTree
import openjdk.source.tree.IfTree
import openjdk.source.tree.LambdaExpressionTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.StatementTree
import openjdk.source.tree.SynchronizedTree
import openjdk.source.tree.Tree
import openjdk.source.tree.TryTree
import openjdk.source.tree.WhileLoopTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath

/**
 * javac trees carry no parent pointers, so containment is answered positionally from [scopeSpan] -- see
 * [truncateAtCeiling]. [searchRange] bounds the occurrence search for this rung.
 */
data class ScopeFrame(
	val label: ScopeLabel,
	val scopeTree: Tree,
	val scopeSpan: TextSpan,
	val searchRange: TextSpan,
	val anchorForm: AnchorForm,
)

/**
 * The scopes the candidate could be hoisted into, innermost first. Stops after the enclosing method,
 * constructor or initializer body; a class body is never an anchor.
 *
 * Lambda boundaries are *crossed* here: whether crossing is legal depends on what the candidate
 * references, which needs resolution, so [truncateAtCeiling] applies it afterwards.
 *
 * A switch case is a barrier: hoisting past one changes the expression from "runs on this path" to
 * "runs on every path", which compiles and silently reorders side effects. Every case form supplies a
 * rung of its own first -- braces, an arrow rule body, or the colon group's own statement list.
 *
 * [indentUnit] is passed in rather than derived here: it is a property of the whole file, and deriving it
 * per rung re-scanned the entire source once for every ancestor of every candidate.
 */
internal fun enclosingScopeFrames(
	candidatePath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	indentUnit: String,
): List<ScopeFrame> {
	val frames = mutableListOf<ScopeFrame>()
	var path: TreePath = candidatePath

	while (true) {
		val parentPath = path.parentPath ?: break
		val frame = frameFor(path.leaf, parentPath, root, positions, fileText, indentUnit)
		if (frame != null) {
			frames += frame
			if (isCeilingBlock(frame.scopeTree, parentPath)) break
		}
		if (parentPath.leaf is CaseTree) break
		// Most nodes are not themselves anchorable -- an argument, an argument list, a member select.
		// Keep climbing rather than stopping, otherwise the chain would end at the first such node and a
		// candidate inside a lambda could never be hoisted out of it.
		path = parentPath
	}
	return frames
}

/**
 * Enforces "crossing a lambda boundary only when nothing lambda-scoped is referenced": if the candidate
 * uses a lambda parameter, that lambda's body is the ceiling and every outer rung disappears.
 *
 * Truncating to nothing empties the chain, which declines the candidate. Keeping the innermost rung
 * instead would hand back a rung the ceiling just ruled out -- for a candidate reading a local declared
 * in a case group, that is a declaration hoisted clean out of the local's scope, and the file stops
 * compiling.
 */
internal fun truncateAtCeiling(
	frames: List<ScopeFrame>,
	ceiling: TextSpan?,
): List<ScopeFrame> {
	if (ceiling == null) return frames
	return frames.takeWhile { ceiling.start <= it.scopeSpan.start && it.scopeSpan.end <= ceiling.end }
}

/** Null when [parentPath]'s leaf is not a position this refactoring anchors in. */
private fun frameFor(
	inner: Tree,
	parentPath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	indentUnit: String,
): ScopeFrame? {
	val parent = parentPath.leaf

	if (parent is BlockTree) {
		val blockSpan = spanOf(root, positions, parent) ?: return null
		return ScopeFrame(
			label = blockLabel(parent, parentPath),
			scopeTree = parent,
			scopeSpan = blockSpan,
			searchRange = blockSpan,
			anchorForm =
				AnchorForm.ExistingBlock(
					BlockAnchor(
						contentSpan = contentSpanOf(blockSpan, fileText) ?: return null,
						statementSpans = parent.statements.mapNotNull { spanOf(root, positions, it) },
					),
				),
		)
	}

	val innerSpan = spanOf(root, positions, inner) ?: return null

	if (inner is ExpressionTree && parent is LambdaExpressionTree && parent.body === inner) {
		return expressionBodyFrame(LAMBDA, inner, innerSpan, parent, root, positions, fileText, indentUnit, "return")
	}

	if (parent is CaseTree && parent.caseKind == CaseTree.CaseKind.RULE && parent.body === inner) {
		return when {
			inner is ExpressionTree -> {
				// `case A -> value;` parses the body as the expression and takes the `;` separately, so the
				// span stops short of it. Replacing only the expression would leave `case A -> { ... };`.
				val withTerminator = TextSpan(innerSpan.start, semicolonAfter(fileText, innerSpan.end))
				expressionBodyFrame(SWITCH_RULE, inner, withTerminator, parent, root, positions, fileText, indentUnit, "yield")
			}

			// `case A -> { ... }` already produced a block frame for the same braces, and wrapping them
			// again would offer a second rung with the same label emitting a redundant nested block.
			inner is BlockTree -> null

			else -> bracelessFrame(SWITCH_RULE, innerSpan, parent, root, positions, fileText, indentUnit)
		}
	}

	if (parent is CaseTree && parent.caseKind == CaseTree.CaseKind.STATEMENT && inner !is BlockTree) {
		return caseGroupFrame(parent, root, positions)
	}

	if (inner is StatementTree && inner !is BlockTree) {
		val label = bracelessOwnerLabel(inner, parent) ?: return null
		return bracelessFrame(label, innerSpan, parent, root, positions, fileText, indentUnit)
	}

	return null
}

/**
 * An old-style `case X:` group, whose statements own no braces of their own.
 *
 * They still have a block's geometry -- an ordered statement list and a content region -- so the shared
 * [BlockAnchor] machinery places the declaration among them, and a local declared in a case group is
 * scoped to the whole switch block, so it compiles. Without this rung the only anchor above a colon-form
 * case is outside the switch, where the expression would run on every path.
 *
 * The content region runs from the first statement to the last rather than from the `:`, so a group
 * written on one line (`case 1: return f();`) expands the way a one-line block does.
 */
private fun caseGroupFrame(
	case: CaseTree,
	root: CompilationUnitTree,
	positions: SourcePositions,
): ScopeFrame? {
	val caseSpan = spanOf(root, positions, case) ?: return null
	val statementSpans = case.statements.mapNotNull { spanOf(root, positions, it) }
	val first = statementSpans.firstOrNull() ?: return null
	return ScopeFrame(
		label = SWITCH_CASE,
		scopeTree = case,
		scopeSpan = caseSpan,
		searchRange = caseSpan,
		anchorForm =
			AnchorForm.ExistingBlock(
				BlockAnchor(
					contentSpan = TextSpan(first.start, statementSpans.last().end),
					statementSpans = statementSpans,
				),
			),
	)
}

/** The statement is replaced by a braced block holding both lines. */
private fun bracelessFrame(
	label: ScopeLabel,
	innerSpan: TextSpan,
	owner: Tree,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	indentUnit: String,
): ScopeFrame? {
	val ownerSpan = spanOf(root, positions, owner) ?: return null
	val indent = leadingIndentAt(fileText, ownerSpan.start)
	return ScopeFrame(
		label = label,
		scopeTree = owner,
		scopeSpan = innerSpan,
		searchRange = innerSpan,
		anchorForm =
			AnchorForm.WrapInBraces(
				BracelessBody(
					bodyStart = innerSpan.start,
					bodyEnd = innerSpan.end,
					indent = indent,
					innerIndent = indent + indentUnit,
				),
			),
	)
}

/**
 * `needsReturn` is left true here and settled by the planner, the only layer that can resolve the target
 * type's abstract method.
 */
private fun expressionBodyFrame(
	label: ScopeLabel,
	inner: Tree,
	innerSpan: TextSpan,
	owner: Tree,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	indentUnit: String,
	returnKeyword: String,
): ScopeFrame? {
	val ownerSpan = spanOf(root, positions, owner) ?: return null
	val indent = leadingIndentAt(fileText, ownerSpan.start)
	return ScopeFrame(
		label = label,
		scopeTree = inner,
		scopeSpan = innerSpan,
		searchRange = innerSpan,
		anchorForm =
			AnchorForm.ConvertExpressionBody(
				bodyStart = innerSpan.start,
				bodyEnd = innerSpan.end,
				indent = indent,
				innerIndent = indent + indentUnit,
				needsReturn = true,
				returnKeyword = returnKeyword,
			),
	)
}

/** Where the chain stops. [blockPath] is the block's own path, so its parent is the owner. */
private fun isCeilingBlock(
	scopeTree: Tree,
	blockPath: TreePath,
): Boolean {
	if (scopeTree !is BlockTree) return false
	val owner = blockPath.parentPath?.leaf ?: return true
	return owner is MethodTree || owner is ClassTree
}

/** The name shown for a block rung, taken from what owns the block. */
private fun blockLabel(
	block: BlockTree,
	blockPath: TreePath,
): ScopeLabel =
	when (val owner = blockPath.parentPath?.leaf) {
		is MethodTree ->
			if (owner.name.contentEquals("<init>")) {
				ScopeLabel(R.string.label_extract_scope_constructor)
			} else {
				ScopeLabel(R.string.label_extract_scope_method, owner.name.toString())
			}

		is ClassTree ->
			ScopeLabel(
				if (block.isStatic) {
					R.string.label_extract_scope_static_initializer
				} else {
					R.string.label_extract_scope_initializer
				},
			)

		is LambdaExpressionTree -> LAMBDA

		is IfTree ->
			ScopeLabel(
				if (owner.thenStatement === block) {
					R.string.label_extract_scope_if_block
				} else {
					R.string.label_extract_scope_else_block
				},
			)

		is ForLoopTree, is EnhancedForLoopTree -> ScopeLabel(R.string.label_extract_scope_for_loop)

		is WhileLoopTree -> ScopeLabel(R.string.label_extract_scope_while_loop)

		is DoWhileLoopTree -> ScopeLabel(R.string.label_extract_scope_do_while_loop)

		is TryTree ->
			ScopeLabel(
				if (owner.finallyBlock === block) {
					R.string.label_extract_scope_finally_block
				} else {
					R.string.label_extract_scope_try_block
				},
			)

		is CatchTree -> ScopeLabel(R.string.label_extract_scope_catch_block)

		is SynchronizedTree -> ScopeLabel(R.string.label_extract_scope_synchronized_block)

		is CaseTree -> if (owner.caseKind == CaseTree.CaseKind.RULE) SWITCH_RULE else SWITCH_CASE

		else -> ScopeLabel(R.string.label_extract_scope_block)
	}

/** A label when [inner] is a braceless body of [parent], else null. */
private fun bracelessOwnerLabel(
	inner: Tree,
	parent: Tree,
): ScopeLabel? =
	when (parent) {
		is IfTree ->
			when {
				parent.thenStatement === inner -> ScopeLabel(R.string.label_extract_scope_if_branch)
				parent.elseStatement === inner -> ScopeLabel(R.string.label_extract_scope_else_branch)
				else -> null
			}

		is ForLoopTree -> bodyLabel(parent.statement === inner, R.string.label_extract_scope_for_body)
		is EnhancedForLoopTree -> bodyLabel(parent.statement === inner, R.string.label_extract_scope_for_body)
		is WhileLoopTree -> bodyLabel(parent.statement === inner, R.string.label_extract_scope_while_body)
		is DoWhileLoopTree -> bodyLabel(parent.statement === inner, R.string.label_extract_scope_do_while_body)
		else -> null
	}

private fun bodyLabel(
	isBody: Boolean,
	@StringRes res: Int,
): ScopeLabel? = if (isBody) ScopeLabel(res) else null

private val LAMBDA = ScopeLabel(R.string.label_extract_scope_lambda)
private val SWITCH_RULE = ScopeLabel(R.string.label_extract_scope_switch_rule)
private val SWITCH_CASE = ScopeLabel(R.string.label_extract_scope_switch_case)

/**
 * The region inside a block's braces.
 *
 * Derived from the first `{` rather than from `blockSpan.start + 1`, because javac's `JCBlock.pos` is
 * not always the brace: `JavacParser` takes the position before `modifiersOpt()`, so a `static { ... }`
 * initializer reports the `s` of `static`. Null when no brace is found, which means the span and the
 * text disagree and the rung must be declined.
 */
private fun contentSpanOf(
	blockSpan: TextSpan,
	fileText: String,
): TextSpan? {
	val open = fileText.indexOf('{', blockSpan.start)
	if (open < 0 || open >= blockSpan.end - 1) return null
	return TextSpan(open + 1, blockSpan.end - 1)
}

/** The offset just past the `;` following [from], or [from] when there is none. */
private fun semicolonAfter(
	fileText: String,
	from: Int,
): Int {
	var i = from
	while (i < fileText.length && fileText[i].isWhitespace()) i++
	return if (i < fileText.length && fileText[i] == ';') i + 1 else from
}
