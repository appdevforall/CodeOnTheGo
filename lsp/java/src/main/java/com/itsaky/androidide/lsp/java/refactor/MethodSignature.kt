package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.leadingIndentAt
import com.itsaky.androidide.lsp.refactor.uniqueName
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.util.Elements
import openjdk.source.tree.BlockTree
import openjdk.source.tree.BreakTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.ContinueTree
import openjdk.source.tree.DoWhileLoopTree
import openjdk.source.tree.EnhancedForLoopTree
import openjdk.source.tree.ForLoopTree
import openjdk.source.tree.IdentifierTree
import openjdk.source.tree.LabeledStatementTree
import openjdk.source.tree.LambdaExpressionTree
import openjdk.source.tree.LiteralTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.ReturnTree
import openjdk.source.tree.SwitchExpressionTree
import openjdk.source.tree.SwitchTree
import openjdk.source.tree.Tree
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
