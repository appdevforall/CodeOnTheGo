package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.MAX_CANDIDATES
import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.element.ElementKind
import openjdk.source.tree.AnnotatedTypeTree
import openjdk.source.tree.AnnotationTree
import openjdk.source.tree.ArrayTypeTree
import openjdk.source.tree.AssignmentTree
import openjdk.source.tree.BinaryTree
import openjdk.source.tree.BlockTree
import openjdk.source.tree.CaseTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.CompoundAssignmentTree
import openjdk.source.tree.ConditionalExpressionTree
import openjdk.source.tree.DoWhileLoopTree
import openjdk.source.tree.ExpressionStatementTree
import openjdk.source.tree.ExpressionTree
import openjdk.source.tree.ForLoopTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.IntersectionTypeTree
import openjdk.source.tree.LambdaExpressionTree
import openjdk.source.tree.LiteralTree
import openjdk.source.tree.MemberReferenceTree
import openjdk.source.tree.MemberSelectTree
import openjdk.source.tree.MethodInvocationTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.NewClassTree
import openjdk.source.tree.ParameterizedTypeTree
import openjdk.source.tree.PrimitiveTypeTree
import openjdk.source.tree.StatementTree
import openjdk.source.tree.Tree
import openjdk.source.tree.UnaryTree
import openjdk.source.tree.UnionTypeTree
import openjdk.source.tree.WhileLoopTree
import openjdk.source.tree.WildcardTree
import openjdk.source.util.JavacTask
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.Trees

/**
 * [paths] is innermost-first, at most [MAX_CANDIDATES] long. Paths rather than bare trees, because every
 * downstream question -- my parent, my type, what this name resolves to -- needs the path.
 */
data class CandidateSyntax(
	val paths: List<TreePath>,
) {
	companion object {
		val NONE = CandidateSyntax(emptyList())
	}
}

/**
 * A cursor is the degenerate selection where the offsets are equal, so callers need one code path.
 *
 * Trimmed first, because a touch-screen selection routinely carries a leading or trailing space. An
 * anchor that is conditionally evaluated -- a ternary branch, the right side of `&&`/`||` -- refuses
 * the whole position up front (see [isConditionallyEvaluated]): extraction would change when it runs,
 * and there is no rung that would not. Other illegal nodes on the way out are **skipped rather than
 * terminating the walk**, so a cursor on a bare literal still offers the call around it.
 */
fun candidateExpressionsAt(
	task: JavacTask,
	root: CompilationUnitTree,
	fileText: String,
	selectionStart: Int,
	selectionEnd: Int,
): CandidateSyntax {
	val trees = Trees.instance(task)
	val positions = trees.sourcePositions
	val (start, end) = trimToCode(fileText, selectionStart, selectionEnd) ?: return CandidateSyntax.NONE

	val anchor =
		deepestPathAt(root, positions, start, end)
			// A caret resting just past a token still resolves, matching a tap inside it.
			?: (if (start == end && start > 0) deepestPathAt(root, positions, start - 1, start - 1) else null)
			?: return CandidateSyntax.NONE

	if (!isExtractionPosition(anchor)) return CandidateSyntax.NONE

	val ceiling = enclosingExecutableBody(anchor)?.leaf ?: return CandidateSyntax.NONE
	val collected = mutableListOf<TreePath>()
	val seen = mutableSetOf<TextSpan>()
	var path: TreePath? = anchor

	while (path != null) {
		val leaf = path.leaf
		if (leaf is ClassTree || leaf is MethodTree) break
		if (isLegalExtractionTarget(path, trees)) {
			val span = spanOf(root, positions, leaf)
			if (span != null && seen.add(span)) {
				collected += path
				if (collected.size == MAX_CANDIDATES) break
			}
		}
		if (leaf === ceiling) break
		path = path.parentPath
	}

	if (collected.isEmpty()) return CandidateSyntax.NONE
	return CandidateSyntax(collected)
}

/**
 * A whitespace-only selection collapses to a cursor at [start]: a drag over the gap between two tokens
 * carries the same intent as a tap in it. Null only when the range is not valid for [text].
 */
internal fun trimToCode(
	text: String,
	start: Int,
	end: Int,
): Pair<Int, Int>? {
	if (start < 0 || end > text.length || start > end) return null
	if (start == end) return start to end
	var s = start
	var e = end
	while (s < e && text[s].isWhitespace()) s++
	while (e > s && text[e - 1].isWhitespace()) e--
	return if (s == e) start to start else s to e
}

