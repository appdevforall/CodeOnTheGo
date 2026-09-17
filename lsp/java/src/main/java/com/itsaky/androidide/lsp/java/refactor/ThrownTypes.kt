package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.type.TypeMirror
import jdkx.lang.model.type.UnionType
import jdkx.lang.model.util.Elements
import jdkx.lang.model.util.Types
import openjdk.source.tree.AssignmentTree
import openjdk.source.tree.CatchTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.LambdaExpressionTree
import openjdk.source.tree.MethodInvocationTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.NewClassTree
import openjdk.source.tree.ThrowTree
import openjdk.source.tree.Tree
import openjdk.source.tree.TryTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.Trees

/**
 * The checked exception types the new method must declare (R10), or null when one cannot be written.
 *
 * Both halves matter: under-declaring leaves the moved body uncompilable, and over-declaring breaks the
 * call site, which is only obliged to handle what the region actually threw. Nested lambdas, local
 * classes and anonymous classes are not descended into -- a checked exception thrown there is
 * constrained by that construct's own signature and never reaches the anchor member.
 */
internal fun thrownCheckedTypesIn(
	regionPaths: List<TreePath>,
	span: TextSpan,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
	types: Types,
	elements: Elements,
	names: TypeNames,
): List<String>? {
	val runtimeException = typeOf(elements, "java.lang.RuntimeException")
	val error = typeOf(elements, "java.lang.Error")
	val rendered = LinkedHashSet<String>()
	var unrenderable = false

	fun record(
		type: TypeMirror,
		sitePath: TreePath,
	) {
		// A generic `throws E` is declared on the callee and only instantiated at the call site, which
		// javac's public API does not hand back. Rendering `E` would emit a name nothing declares, and
		// guessing its bound would over-declare, so the region is declined instead (ADR 0014).
		if (type.kind == TypeKind.TYPEVAR) {
			unrenderable = true
			return
		}
		// `throw e` in `catch (A | B e)` is typed as the union, not as either alternative, so
		// dropping it here would leave the moved body throwing what nothing declares.
		if (type.kind == TypeKind.UNION) {
			(type as UnionType).alternatives.forEach { alternative -> record(alternative, sitePath) }
			return
		}
		if (type.kind != TypeKind.DECLARED) return
		if (runtimeException != null && types.isAssignable(type, runtimeException)) return
		if (error != null && types.isAssignable(type, error)) return
		if (isCaughtWithin(type, sitePath, span, root, trees, positions, types)) return
		val text = names.render(type)
		if (text == null) unrenderable = true else rendered += text
	}

	fun consider(path: TreePath) {
		when (val leaf = path.leaf) {
			is MethodInvocationTree, is NewClassTree -> {
				val element = runCatching { trees.getElement(path) }.getOrNull() as? ExecutableElement ?: return
				element.thrownTypes.forEach { record(it, path) }
			}

			is ThrowTree -> {
				// A rethrown catch parameter is precise (JLS 11.2.2): the enclosing method declares
				// what the `try` block can throw, not the parameter's declared type. Recording the
				// declared type would make `catch (Exception e) { throw e; }` declare Exception and
				// leave the call site with an unreported exception.
				val precise = preciseRethrowSourceOf(path, leaf, trees, positions, root)
				if (precise != null) {
					val fromTry =
						thrownCheckedTypesIn(
							regionPaths = listOf(precise.blockPath),
							span = precise.blockSpan,
							root = root,
							trees = trees,
							positions = positions,
							types = types,
							elements = elements,
							names = names,
						)
					if (fromTry == null) unrenderable = true else rendered += fromTry
					return
				}

				val type =
					runCatching { trees.getTypeMirror(TreePath(path, leaf.expression)) }.getOrNull() ?: return
				record(type, path)
			}

			is TryTree -> {
				// A resource's close() throws too, and there is no invocation node to find it on.
				leaf.resources.forEach { resource ->
					val resourcePath = TreePath(path, resource)
					val type = runCatching { trees.getTypeMirror(resourcePath) }.getOrNull() ?: return@forEach
					closeThrownTypesOf(type, elements).forEach { record(it, resourcePath) }
				}
			}

			else -> {
				Unit
			}
		}
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

			override fun visitLambdaExpression(
				node: LambdaExpressionTree,
				p: Unit?,
			): Unit? = null

			override fun visitClass(
				node: ClassTree,
				p: Unit?,
			): Unit? = null

			override fun visitMethod(
				node: MethodTree,
				p: Unit?,
			): Unit? = null
		}

	regionPaths.forEach { path ->
		consider(path)
		scanner.scan(path, null)
	}

	return if (unrenderable) null else rendered.toList()
}

/** The `try` block a precise rethrow draws its types from. */
private class PreciseRethrow(
	val blockPath: TreePath,
	val blockSpan: TextSpan,
)

