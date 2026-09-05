package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.leadingIndentAt
import com.itsaky.androidide.lsp.refactor.uniqueName
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.element.NestingKind
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.TypeParameterElement
import jdkx.lang.model.element.VariableElement
import jdkx.lang.model.type.ArrayType
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.type.TypeMirror
import jdkx.lang.model.type.TypeVariable
import jdkx.lang.model.type.UnionType
import jdkx.lang.model.type.WildcardType
import jdkx.lang.model.util.Elements
import jdkx.lang.model.util.Types
import openjdk.source.tree.AssignmentTree
import openjdk.source.tree.BlockTree
import openjdk.source.tree.BreakTree
import openjdk.source.tree.CatchTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.CompoundAssignmentTree
import openjdk.source.tree.ContinueTree
import openjdk.source.tree.DoWhileLoopTree
import openjdk.source.tree.EnhancedForLoopTree
import openjdk.source.tree.ForLoopTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.LabeledStatementTree
import openjdk.source.tree.LambdaExpressionTree
import openjdk.source.tree.LiteralTree
import openjdk.source.tree.MethodInvocationTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.NewClassTree
import openjdk.source.tree.ReturnTree
import openjdk.source.tree.SwitchExpressionTree
import openjdk.source.tree.SwitchTree
import openjdk.source.tree.ThrowTree
import openjdk.source.tree.Tree
import openjdk.source.tree.TryTree
import openjdk.source.tree.UnaryTree
import openjdk.source.tree.VariableTree
import openjdk.source.tree.WhileLoopTree
import openjdk.source.tree.YieldTree
import openjdk.source.util.JavacTask
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner
import openjdk.source.util.Trees

/** Either a fully derived candidate or the reason the region could not become one. */
internal sealed interface AnalysisResult {
	data class Analysed(
		val candidate: ExtractMethodCandidate,
	) : AnalysisResult

	data class Refused(
		val refusal: ExtractionRefusal,
	) : AnalysisResult
}

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
 * Derives everything the sheet renders and the edit builder emits for one region, or the reason it
 * cannot be moved faithfully (ADR 0014).
 *
 * The order of the checks is the order of the refusals' specificity: a region that both uses a type
 * parameter and exits itself reports the type parameter, which is the more actionable of the two.
 */