/**
 * javac has no `findElementAt`, so the whole unit is scanned. Pre-order means a child is seen after its
 * parent and is never wider, so keeping the narrowest-so-far with `<=` finds the deepest node without
 * counting depth. Nodes with no positions are skipped but still descended into.
 */
internal fun deepestPathAt(
	root: CompilationUnitTree,
	positions: SourcePositions,
	start: Int,
	end: Int,
): TreePath? {
	var best: TreePath? = null
	var bestWidth = Int.MAX_VALUE

	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun scan(
				tree: Tree?,
				p: Unit?,
			): Unit? {
				if (tree == null) return null
				val treeStart = positions.getStartPosition(root, tree).toInt()
				val treeEnd = positions.getEndPosition(root, tree).toInt()
				if (treeStart >= 0 && treeEnd >= treeStart && treeStart <= start && end <= treeEnd) {
					val width = treeEnd - treeStart
					if (width <= bestWidth) {
						// `currentPath` still holds the parent at this point, so extending it names `tree`
						// -- the same path getCurrentPath() reports once super.scan has pushed it.
						best = TreePath(currentPath, tree)
						bestWidth = width
					}
				}
				return super.scan(tree, p)
			}
		}
	scanner.scan(TreePath(root), null)
	return best
}

/**
 * Rejects positions where no local declaration can precede the expression: annotation arguments (must be
 * constant), `this(...)`/`super(...)` arguments (nothing can precede them), and anything outside an
 * executable body -- notably a field initializer, where an initializer block would change when it runs.
 */
internal fun isExtractionPosition(path: TreePath): Boolean {
	var current: TreePath? = path
	while (current != null) {
		val leaf = current.leaf
		if (leaf is AnnotationTree) return false
		if (leaf is MethodInvocationTree && isConstructorDelegation(leaf)) return false
		current = current.parentPath
	}
	if (isCaseLabel(path)) return false
	if (isConditionallyEvaluated(path)) return false
	return enclosingExecutableBody(path) != null
}

/**
 * A `case` label must be a compile-time constant, so a hoisted local can never stand in for one -- and
 * an unqualified enum constant only resolves inside the label at all.
 */
private fun isCaseLabel(path: TreePath): Boolean {
	var child: Tree = path.leaf
	var current: TreePath? = path.parentPath
	while (current != null) {
		val leaf = current.leaf
		if (leaf is StatementTree && leaf !is CaseTree) return false
		if (leaf is CaseTree) return leaf.body !== child
		child = leaf
		current = current.parentPath
	}
	return false
}

/**
 * Whether the expression is evaluated conditionally or repeatedly, where hoisting it changes *when* it
 * runs rather than just naming it.
 *
 * A loop condition hoisted out of its loop is evaluated once, so `while (it.hasNext())` never
 * terminates. A `for` update is the same, one clause along. The right operand of `&&`/`||` hoisted out
 * stops being guarded, so `s != null && s.length() > 0` throws. A conditional branch is the same shape.
 * None of these has an inner rung to offer instead -- `frameFor` finds no frame for a condition, an
 * update or an operand -- so the only placement available is the wrong one, and declining is the honest
 * answer.
 *
 * The walk stops at a lambda body for the same reason [enclosingExecutableBody] does: the expression
 * runs once per invocation of the lambda whatever surrounds the lambda itself, and the body *is* an
 * inner rung. Without this, `while (list.stream().anyMatch(x -> x.length() + 1 > n))` refused a
 * candidate that hoisting into the lambda would have placed correctly.
 */
private fun isConditionallyEvaluated(path: TreePath): Boolean {
	var child: Tree = path.leaf
	var current: TreePath? = path.parentPath
	while (current != null) {
		val leaf = current.leaf
		when {
			leaf is LambdaExpressionTree && leaf.body === child -> return false

			leaf is WhileLoopTree && leaf.condition === child -> return true

			leaf is DoWhileLoopTree && leaf.condition === child -> return true

			leaf is ForLoopTree && leaf.condition === child -> return true

			leaf is ConditionalExpressionTree &&
				(leaf.trueExpression === child || leaf.falseExpression === child) -> return true

			leaf is BinaryTree &&
				leaf.kind in SHORT_CIRCUIT_KINDS &&
				leaf.rightOperand === child -> return true

			// A `for` update is parsed as an ExpressionStatementTree, so the statement boundary below
			// would otherwise read it as a fixed evaluation point and accept it.
			leaf is ExpressionStatementTree && isForUpdate(current.parentPath, leaf) -> return true

			// A statement boundary means the expression is evaluated exactly where it is written.
			leaf is StatementTree -> return false
		}
		child = leaf
		current = current.parentPath
	}
	return false
}

