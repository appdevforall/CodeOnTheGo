package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.lang.model.util.Elements
import openjdk.source.tree.ArrayAccessTree
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
import openjdk.source.tree.VariableTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.TreeScanner
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
 *
 * [scopePath] is the rung's own subtree, so the walk covers what [ScopeFrame.searchRange] names rather
 * than the whole compilation unit -- this runs once per rung per candidate, on the coroutine the user is
 * waiting on. [candidateElements] is resolved once by the caller for the same reason.
 */
internal fun findOccurrences(
	candidatePath: TreePath,
	candidateElements: List<Element?>,
	frame: ScopeFrame,
	scopePath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	trees: Trees,
): List<TextSpan> {
	val candidateSpan = spanOf(root, positions, candidatePath.leaf) ?: return emptyList()
	val candidateKind = candidatePath.leaf.kind
	val candidateText = normalizeSource(fileText.substring(candidateSpan.start, candidateSpan.end))

	val matches = mutableListOf<TextSpan>()

	fun consider(path: TreePath) {
		val tree = path.leaf
		if (tree !is ExpressionTree || tree.kind != candidateKind) return
		val span = spanOf(root, positions, tree) ?: return
		if (span.start < frame.searchRange.start || span.end > frame.searchRange.end) return
		if (span == candidateSpan) {
			matches += span
			return
		}
		if (isLegalExtractionTarget(path, trees) &&
			// Shape alone is not enough: a `case` label matches every structural test and then fails to
			// compile once the local is substituted in.
			isExtractionPosition(path) &&
			normalizeSource(fileText.substring(span.start, span.end)) == candidateText &&
			referencedElements(path, trees) == candidateElements
		) {
			matches += span
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
		}
	// `TreePathScanner.scan(TreePath, P)` dispatches straight to the leaf's visitor, so the subtree's own
	// root never reaches the override above -- and for an expression-bodied rung that root can *be* the
	// candidate.
	consider(scopePath)
	scanner.scan(scopePath, null)

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
internal fun referencedElements(
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
 * Writes to any variable the candidate reads, feeding [excludeUnsoundOccurrences]. Effectively-final
 * locals need no special case: a local that is never written has no write to find.
 *
 * Two sets, because `final` means different things to the two shapes. A `final` reference cannot be
 * reassigned, which is the role Kotlin's `val` check plays -- but `final int[] arr` says nothing about
 * `arr[i] = 99`, so an element write counts against every referenced variable, final or not.
 *
 * Bounded to [scopePath]'s subtree for the same reason as [findOccurrences], and given the candidate's
 * already-resolved elements rather than resolving them again.
 */
internal fun writeOffsetsFor(
	candidateElements: List<Element?>,
	frame: ScopeFrame,
	scopePath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
	trees: Trees,
): List<Int> {
	val referenced = candidateElements.filterIsInstance<VariableElement>().toSet()
	if (referenced.isEmpty()) return emptyList()
	val reassignable = referenced.filterNot { Modifier.FINAL in it.modifiers }.toSet()

	val offsets = mutableListOf<Int>()

	fun consider(path: TreePath) {
		val tree = path.leaf
		val target =
			when (tree) {
				is AssignmentTree -> tree.variable
				is CompoundAssignmentTree -> tree.variable
				is UnaryTree -> if (tree.kind in INCREMENT_KINDS) tree.expression else null
				else -> null
			} ?: return
		val span = spanOf(root, positions, target) ?: return
		if (span.start < frame.searchRange.start || span.end > frame.searchRange.end) return
		// `getElement` answers nothing for an array access -- an element is not a declaration -- so the
		// write is attributed to the array the access reads, which is what the candidate names too.
		val written = arrayBaseOf(target) ?: target
		val element = runCatching { trees.getElement(TreePath(path, written)) }.getOrNull() ?: return
		val accepted = if (written === target) reassignable else referenced
		if (element in accepted) offsets += span.start
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
	consider(scopePath)
	scanner.scan(scopePath, null)
	return offsets.sorted()
}

/**
 * The array an assignment target indexes into, unwrapping nested accesses (`grid[r][c]` -> `grid`), or
 * null when the target is not an array access at all.
 */
private fun arrayBaseOf(target: Tree): Tree? {
	var current = target as? ArrayAccessTree ?: return null
	while (true) {
		val next = current.expression
		if (next !is ArrayAccessTree) return next
		current = next
	}
}

internal val INCREMENT_KINDS =
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
internal fun referencedDeclarationCeiling(
	candidateElements: List<Element?>,
	root: CompilationUnitTree,
	positions: SourcePositions,
	trees: Trees,
): TextSpan? {
	var narrowest: TextSpan? = null
	for (element in candidateElements) {
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

		is BlockTree -> owner

		// A `for`, enhanced-`for`, try-with-resources or `instanceof` pattern variable is scoped to the
		// construct that declares it. Returning the construct itself is correct, and the safe default for
		// anything unrecognised. A construct that holds no anchorable rung -- `o instanceof String s` --
		// leaves the chain empty, and [truncateAtCeiling] falls back to the innermost rung rather than
		// refusing the candidate.
		else -> owner
	}

internal val LOCAL_KINDS =
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
	names += localNamesInEnclosingBodies(candidatePath)

	var scope = runCatching { trees.getScope(candidatePath) }.getOrNull()
	while (scope != null && seenScopes.add(scope)) {
		val current = scope
		runCatching { current.localElements }
			.getOrNull()
			?.forEach { element -> names += element.simpleName.toString() }

		val enclosing = runCatching { current.enclosingClass }.getOrNull()
		if (enclosing != null && seenClasses.add(enclosing)) {
			// Methods live in a separate namespace, so a local never shadows one: `int size = list.size();`
			// is legal. Only fields and enum constants make the bare name mean something else.
			runCatching { elements.getAllMembers(enclosing) }
				.getOrNull()
				?.filter { it.kind == ElementKind.FIELD || it.kind == ElementKind.ENUM_CONSTANT }
				?.forEach { member -> names += member.simpleName.toString() }
		}
		scope = runCatching { current.enclosingScope }.getOrNull()
	}
	return names
}

/**
 * Every local declared anywhere in the executable bodies enclosing [candidatePath].
 *
 * `Trees.getScope` reports only what is in scope *at* the candidate, because javac has attributed the
 * method only that far. Java forbids two locals of the same name in a block whatever their order, so a
 * name taken by a declaration further down the same body is still taken -- and it gates the sheet's text
 * field as well as the suggestion.
 */
private fun localNamesInEnclosingBodies(candidatePath: TreePath): Set<String> {
	val names = mutableSetOf<String>()
	var body = enclosingExecutableBody(candidatePath)
	while (body != null) {
		object : TreeScanner<Unit, Unit>() {
			override fun visitVariable(
				node: VariableTree,
				p: Unit?,
			): Unit? {
				names += node.name.toString()
				return super.visitVariable(node, p)
			}
		}.scan(body.leaf, null)
		body = body.parentPath?.let { enclosingExecutableBody(it) }
	}
	return names
}