internal fun analyseRegion(
	region: ExtractionRegion,
	task: JavacTask,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
	fileText: String,
): AnalysisResult {
	val anchor = anchorMemberFor(region.path, root, positions) ?: return refuse(ExtractionRefusal.NotASingleRegion)
	val span = region.span
	val names = TypeNames(root)
	val elements = task.elements
	val types = task.types
	val regionPaths = regionPathsOf(region)
	val anchorMethodElement = anchor.method?.let { runCatching { trees.getElement(anchor.path) }.getOrNull() }

	val references = collectReferences(regionPaths, root, positions, trees)

	references
		.firstOrNull { isAnchorTypeParameter(it.element, anchorMethodElement) }
		?.let { return refuse(ExtractionRefusal.UsesTypeParameter(it.element.simpleName.toString())) }

	references
		.firstOrNull { isCapturedLocalType(it.element, span, root, trees, positions) }
		?.let { return refuse(ExtractionRefusal.CapturedLocalDeclaration(localTypeNameOf(it.element))) }

	outerReassignmentIn(regionPaths, span, anchor, root, trees, positions)
		?.let { return refuse(ExtractionRefusal.ReassignsOuterVar(it)) }

	val parameters = mutableListOf<MethodParameter>()
	for (captured in capturedVariablesIn(references, span, anchor, root, trees, positions)) {
		val type = captured.asType()
		localTypeNameIn(type)?.let { return refuse(ExtractionRefusal.CapturedLocalDeclaration(captured.simpleName.toString())) }
		anchorTypeVariableIn(type, anchorMethodElement)?.let { return refuse(ExtractionRefusal.UsesTypeParameter(it)) }
		val typeText = names.render(type) ?: return refuse(ExtractionRefusal.UnrenderableType)
		parameters += MethodParameter(name = captured.simpleName.toString(), typeText = typeText)
	}

	val referencedAfter = referencedAfterRegion(region, anchor, root, trees, positions)

	escapingLocalClassIn(region, referencedAfter, trees)
		?.let { return refuse(ExtractionRefusal.CapturedLocalDeclaration(it)) }

	val outputs = outputsOf(region, referencedAfter, trees)
	if (outputs.size > 1) {
		return refuse(ExtractionRefusal.MultipleOutputs(outputs.map { it.simpleName.toString() }))
	}

	val exits = exitsIn(regionPaths, span, root, positions)
	val tailReturn = tailReturnOf(region, anchor, exits, outputs)
	if (exits.isNotEmpty() && !tailReturn) return refuse(ExtractionRefusal.ExitsRegion)

	val shape =
		bodyAndCallSite(region, anchor, outputs.singleOrNull(), tailReturn, root, trees, names, anchorMethodElement)
			?: return refuse(ExtractionRefusal.UnrenderableType)
	if (shape is Shape.Refusal) return refuse(shape.refusal)
	val body = (shape as Shape.Derived)

	val thrown =
		thrownCheckedTypesIn(regionPaths, span, root, trees, positions, types, elements, names)
			?: return refuse(ExtractionRefusal.UnrenderableType)

	val takenNames = methodNamesIn(anchor.classPath, trees, elements)
	val insertOffset = absorbTrailingSemicolon(fileText, anchor.span.end)

	return AnalysisResult.Analysed(
		ExtractMethodCandidate(
			label = collapseForLabel(fileText.substring(span.start, span.end)),
			span = span,
			suggestedName = suggestedNameFor(region, body.returnTypeText, takenNames),
			takenNames = takenNames,
			modifiers = if (anchor.isStatic) listOf("private", "static") else listOf("private"),
			parameters = parameters,
			returnTypeText = body.returnTypeText,
			thrownTypes = thrown,
			body = body.body,
			callSite = body.callSite,
			insertOffset = insertOffset,
			insertIndent = leadingIndentAt(fileText, anchor.span.start),
			textBlockSpans = textBlockSpansIn(regionPaths, root, positions, fileText),
		),
	)
}

private fun refuse(refusal: ExtractionRefusal): AnalysisResult = AnalysisResult.Refused(refusal)

/** The trees the region actually covers: one expression, or each statement of the range. */
internal fun regionPathsOf(region: ExtractionRegion): List<TreePath> =
	when (region) {
		is ExtractionRegion.Expression -> {
			listOf(region.path)
		}

		is ExtractionRegion.Statements -> {
			val blockPath = region.path.parentPath
			if (blockPath == null) listOf(region.path) else region.statements.map { TreePath(blockPath, it) }
		}
	}

/**
 * The nearest ancestor that is a direct member of a class.
 *
 * A nested class is never climbed past: a region inside a member of an inner, local or anonymous class
 * anchors on that member, so the new method lands in the class whose members the region reads.
 */
internal fun anchorMemberFor(
	regionPath: TreePath,
	root: CompilationUnitTree,
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
				isStatic = isStaticMember(member),
				method = member as? MethodTree,
			)
		}
		current = parent
	}
}

/** A static anchor forces a `static` method: no instance to resolve `this` or an instance member on. */
private fun isStaticMember(member: Tree): Boolean =
	when (member) {
		is MethodTree -> Modifier.STATIC in member.modifiers.flags
		is VariableTree -> Modifier.STATIC in member.modifiers.flags
		is BlockTree -> member.isStatic
		else -> false
	}