/** Whether [statement] is one of the update clauses of the `for` loop at [parentPath]. */
private fun isForUpdate(
	parentPath: TreePath?,
	statement: Tree,
): Boolean {
	val loop = parentPath?.leaf as? ForLoopTree ?: return false
	return loop.update.any { it === statement }
}

private val SHORT_CIRCUIT_KINDS = setOf(Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR)

/** `this(...)` and `super(...)`, whose method select is the bare keyword. */
private fun isConstructorDelegation(invocation: MethodInvocationTree): Boolean {
	val name = (invocation.methodSelect as? IdentifierTree)?.name?.toString() ?: return false
	return name == "this" || name == "super"
}

/**
 * The nearest lambda, method/constructor, or initializer body. An initializer block is a `BlockTree`
 * under a `ClassTree`, a method body one under a `MethodTree`; both stop the walk at the right ceiling.
 */
internal fun enclosingExecutableBody(path: TreePath): TreePath? {
	var current: TreePath? = path.parentPath
	var child: Tree = path.leaf
	while (current != null) {
		val leaf = current.leaf
		if (leaf is LambdaExpressionTree && leaf.body === child) return current
		if (leaf is MethodTree && leaf.body === child) return current
		if (leaf is ClassTree && child is BlockTree) return TreePath(current, child)
		child = leaf
		current = current.parentPath
	}
	return null
}

/**
 * Excluded, and why: lambdas and method references, whose type comes from a target a hoisted declaration
 * no longer has; method selects and `new` class names, which are fragments; names resolving to a type or
 * package; assignment targets; type trees; and bare literals, where extracting is almost never the
 * intent -- the expression *around* a literal is still offered.
 */
internal fun isLegalExtractionTarget(
	path: TreePath,
	trees: Trees,
): Boolean {
	val leaf = path.leaf
	if (leaf !is ExpressionTree) return false
	if (leaf is LambdaExpressionTree || leaf is MemberReferenceTree) return false
	if (leaf is LiteralTree) return false
	if (leaf is PrimitiveTypeTree ||
		leaf is ArrayTypeTree ||
		leaf is ParameterizedTypeTree ||
		leaf is WildcardTree ||
		leaf is AnnotatedTypeTree ||
		leaf is UnionTypeTree ||
		leaf is IntersectionTypeTree
	) {
		return false
	}

	if ((leaf is IdentifierTree || leaf is MemberSelectTree) && namesATypeOrPackage(path, trees)) return false

	val parent = path.parentPath?.leaf ?: return false
	if (parent is MethodInvocationTree && parent.methodSelect === leaf) return false
	if (parent is NewClassTree && parent.identifier === leaf) return false
	if (parent is AssignmentTree && parent.variable === leaf) return false
	if (parent is CompoundAssignmentTree && parent.variable === leaf) return false
	// `foo(i++)` with the cursor on `i`: binding it would increment the copy and leave `i` alone, and it
	// compiles, so nothing would tell the user the behaviour changed.
	if (parent is UnaryTree && parent.kind in INCREMENT_KINDS && parent.expression === leaf) return false
	// The whole expression of an expression statement: the source `;` sits outside the candidate's span,
	// so replacing the expression would leave a bare `v;` behind -- "not a statement".
	if (parent is ExpressionStatementTree) return false
	return true
}

/** A resolution failure reads as "not a type", keeping a candidate over broken code. */
private fun namesATypeOrPackage(
	path: TreePath,
	trees: Trees,
): Boolean {
	val kind = runCatching { trees.getElement(path)?.kind }.getOrNull() ?: return false
	return when (kind) {
		ElementKind.CLASS,
		ElementKind.INTERFACE,
		ElementKind.ENUM,
		ElementKind.ANNOTATION_TYPE,
		ElementKind.RECORD,
		ElementKind.PACKAGE,
		ElementKind.MODULE,
		ElementKind.TYPE_PARAMETER,
		-> true

		else -> false
	}
}

/** [tree]'s span, or null when javac has no positions for it. */
internal fun spanOf(
	root: CompilationUnitTree,
	positions: SourcePositions,
	tree: Tree,
): TextSpan? {
	val start = positions.getStartPosition(root, tree).toInt()
	val end = positions.getEndPosition(root, tree).toInt()
	if (start < 0 || end < start) return null
	return TextSpan(start, end)
}
