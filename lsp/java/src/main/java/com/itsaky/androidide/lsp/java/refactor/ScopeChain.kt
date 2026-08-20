package com.itsaky.androidide.lsp.java.refactor

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
	val label: String,
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
 * An old-style `case X:` group is deliberately not a rung -- its statements have no owning braces, so
 * the block geometry the rewrite reasons about does not exist. `case X: { ... }` works normally.
 */
fun enclosingScopeFrames(
	candidatePath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
): List<ScopeFrame> {
	val frames = mutableListOf<ScopeFrame>()
	var path: TreePath = candidatePath

	while (true) {
		val parentPath = path.parentPath ?: break
		val frame = frameFor(path.leaf, parentPath, root, positions, fileText)
		if (frame == null) {
			// Most nodes are not themselves anchorable -- an argument, an argument list, a member
			// select. Keep climbing rather than stopping, otherwise the chain would end at the first
			// such node and a candidate inside a lambda could never be hoisted out of it.
			path = parentPath
			continue
		}

		frames += frame
		if (isCeilingBlock(frame.scopeTree, parentPath)) break
		path = parentPath
	}
	return frames
}

/**
 * Enforces "crossing a lambda boundary only when nothing lambda-scoped is referenced": if the candidate
 * uses a lambda parameter, that lambda's body is the ceiling and every outer rung disappears. Truncating
 * to nothing keeps the innermost rung, so this step never empties a chain on its own.
 */
fun truncateAtCeiling(
	frames: List<ScopeFrame>,
	ceiling: TextSpan?,
): List<ScopeFrame> {
	if (ceiling == null) return frames
	val kept = frames.takeWhile { ceiling.start <= it.scopeSpan.start && it.scopeSpan.end <= ceiling.end }
	return kept.ifEmpty { frames.take(1) }
}

/** Null when [parentPath]'s leaf is not a position this refactoring anchors in. */
private fun frameFor(
	inner: Tree,
	parentPath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
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
					// A Java block always owns its braces, unlike a Kotlin lambda body, so the content
					// span is unconditionally what sits between them.
					contentSpan = TextSpan(blockSpan.start + 1, blockSpan.end - 1),
					statementSpans = parent.statements.mapNotNull { spanOf(root, positions, it) },
				),
		)
	}

	val innerSpan = spanOf(root, positions, inner) ?: return null

	if (inner is ExpressionTree && parent is LambdaExpressionTree && parent.body === inner) {
		return expressionBodyFrame("lambda", inner, innerSpan, parent, root, positions, fileText, "return")
	}

	if (parent is CaseTree && parent.caseKind == CaseTree.CaseKind.RULE && parent.body === inner) {
		return if (inner is ExpressionTree) {
			expressionBodyFrame("switch rule", inner, innerSpan, parent, root, positions, fileText, "yield")
		} else {
			bracelessFrame("switch rule", innerSpan, parent, root, positions, fileText)
		}
	}

	if (inner is StatementTree && inner !is BlockTree) {
		val label = bracelessOwnerLabel(inner, parent) ?: return null
		return bracelessFrame(label, innerSpan, parent, root, positions, fileText)
	}

	return null
}

/** The statement is replaced by a braced block holding both lines. */
private fun bracelessFrame(
	label: String,
	innerSpan: TextSpan,
	owner: Tree,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
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
				bodyStart = innerSpan.start,
				bodyEnd = innerSpan.end,
				indent = indent,
				innerIndent = indent + detectIndentUnit(fileText),
			),
	)
}

/**
 * `needsReturn` is left true here and settled by the planner, the only layer that can resolve the target
 * type's abstract method.
 */
private fun expressionBodyFrame(
	label: String,
	inner: Tree,
	innerSpan: TextSpan,
	owner: Tree,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
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
				innerIndent = indent + detectIndentUnit(fileText),
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
): String =
	when (val owner = blockPath.parentPath?.leaf) {
		is MethodTree -> if (owner.name.contentEquals("<init>")) "constructor" else "method ${owner.name}"
		is ClassTree -> if (block.isStatic) "static initializer" else "initializer"
		is LambdaExpressionTree -> "lambda"
		is IfTree -> if (owner.thenStatement === block) "if block" else "else block"
		is ForLoopTree, is EnhancedForLoopTree -> "for loop"
		is WhileLoopTree -> "while loop"
		is DoWhileLoopTree -> "do-while loop"
		is TryTree -> if (owner.finallyBlock === block) "finally block" else "try block"
		is CatchTree -> "catch block"
		is SynchronizedTree -> "synchronized block"
		is CaseTree -> "switch rule"
		else -> "block"
	}

/** A label when [inner] is a braceless body of [parent], else null. */
private fun bracelessOwnerLabel(
	inner: Tree,
	parent: Tree,
): String? =
	when (parent) {
		is IfTree ->
			when {
				parent.thenStatement === inner -> "if branch"
				parent.elseStatement === inner -> "else branch"
				else -> null
			}

		is ForLoopTree -> if (parent.statement === inner) "for body" else null
		is EnhancedForLoopTree -> if (parent.statement === inner) "for body" else null
		is WhileLoopTree -> if (parent.statement === inner) "while body" else null
		is DoWhileLoopTree -> if (parent.statement === inner) "do-while body" else null
		else -> null
	}