/**
 * The `try` block behind [throwTree] when it rethrows a caught parameter, or null when it does not.
 *
 * Under JLS 11.2.2 such a throw carries only what the `try` block can throw and the clause catches,
 * so the block is the thing to analyse. Answering the block rather than a narrowed type list keeps
 * this in terms the caller already handles: it runs the same analysis over it and unions the result,
 * which also covers a multi-catch parameter without enumerating alternatives.
 *
 * A parameter reassigned in the clause is not effectively final, so javac does not apply precise
 * rethrow to it and neither does this.
 */
private fun preciseRethrowSourceOf(
	path: TreePath,
	throwTree: ThrowTree,
	trees: Trees,
	positions: SourcePositions,
	root: CompilationUnitTree,
): PreciseRethrow? {
	val thrown = throwTree.expression as? IdentifierTree ?: return null
	val thrownElement =
		runCatching { trees.getElement(TreePath(path, thrown)) }.getOrNull() ?: return null

	var cursor: TreePath? = path
	while (cursor != null) {
		val leaf = cursor.leaf
		if (leaf is CatchTree) {
			val parameterElement =
				runCatching { trees.getElement(TreePath(cursor, leaf.parameter)) }.getOrNull()
			if (parameterElement != thrownElement) return null
			if (isReassignedIn(leaf, thrownElement, cursor, trees)) return null

			val tryTree = cursor.parentPath?.leaf as? TryTree ?: return null
			val blockPath = TreePath(cursor.parentPath, tryTree.block)
			val blockSpan = spanOf(root, positions, tryTree.block) ?: return null
			return PreciseRethrow(blockPath, blockSpan)
		}
		if (leaf is MethodTree || leaf is LambdaExpressionTree || leaf is ClassTree) return null
		cursor = cursor.parentPath
	}
	return null
}

/** Whether [element] is assigned anywhere in [clause], which would cost it precise rethrow. */
private fun isReassignedIn(
	clause: CatchTree,
	element: Element,
	clausePath: TreePath,
	trees: Trees,
): Boolean {
	var reassigned = false
	object : TreePathScanner<Unit, Unit>() {
		override fun visitAssignment(
			node: AssignmentTree,
			p: Unit?,
		): Unit? {
			val target = node.variable
			if (target is IdentifierTree) {
				val targetElement =
					runCatching { trees.getElement(TreePath(currentPath, target)) }.getOrNull()
				if (targetElement == element) reassigned = true
			}
			return super.visitAssignment(node, p)
		}
	}.scan(clausePath, null)
	return reassigned
}

/**
 * Whether a `try` **inside the region** already handles [type] at [sitePath].
 *
 * Only a `try` whose whole statement is inside the region counts: one that encloses the region handles
 * the call site just as it handled the code, and declaring nothing there would leave the new method's
 * body uncompilable. A site sitting in a `catch` or `finally` is not protected by that `try`'s own
 * catches, which is why the block has to contain it.
 */
private fun isCaughtWithin(
	type: TypeMirror,
	sitePath: TreePath,
	span: TextSpan,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
	types: Types,
): Boolean {
	var child: Tree = sitePath.leaf
	var current: TreePath? = sitePath.parentPath
	while (current != null) {
		val leaf = current.leaf
		val leafSpan = spanOf(root, positions, leaf)
		if (leafSpan != null && !span.contains(leafSpan)) return false
		if (leaf is TryTree && (leaf.block === child || leaf.resources.any { it === child })) {
			val tryPath = current
			if (leaf.catches.any { catchesType(type, tryPath, it, trees, types) }) return true
		}
		child = leaf
		current = current.parentPath
	}
	return false
}

/**
 * Whether one `catch` clause catches [thrown]. A multi-catch's alternatives are separate types, so each
 * is asked in turn.
 */
private fun catchesType(
	thrown: TypeMirror,
	tryPath: TreePath,
	catch: CatchTree,
	trees: Trees,
	types: Types,
): Boolean {
	val parameterPath = TreePath(TreePath(tryPath, catch), catch.parameter)
	val caught = runCatching { trees.getTypeMirror(parameterPath) }.getOrNull() ?: return false
	if (caught is UnionType) return caught.alternatives.any { types.isAssignable(thrown, it) }
	return types.isAssignable(thrown, caught)
}

/** The thrown types of the `close()` a try-with-resources resource will call. */
private fun closeThrownTypesOf(
	resourceType: TypeMirror,
	elements: Elements,
): List<TypeMirror> {
	val element = runCatching { (resourceType as? DeclaredType)?.asElement() }.getOrNull() as? TypeElement ?: return emptyList()
	return runCatching { elements.getAllMembers(element) }
		.getOrNull()
		.orEmpty()
		.filterIsInstance<ExecutableElement>()
		.firstOrNull { it.simpleName.toString() == "close" && it.parameters.isEmpty() }
		?.thrownTypes
		.orEmpty()
}

private fun typeOf(
	elements: Elements,
	name: String,
): TypeMirror? = runCatching { elements.getTypeElement(name)?.asType() }.getOrNull()
