package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.element.Modifier
import openjdk.source.tree.BlockTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.Tree
import openjdk.source.tree.VariableTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.Trees

/**
 * The class member the new method becomes a sibling of (R4).
 *
 * "Direct member of a `ClassTree`" covers every anchor uniformly: a method, a constructor, an
 * initializer block, and a field whose initializer holds a lambda. Java has no local-method form, so
 * unlike Kotlin there is no insert-before case and no "nowhere to anchor" refusal.
 */
internal class AnchorMember(
	val path: TreePath,
	val classPath: TreePath,
	val span: TextSpan,
	val isStatic: Boolean,
	val method: MethodTree?,
)

/**
 * The nearest ancestor that is a direct member of a class.
 *
 * A nested class is never climbed past: a region inside a member of an inner, local or anonymous class
 * anchors on that member, so the new method lands in the class whose members the region reads.
 */
internal fun anchorMemberFor(
	regionPath: TreePath,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): AnchorMember? {
	var current: TreePath = regionPath
	while (true) {
		val parent = current.parentPath ?: return null
		if (parent.leaf is ClassTree) {
			val member = current.leaf
			val span = spanOf(root, positions, member) ?: return null
			return AnchorMember(
				path = current,
				classPath = parent,
				span = span,
				isStatic = isStaticMember(current, trees),
				method = member as? MethodTree,
			)
		}
		current = parent
	}
}

/** A static anchor forces a `static` method: no instance to resolve `this` or an instance member on. */
private fun isStaticMember(
	memberPath: TreePath,
	trees: Trees,
): Boolean {
	val element = runCatching { trees.getElement(memberPath) }.getOrNull()
	if (element != null) return Modifier.STATIC in element.modifiers
	return (memberPath.leaf as? BlockTree)?.isStatic == true
}

/** The trees the region actually covers: one expression, or each statement of the range. */
internal fun regionPathsOf(region: ExtractionRegion): List<TreePath> =
	when (region) {
		is ExtractionRegion.Expression -> {
			listOf(region.path)
		}

		is ExtractionRegion.Statements -> {
			val blockPath = region.path.parentPath ?: error("a statements region's path must have a block parent")
			region.statements.map { TreePath(blockPath, it) }
		}
	}
