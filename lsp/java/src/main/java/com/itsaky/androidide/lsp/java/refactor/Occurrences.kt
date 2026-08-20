package com.itsaky.androidide.lsp.java.refactor

import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.lang.model.util.Elements
import openjdk.source.tree.AssignmentTree
import openjdk.source.tree.BlockTree
import openjdk.source.tree.CatchTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.CompoundAssignmentTree
import openjdk.source.tree.ExpressionTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.LambdaExpressionTree
import openjdk.source.tree.MemberSelectTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.Scope
import openjdk.source.tree.Tree
import openjdk.source.tree.UnaryTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.Trees
import java.util.Collections
import java.util.IdentityHashMap

/**
 * "The same expression" is normalized source text plus an identical ordered sequence of resolved
 * elements. The element check is the point: text alone would match `config.timeout` inside a nested
 * lambda where `config` is a different `config`, and ADFA-3324 states it outright -- text-based matching
 * breaks things.
 *
 * Matches must themselves be legal targets: in `a.a`, a candidate of `a` matches the selector too, and
 * rewriting it would produce `v.v`. Overlaps are dropped so no site is rewritten twice.
 */
fun findOccurrences(
	candidatePath: TreePath,
	frame: ScopeFrame,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	trees: Trees,
): List<TextSpan> {
	val candidateSpan = spanOf(root, positions, candidatePath.leaf) ?: return emptyList()
	val candidateKind = candidatePath.leaf.kind
	val candidateText = normalizeSource(fileText.substring(candidateSpan.start, candidateSpan.end))
	val candidateElements = referencedElements(candidatePath, trees)

	val matches = mutableListOf<TextSpan>()
	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun scan(
				tree: Tree?,
				p: Unit?,
			): Unit? {
				if (tree == null) return null
				val span = spanOf(root, positions, tree)
				if (span != null &&
					span.start >= frame.searchRange.start &&
					span.end <= frame.searchRange.end &&
					tree.kind == candidateKind &&
					tree is ExpressionTree
				) {
					val path = TreePath(currentPath, tree)
					if (span == candidateSpan) {
						matches += span
					} else if (isLegalExtractionTarget(path, trees) &&
						normalizeSource(fileText.substring(span.start, span.end)) == candidateText &&
						referencedElements(path, trees) == candidateElements
					) {
						matches += span
					}
				}
				return super.scan(tree, p)
			}
		}
	scanner.scan(TreePath(root), null)

	val accepted = mutableListOf<TextSpan>()
	for (match in matches.sortedBy { it.start }) {
		if (accepted.none { it.overlaps(match) }) accepted += match
	}
	return accepted
}

/**
 * Same-unit elements are the same `Symbol` instance for the same declaration, so list equality answers
 * "the same declarations?" exactly. An unresolvable reference contributes null, making two identical
 * expressions compare unequal -- the safe answer, since it cannot be shown to hold the same value.
 */
private fun referencedElements(
	path: TreePath,
	trees: Trees,
): List<Element?> {
	val elements = mutableListOf<Element?>()
	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun visitIdentifier(
				node: IdentifierTree,
				p: Unit?,
			): Unit? {
				elements += runCatching { trees.getElement(currentPath) }.getOrNull()
				return super.visitIdentifier(node, p)
			}

			override fun visitMemberSelect(
				node: MemberSelectTree,
				p: Unit?,
			): Unit? {
				elements += runCatching { trees.getElement(currentPath) }.getOrNull()
				return super.visitMemberSelect(node, p)
			}
		}
	scanner.scan(path, null)
	return elements
}

/**
 * Writes to any non-`final` variable the candidate reads, feeding [excludeUnsoundOccurrences]. A `final`
 * variable cannot be written, which is the role Kotlin's `val` check plays. Effectively-final locals need
 * no special case: a local that is never written has no write to find.
 */
fun writeOffsetsFor(
	candidatePath: TreePath,
	frame: ScopeFrame,
	root: CompilationUnitTree,
	positions: SourcePositions,
	trees: Trees,
): List<Int> {
	val mutables =
		referencedElements(candidatePath, trees)
			.filterIsInstance<VariableElement>()
			.filterNot { Modifier.FINAL in it.modifiers }
			.toSet()
	if (mutables.isEmpty()) return emptyList()

	val offsets = mutableListOf<Int>()
	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun scan(
				tree: Tree?,
				p: Unit?,
			): Unit? {
				if (tree == null) return null
				val target =
					when (tree) {
						is AssignmentTree -> tree.variable
						is CompoundAssignmentTree -> tree.variable
						is UnaryTree -> if (tree.kind in INCREMENT_KINDS) tree.expression else null
						else -> null
					}
				if (target != null) {
					val span = spanOf(root, positions, target)
					if (span != null && span.start >= frame.searchRange.start && span.end <= frame.searchRange.end) {
						val element =
							runCatching { trees.getElement(TreePath(TreePath(currentPath, tree), target)) }.getOrNull()
						if (element in mutables) offsets += span.start
					}
				}
				return super.scan(tree, p)
			}
		}
	scanner.scan(TreePath(root), null)
	return offsets.sorted()
}

