package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.VariableElement
import openjdk.source.tree.AssignmentTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.CompoundAssignmentTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.Tree
import openjdk.source.tree.UnaryTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.Trees

internal class Reference(
	val element: Element,
	val offset: Int,
)

/**
 * Every named reference the region makes, in source order.
 *
 * Identifiers only: a member select's own selector resolves to a field or method, which needs nothing,
 * and its base is an identifier this already sees. Nested lambdas and local classes **are** descended
 * into, since a local they capture is a local the new method must be handed.
 */
internal fun collectReferences(
	regionPaths: List<TreePath>,
	root: CompilationUnitTree,
	positions: SourcePositions,
	trees: Trees,
): List<Reference> {
	val references = mutableListOf<Reference>()

	fun consider(path: TreePath) {
		val leaf = path.leaf
		if (leaf !is IdentifierTree) return
		val element = runCatching { trees.getElement(path) }.getOrNull() ?: return
		val span = spanOf(root, positions, leaf) ?: return
		references += Reference(element, span.start)
	}

	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun scan(
				tree: Tree?,
				p: Unit?,
			): Unit? {
				if (tree == null) return null
				consider(TreePath(currentPath, tree))
				return super.scan(tree, p)
			}
		}

	regionPaths.forEach { path ->
		consider(path)
		scanner.scan(path, null)
	}
	return references.sortedBy { it.offset }
}

/**
 * The name of the variable the region reassigns but does not declare, or null when there is none (R7).
 *
 * Only a reassignment of the variable *itself* counts. An element write through a captured reference
 * (`arr[i] = x`) mutates what the caller can already see, so it needs no rule -- the same distinction
 * `writeOffsetsFor` draws for extract variable.
 */
internal fun outerReassignmentIn(
	regionPaths: List<TreePath>,
	span: TextSpan,
	anchor: AnchorMember,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): String? {
	var found: String? = null

	fun consider(path: TreePath) {
		if (found != null) return
		val target =
			when (val leaf = path.leaf) {
				is AssignmentTree -> leaf.variable
				is CompoundAssignmentTree -> leaf.variable
				is UnaryTree -> if (leaf.kind in INCREMENT_KINDS) leaf.expression else null
				else -> null
			} as? IdentifierTree ?: return

		val element = runCatching { trees.getElement(TreePath(path, target)) }.getOrNull() ?: return
		if (element.kind !in LOCAL_KINDS) return
		val declaration = declarationSpanOf(element, root, trees, positions) ?: return
		if (span.contains(declaration)) return
		if (!anchor.span.contains(declaration)) return
		found = element.simpleName.toString()
	}

	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun scan(
				tree: Tree?,
				p: Unit?,
			): Unit? {
				if (tree == null) return null
				consider(TreePath(currentPath, tree))
				return super.scan(tree, p)
			}
		}

	regionPaths.forEach { path ->
		consider(path)
		scanner.scan(path, null)
	}
	return found
}

/**
 * The variables that become parameters: referenced, declared inside the anchor member, declared outside
 * the region. In first textual appearance order, so the signature reads in the order the body uses it.
 *
 * A field needs nothing -- the new method is a member of the same class -- and a declaration in another
 * file cannot be a local at all.
 */
internal fun capturedVariablesIn(
	references: List<Reference>,
	span: TextSpan,
	anchor: AnchorMember,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): List<VariableElement> {
	val captured = LinkedHashMap<VariableElement, Unit>()
	for (reference in references) {
		val element = reference.element as? VariableElement ?: continue
		if (element.kind !in LOCAL_KINDS) continue
		val declaration = declarationSpanOf(element, root, trees, positions) ?: continue
		if (span.contains(declaration)) continue
		if (!anchor.span.contains(declaration)) continue
		captured[element] = Unit
	}
	return captured.keys.toList()
}

internal fun declarationSpanOf(
	element: Element,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): TextSpan? {
	val path = runCatching { trees.getPath(element) }.getOrNull() ?: return null
	if (path.compilationUnit !== root) return null
	return spanOf(root, positions, path.leaf)
}

/** Whether [other] lies entirely inside this span. */
internal fun TextSpan.contains(other: TextSpan): Boolean = start <= other.start && other.end <= end