private class Reference(
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
private fun collectReferences(
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

/** Whether [element] is a type parameter declared on the anchor method itself (R10). */
private fun isAnchorTypeParameter(
	element: Element,
	anchorMethodElement: Element?,
): Boolean =
	element is TypeParameterElement &&
		anchorMethodElement != null &&
		runCatching { element.genericElement == anchorMethodElement }.getOrDefault(false)

/**
 * Whether [element] is a local or anonymous class the region uses but does not contain. Its name is
 * reachable only from inside the anchor member, so it stops resolving once the body moves.
 */
private fun isCapturedLocalType(
	element: Element,
	span: TextSpan,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): Boolean {
	// A reference to the class *itself* is only one of the shapes: `new Helper()` resolves to Helper's
	// constructor and `Helper.CONST` to a field, so a member's own declaring class is asked about too.
	val type =
		element as? TypeElement
			?: element.enclosingElement as? TypeElement
			?: return false
	if (type.nestingKind != NestingKind.LOCAL && type.nestingKind != NestingKind.ANONYMOUS) return false
	val declaration = declarationSpanOf(type, root, trees, positions) ?: return false
	return !span.contains(declaration)
}

/**
 * The name of the variable the region reassigns but does not declare, or null when there is none (R7).
 *
 * Only a reassignment of the variable *itself* counts. An element write through a captured reference
 * (`arr[i] = x`) mutates what the caller can already see, so it needs no rule -- the same distinction
 * `writeOffsetsFor` draws for extract variable.
 */
private fun outerReassignmentIn(
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
private fun capturedVariablesIn(
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

/**
 * The locals the region declares that the code after it still needs (R7).
 *
 * Only declarations that are *direct* statements of the range can be outputs: one nested in an inner
 * block is out of scope after the region whatever this refactoring does.
 */
private fun outputsOf(
	region: ExtractionRegion,
	referencedAfter: Set<Element>,
	trees: Trees,
): List<VariableElement> = declaredDirectlyIn<VariableTree, VariableElement>(region, trees).filter { it in referencedAfter }

/**
 * A local class the region declares and the code after it still names (R7).
 *
 * A class is not a value, so unlike a local it cannot be handed back through a return: once the
 * declaration moves into the new method, every mention of the name after the region stops resolving.
 */
private fun escapingLocalClassIn(
	region: ExtractionRegion,
	referencedAfter: Set<Element>,
	trees: Trees,
): String? =
	declaredDirectlyIn<ClassTree, TypeElement>(region, trees)
		.firstOrNull { it in referencedAfter }
		?.simpleName
		?.toString()

/** The elements the region declares as *direct* statements: one nested deeper is out of scope anyway. */
private inline fun <reified T : Tree, reified E : Element> declaredDirectlyIn(
	region: ExtractionRegion,
	trees: Trees,
): List<E> {
	if (region !is ExtractionRegion.Statements) return emptyList()
	val blockPath = region.path.parentPath ?: return emptyList()
	return region.statements
		.filterIsInstance<T>()
		.mapNotNull { runCatching { trees.getElement(TreePath(blockPath, it)) }.getOrNull() }
		.filterIsInstance<E>()
}

/** Every declaration the code after the region still names, within the anchor member. */
private fun referencedAfterRegion(
	region: ExtractionRegion,
	anchor: AnchorMember,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
): Set<Element> {
	if (region !is ExtractionRegion.Statements) return emptySet()
	val referenced = mutableSetOf<Element>()
	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun visitIdentifier(
				node: IdentifierTree,
				p: Unit?,
			): Unit? {
				val span = spanOf(root, positions, node)
				if (span != null && span.start >= region.span.end) {
					runCatching { trees.getElement(currentPath) }.getOrNull()?.let { referenced += it }
				}
				return super.visitIdentifier(node, p)
			}
		}
	scanner.scan(anchor.path, null)
	return referenced
}

private class Exit(
	val tree: Tree,
)

/**
 * Every jump inside the region whose target lies outside it (R8).
 *
 * The scan stops at a lambda, a class body and a method: a `return` belonging to a declaration the
 * region *contains* moves with that declaration and never crosses the boundary, so counting it would
 * refuse a good extraction over something the user did not write.
 */
private fun exitsIn(
	regionPaths: List<TreePath>,
	span: TextSpan,
	root: CompilationUnitTree,
	positions: SourcePositions,
): List<Exit> {
	val exits = mutableListOf<Exit>()

	fun consider(path: TreePath) {
		val leaf = path.leaf
		val target =
			when (leaf) {
				is ReturnTree -> null
				is BreakTree -> jumpTargetOf(path, leaf.label?.toString(), breakable = true)
				is ContinueTree -> jumpTargetOf(path, leaf.label?.toString(), breakable = false)
				is YieldTree -> enclosingSwitchExpressionOf(path)
				else -> return
			}

		if (leaf is ReturnTree) {
			exits += Exit(leaf)
			return
		}
		val targetSpan = target?.let { spanOf(root, positions, it) }
		if (targetSpan == null || !span.contains(targetSpan)) exits += Exit(leaf)
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
	return exits
}

/**
 * What an unlabelled `break`/`continue` jumps to, or the statement a labelled one names.
 *
 * `break` also targets a `switch`; `continue` only ever targets a loop.
 */
private fun jumpTargetOf(
	sitePath: TreePath,
	label: String?,
	breakable: Boolean,
): Tree? {
	var current: TreePath? = sitePath.parentPath
	while (current != null) {
		val leaf = current.leaf
		if (label != null) {
			if (leaf is LabeledStatementTree && leaf.label.toString() == label) return leaf
		} else if (isLoop(leaf) || (breakable && (leaf is SwitchTree || leaf is SwitchExpressionTree))) {
			return leaf
		}
		current = current.parentPath
	}
	return null
}

private fun enclosingSwitchExpressionOf(sitePath: TreePath): Tree? {
	var current: TreePath? = sitePath.parentPath
	while (current != null) {
		val leaf = current.leaf
		if (leaf is SwitchExpressionTree) return leaf
		current = current.parentPath
	}
	return null
}

private fun isLoop(tree: Tree): Boolean =
	tree is WhileLoopTree || tree is DoWhileLoopTree || tree is ForLoopTree || tree is EnhancedForLoopTree

/**
 * Whether the region ends in a `return` that can move with it (R8).
 *
 * The region's enclosing executable body has to be the anchor member's own: a `return` inside a lambda
 * returns from the lambda, so taking the anchor's return type would emit a method returning something
 * its body never returns.
 */
private fun tailReturnOf(
	region: ExtractionRegion,
	anchor: AnchorMember,
	exits: List<Exit>,
	outputs: List<VariableElement>,
): Boolean {
	if (region !is ExtractionRegion.Statements) return false
	if (anchor.method == null) return false
	if (outputs.isNotEmpty()) return false
	val last = region.statements.lastOrNull() as? ReturnTree ?: return false
	if (exits.size != 1 || exits.single().tree !== last) return false
	// enclosingExecutableBody answers the *declaration* that owns the body -- the MethodTree, the
	// LambdaExpressionTree, or the initializer's own BlockTree -- so the anchor member's own tree is what
	// it has to equal. A lambda in between means the `return` returns from the lambda, not the anchor.
	return enclosingExecutableBody(region.path)?.leaf === anchor.path.leaf
}

private sealed interface Shape {
	data class Derived(
		val returnTypeText: String,
		val body: ExtractedBody,
		val callSite: CallSiteForm,
	) : Shape

	data class Refusal(
		val refusal: ExtractionRefusal,
	) : Shape
}

/** The return type, the body form and the call-site form, which are one decision (R6). */
private fun bodyAndCallSite(
	region: ExtractionRegion,
	anchor: AnchorMember,
	output: VariableElement?,
	tailReturn: Boolean,
	root: CompilationUnitTree,
	trees: Trees,
	names: TypeNames,
	anchorMethodElement: Element?,
): Shape? {
	if (region is ExtractionRegion.Expression) {
		val type = runCatching { trees.getTypeMirror(region.path) }.getOrNull() ?: return null
		if (type.kind == TypeKind.VOID) {
			return Shape.Derived("void", ExtractedBody.ExpressionBody(needsReturn = false), CallSiteForm.Call)
		}
		anchorTypeVariableIn(type, anchorMethodElement)?.let { return Shape.Refusal(ExtractionRefusal.UsesTypeParameter(it)) }
		localTypeNameIn(type)?.let { return Shape.Refusal(ExtractionRefusal.CapturedLocalDeclaration(it)) }
		// The same derivation extract variable uses, so a poly expression keeps javac's inference and a
		// constant that only fits because it was narrowed in place is declined rather than widened.
		val typeText = declaredTypeTextFor(region.path, trees, root) ?: return null
		return Shape.Derived(typeText, ExtractedBody.ExpressionBody(needsReturn = true), CallSiteForm.Call)
	}

	if (tailReturn) {
		val element = anchorMethodElement as? ExecutableElement ?: return null
		val returnType = element.returnType
		if (returnType.kind == TypeKind.VOID) {
			return Shape.Derived("void", ExtractedBody.StatementBody(trailingReturn = null), CallSiteForm.Return)
		}
		anchorTypeVariableIn(returnType, anchorMethodElement)
			?.let { return Shape.Refusal(ExtractionRefusal.UsesTypeParameter(it)) }
		val typeText = names.render(returnType) ?: return null
		return Shape.Derived(typeText, ExtractedBody.StatementBody(trailingReturn = null), CallSiteForm.Return)
	}

	if (output != null) {
		val type = output.asType()
		anchorTypeVariableIn(type, anchorMethodElement)?.let { return Shape.Refusal(ExtractionRefusal.UsesTypeParameter(it)) }
		localTypeNameIn(type)?.let { return Shape.Refusal(ExtractionRefusal.CapturedLocalDeclaration(it)) }
		val typeText = names.render(type) ?: return null
		val name = output.simpleName.toString()
		return Shape.Derived(
			typeText,
			ExtractedBody.StatementBody(trailingReturn = "return $name;"),
			CallSiteForm.AssignOutput(typeText, name),
		)
	}

	return Shape.Derived("void", ExtractedBody.StatementBody(trailingReturn = null), CallSiteForm.CallStatement)
}

/**
 * The checked exception types the new method must declare (R10), or null when one cannot be written.
 *
 * Both halves matter: under-declaring leaves the moved body uncompilable, and over-declaring breaks the
 * call site, which is only obliged to handle what the region actually threw. Nested lambdas, local
 * classes and anonymous classes are not descended into -- a checked exception thrown there is
 * constrained by that construct's own signature and never reaches the anchor member.
 */
private fun thrownCheckedTypesIn(
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
				val type =
					runCatching { trees.getTypeMirror(TreePath(path, leaf.expression)) }.getOrNull() ?: return
				record(type, path)
			}

			is TryTree -> {
				// A resource's close() throws too, and there is no invocation node to find it on.
				leaf.resources.forEach { resource ->
					val type = runCatching { trees.getTypeMirror(TreePath(path, resource)) }.getOrNull() ?: return@forEach
					closeThrownTypesOf(type, elements).forEach { record(it, path) }
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

/** Every method and constructor name visible in the insertion class, inherited ones included (R12). */
internal fun methodNamesIn(
	classPath: TreePath,
	trees: Trees,
	elements: Elements,
): Set<String> {
	val element = runCatching { trees.getElement(classPath) }.getOrNull() as? TypeElement ?: return emptySet()
	return runCatching { elements.getAllMembers(element) }
		.getOrNull()
		.orEmpty()
		.filter { it.kind == ElementKind.METHOD || it.kind == ElementKind.CONSTRUCTOR }
		.map { it.simpleName.toString() }
		.toSet()
}

/**
 * An expression region reads its name from its own shape and type, as extract variable does; a
 * statement range has no expression to read, and inventing a verb from statement shapes is guesswork.
 */
private fun suggestedNameFor(
	region: ExtractionRegion,
	returnTypeText: String,
	takenNames: Set<String>,
): String =
	when (region) {
		is ExtractionRegion.Expression -> suggestVariableName(region.path.leaf, returnTypeText, takenNames)
		is ExtractionRegion.Statements -> uniqueName("extracted", takenNames)
	}

/** The text block literals inside the region, whose interior must survive re-indentation verbatim. */
private fun textBlockSpansIn(
	regionPaths: List<TreePath>,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
): List<TextSpan> {
	val spans = mutableListOf<TextSpan>()
	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun visitLiteral(
				node: LiteralTree,
				p: Unit?,
			): Unit? {
				val span = spanOf(root, positions, node)
				if (span != null && span.end <= fileText.length && fileText.startsWith("\"\"\"", span.start)) {
					spans += span
				}
				return super.visitLiteral(node, p)
			}
		}
	regionPaths.forEach { scanner.scan(it, null) }
	return spans
}

/** The local class a reference names, whether directly or through one of its members. */
private fun localTypeNameOf(element: Element): String {
	val type = element as? TypeElement ?: element.enclosingElement as? TypeElement ?: return element.simpleName.toString()
	return type.simpleName.toString().ifEmpty { element.simpleName.toString() }
}

private fun declarationSpanOf(
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

/**
 * The name of a local or anonymous class appearing anywhere in [type], or null when there is none.
 *
 * A depth limit rather than a visited set: `Enum<E extends Enum<E>>` is a real shape, and comparing
 * `TypeMirror`s for identity is not reliable enough to terminate on.
 */
private fun localTypeNameIn(
	type: TypeMirror,
	depth: Int = 0,
): String? {
	if (depth > MAX_TYPE_DEPTH) return null
	return when (type) {
		is DeclaredType -> {
			val element = runCatching { type.asElement() }.getOrNull() as? TypeElement
			if (element != null &&
				(element.nestingKind == NestingKind.LOCAL || element.nestingKind == NestingKind.ANONYMOUS)
			) {
				element.simpleName.toString().ifEmpty { "anonymous class" }
			} else {
				type.typeArguments.firstNotNullOfOrNull { localTypeNameIn(it, depth + 1) }
			}
		}

		is ArrayType -> {
			localTypeNameIn(type.componentType, depth + 1)
		}

		is WildcardType -> {
			type.extendsBound?.let { localTypeNameIn(it, depth + 1) }
				?: type.superBound?.let { localTypeNameIn(it, depth + 1) }
		}

		is UnionType -> {
			type.alternatives.firstNotNullOfOrNull { localTypeNameIn(it, depth + 1) }
		}

		else -> {
			null
		}
	}
}

/** The name of a type variable declared on the anchor method appearing anywhere in [type] (R10). */
private fun anchorTypeVariableIn(
	type: TypeMirror,
	anchorMethodElement: Element?,
	depth: Int = 0,
): String? {
	if (anchorMethodElement == null || depth > MAX_TYPE_DEPTH) return null
	return when (type) {
		is TypeVariable -> {
			val element = runCatching { type.asElement() }.getOrNull()
			if (isAnchorTypeParameter(element ?: return null, anchorMethodElement)) {
				element.simpleName.toString()
			} else {
				null
			}
		}

		is DeclaredType -> {
			type.typeArguments.firstNotNullOfOrNull { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
		}

		is ArrayType -> {
			anchorTypeVariableIn(type.componentType, anchorMethodElement, depth + 1)
		}

		is WildcardType -> {
			type.extendsBound?.let { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
				?: type.superBound?.let { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
		}

		is UnionType -> {
			type.alternatives.firstNotNullOfOrNull { anchorTypeVariableIn(it, anchorMethodElement, depth + 1) }
		}

		else -> {
			null
		}
	}
}

private const val MAX_TYPE_DEPTH = 8

/**
 * Renders a type as source, shortened only where the file already resolves the short form.
 *
 * The import sets are read once per plan rather than per type: every candidate in one plan renders
 * against the same file.
 */
internal class TypeNames(
	root: CompilationUnitTree,
) {
	private val imported = importedNamesOf(root)
	private val starred = starImportedPackagesOf(root)

	fun render(type: TypeMirror): String? {
		val text = runCatching { type.toString() }.getOrNull() ?: return null
		if (isUnrenderableTypeText(text)) return null
		if (isValuelessKind(type.kind)) return null
		return shortenTypeText(text, imported, starred)
	}
}