private val INCREMENT_KINDS =
	setOf(
		Tree.Kind.PREFIX_INCREMENT,
		Tree.Kind.PREFIX_DECREMENT,
		Tree.Kind.POSTFIX_INCREMENT,
		Tree.Kind.POSTFIX_DECREMENT,
	)

/**
 * What stops a hoist escaping a lambda it depends on: a candidate using a lambda parameter gets that
 * lambda's body back as its ceiling, and [truncateAtCeiling] drops every outer rung. Only locals,
 * parameters, exception parameters and resource variables constrain anything -- a field or a library
 * declaration does not.
 */
fun referencedDeclarationCeiling(
	candidatePath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	trees: Trees,
): TextSpan? {
	var narrowest: TextSpan? = null
	for (element in referencedElements(candidatePath, trees)) {
		if (element == null || element.kind !in LOCAL_KINDS) continue
		val declaration = runCatching { trees.getPath(element) }.getOrNull() ?: continue
		if (declaration.compilationUnit !== root) continue
		val scope = constrainingScopeFor(declaration) ?: continue
		val span = spanOf(root, positions, scope) ?: continue
		if (narrowest == null || span.length < narrowest.length) narrowest = span
	}
	return narrowest
}

/**
 * Deliberately *not* [enclosingExecutableBody]: a parameter is not inside the body it scopes, so asking
 * which body encloses the *declaration* walks past the lambda and answers the enclosing method -- which
 * would let a lambda-parameter-using expression hoist clean out of the lambda that binds it.
 */
private fun constrainingScopeFor(declaration: TreePath): Tree? =
	when (val owner = declaration.parentPath?.leaf) {
		is LambdaExpressionTree -> owner.body
		is MethodTree -> owner.body
		is CatchTree -> owner.block
		else -> owner?.takeIf { it is BlockTree }
	}

private val LOCAL_KINDS =
	setOf(
		ElementKind.LOCAL_VARIABLE,
		ElementKind.PARAMETER,
		ElementKind.EXCEPTION_PARAMETER,
		ElementKind.RESOURCE_VARIABLE,
		ElementKind.BINDING_VARIABLE,
	)

/**
 * `Trees.getScope` answers this directly, and `Elements.getAllMembers` **includes inherited members**, so
 * a generated local cannot silently shadow one -- which Kotlin's syntactic walk cannot manage. A local in
 * a sibling method is absent: it is in no enclosing scope, and treating it as taken refuses a legal name.
 */
fun namesInScopeAt(
	candidatePath: TreePath,
	root: CompilationUnitTree,
	trees: Trees,
	elements: Elements,
): Set<String> {
	val names = mutableSetOf<String>()

	root.typeDecls
		.filterIsInstance<ClassTree>()
		.forEach { names += it.simpleName.toString() }

	/*
	 * javac's outermost scopes do not reliably terminate the getEnclosingScope() chain -- a star-import
	 * scope can report itself -- so the walk is guarded by identity rather than trusting a null. Without
	 * this the loop never ends and the action hangs the compiler's semaphore.
	 */
	val seenScopes = Collections.newSetFromMap(IdentityHashMap<Scope, Boolean>())
	val seenClasses = mutableSetOf<TypeElement>()
	var scope = runCatching { trees.getScope(candidatePath) }.getOrNull()
	while (scope != null && seenScopes.add(scope)) {
		val current = scope
		runCatching { current.localElements }
			.getOrNull()
			?.forEach { element -> names += element.simpleName.toString() }

		val enclosing = runCatching { current.enclosingClass }.getOrNull()
		if (enclosing != null && seenClasses.add(enclosing)) {
			runCatching { elements.getAllMembers(enclosing) }
				.getOrNull()
				?.forEach { member -> names += member.simpleName.toString() }
		}
		scope = runCatching { current.enclosingScope }.getOrNull()
	}
	return names
}
