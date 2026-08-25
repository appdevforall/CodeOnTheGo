package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.java.compiler.CompileTask
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.util.Elements
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.DoWhileLoopTree
import openjdk.source.tree.EnhancedForLoopTree
import openjdk.source.tree.ForLoopTree
import openjdk.source.tree.WhileLoopTree
import openjdk.source.util.JavacTask
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.Trees
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("JavaExtractVariablePlanner")

/**
 * The whole plan from one attributed compile.
 *
 * Returns an empty plan both when there is nothing to extract and whenever anything here throws: the
 * action framework catches only `IllegalArgumentException` and this runs on a scope with no exception
 * handler, so an uncaught throw would crash the app. "Nothing to extract" is always safe.
 */
fun buildExtractionPlan(
	task: CompileTask,
	file: Path,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int?,
): ExtractionPlan =
	runCatching {
		val root = task.root(file)
		val fileText = root.sourceFile.getCharContent(true).toString()
		val trees = Trees.instance(task.task)
		val positions = trees.sourcePositions

		val syntax = candidateExpressionsAt(task.task, root, fileText, selectionStart, selectionEnd)
		if (syntax.paths.isEmpty()) return ExtractionPlan.empty(fileText, documentVersion)

		// A property of the file, not of a rung: derived once here rather than re-scanning the whole
		// source for every ancestor of every candidate.
		val indentUnit = detectIndentUnit(fileText)

		ExtractionPlan(
			fileText = fileText,
			documentVersion = documentVersion,
			candidates =
				syntax.paths.mapNotNull { path ->
					candidateFor(path, task.task.elements, root, trees, positions, fileText, indentUnit)
				},
		)
	}.getOrElse { error ->
		// Cancellation is the coroutine's business, not a failure to degrade from: swallowing it would
		// leave the action running after its scope was cancelled.
		if (error is CancellationException) throw error
		logger.warn("Failed to build a Java extract-variable plan for {}", file, error)
		ExtractionPlan.empty()
	}

/**
 * The pass itself, over an already-attributed unit.
 *
 * Split from the [CompileTask] overload so it needs nothing but javac, which is what lets the analysis
 * be tested against a source string with no project model and no tooling API.
 *
 * [fileText] must be the text [root]'s positions were computed against.
 */
fun buildExtractionPlan(
	task: JavacTask,
	root: CompilationUnitTree,
	fileText: String,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int?,
): ExtractionPlan =
	runCatching {
		val trees = Trees.instance(task)
		val positions = trees.sourcePositions

		val syntax = candidateExpressionsAt(task, root, fileText, selectionStart, selectionEnd)
		if (syntax.paths.isEmpty()) return ExtractionPlan.empty(fileText, documentVersion)

		val indentUnit = detectIndentUnit(fileText)

		ExtractionPlan(
			fileText = fileText,
			documentVersion = documentVersion,
			candidates =
				syntax.paths.mapNotNull { path ->
					candidateFor(path, task.elements, root, trees, positions, fileText, indentUnit)
				},
		)
	}.getOrElse { error ->
		if (error is CancellationException) throw error
		logger.warn("Failed to build a Java extract-variable plan", error)
		ExtractionPlan.empty(fileText, documentVersion)
	}

/** Null when the type cannot be written as source, or nothing remains of the legal scope chain. */
private fun candidateFor(
	path: TreePath,
	elements: Elements,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
	fileText: String,
	indentUnit: String,
): CandidateExpression? {
	val declaredType = declaredTypeTextFor(path, trees, root) ?: return null
	val span = spanOf(root, positions, path.leaf) ?: return null

	// Resolved once and threaded down: the ceiling, the occurrence search and the write search all ask
	// the same question, and each answer costs a scan of the candidate plus a getElement per name.
	val candidateElements = referencedElements(path, trees)

	val frames =
		truncateAtCeiling(
			enclosingScopeFrames(path, root, positions, fileText, indentUnit),
			referencedDeclarationCeiling(candidateElements, root, positions, trees),
		)
	if (frames.isEmpty()) return null

	val scopes =
		frames.mapNotNull {
			scopeOptionFor(path, candidateElements, span, it, root, trees, positions, fileText)
		}
	if (scopes.isEmpty()) return null

	val takenNames = namesInScopeAt(path, root, trees, elements)

	return CandidateExpression(
		label = collapseForLabel(fileText.substring(span.start, span.end)),
		span = span,
		declaredType = declaredType,
		suggestedName = suggestVariableName(path.leaf, declaredType, takenNames),
		takenNames = takenNames,
		scopes = scopes,
	)
}

/**
 * Null when the rung cannot be honoured. Both declines run before the occurrence search, so a refused
 * rung costs nothing -- and refusing here turns a sheet whose confirm must fail into an up-front
 * "nothing to extract".
 */
private fun scopeOptionFor(
	candidatePath: TreePath,
	candidateElements: List<Element?>,
	span: TextSpan,
	frame: ScopeFrame,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
	fileText: String,
): ScopeOption? {
	// javac has no parent pointers, so every getPath is a full-unit walk. One here serves both scans
	// below and the lambda-target lookup, and bounds them to this rung's subtree.
	val scopePath = TreePath.getPath(root, frame.scopeTree) ?: return null

	val anchorForm =
		when (val form = frame.anchorForm) {
			is AnchorForm.ExistingBlock -> {
				if (blockPlacementFor(fileText, form, span) is BlockPlacement.Refused) return null
				form
			}

			is AnchorForm.ConvertExpressionBody -> {
				convertExpressionBodyForm(form, scopePath, trees) ?: return null
			}

			is AnchorForm.WrapInBraces -> {
				form
			}
		}

	val matches =
		findOccurrences(candidatePath, candidateElements, frame, scopePath, root, positions, fileText, trees)
	val writes = writeOffsetsFor(candidateElements, frame, scopePath, root, positions, trees)
	if (hoistSkipsWrite(candidatePath, span, frame, anchorForm, root, positions, writes)) return null
	val sound = excludeUnsoundOccurrences(matches, span, writes)
	val occurrences = servableOccurrences(fileText, anchorForm, sound, span)

	return ScopeOption(label = frame.label, anchorForm = anchorForm, occurrences = occurrences)
}

/**
 * Whether hoisting to this rung would carry the declaration over a write to something the expression
 * reads -- which compiles, and silently freezes the value, so declining the rung is the only signal
 * available. The inner rungs survive, so the user is never left with nothing.
 *
 * Two shapes. A write between the anchor statement and the occurrence is simply skipped: extracting
 * `limit + 1` from `if (c) { limit = 5; foo(limit + 1); }` at the method rung anchors on the `if` and
 * reads the pre-assignment value. A write inside a loop the occurrence sits in but the anchor does not
 * is worse: `while (limit < 10) { foo(limit + 1); limit++; }` hoisted out of the loop evaluates once and
 * feeds every iteration the same value.
 */
private fun hoistSkipsWrite(
	candidatePath: TreePath,
	span: TextSpan,
	frame: ScopeFrame,
	anchorForm: AnchorForm,
	root: CompilationUnitTree,
	positions: SourcePositions,
	writes: List<Int>,
): Boolean {
	if (writes.isEmpty()) return false

	if (anchorForm is AnchorForm.ExistingBlock) {
		val anchor = anchorOf(anchorForm, span)
		if (anchor != null && writes.any { it in anchor.start until span.start }) return true
	}

	var current: TreePath? = candidatePath.parentPath
	while (current != null) {
		val loop = current.leaf
		if (loop is WhileLoopTree || loop is DoWhileLoopTree || loop is ForLoopTree || loop is EnhancedForLoopTree) {
			// No span means the plan and the tree disagree about this loop, so its containment cannot be
			// checked either way.
			val loopSpan = spanOf(root, positions, loop) ?: return true
			// The rung is itself inside the loop, so the declaration re-runs with every iteration.
			if (loopSpan.start <= frame.scopeSpan.start && frame.scopeSpan.end <= loopSpan.end) return false
			if (writes.any { it in loopSpan.start until loopSpan.end }) return true
		}
		current = current.parentPath
	}
	return false
}

/**
 * A lambda's `needsReturn` comes from the **functional interface method's** return type, never the
 * body's: `Runnable r = () -> list.add(x);` is legal even though `add` returns `boolean`, and emitting
 * `return list.add(x);` there would not compile. Unresolvable target or method declines the rung.
 */
private fun convertExpressionBodyForm(
	form: AnchorForm.ConvertExpressionBody,
	scopePath: TreePath,
	trees: Trees,
): AnchorForm.ConvertExpressionBody? {
	if (form.returnKeyword == "yield") return form

	// scopePath's leaf is the body expression, so the lambda is its parent.
	val lambdaPath = scopePath.parentPath ?: return null
	val target = runCatching { trees.getTypeMirror(lambdaPath) }.getOrNull() ?: return null
	if (target !is DeclaredType) return null
	val abstractMethod = singleAbstractMethodOf(target) ?: return null
	return form.copy(needsReturn = abstractMethod.returnType.kind != TypeKind.VOID)
}

/**
 * `Object`'s methods are re-declarable on a functional interface without counting against its single
 * abstract method, so they are discounted by name and arity.
 */
private fun singleAbstractMethodOf(target: DeclaredType): ExecutableElement? {
	val element = runCatching { target.asElement() }.getOrNull() ?: return null
	return runCatching { element.enclosedElements }
		.getOrNull()
		?.filterIsInstance<ExecutableElement>()
		?.filter { it.kind == ElementKind.METHOD }
		?.filter { Modifier.ABSTRACT in it.modifiers }
		?.filterNot(::isObjectMethod)
		?.singleOrNull()
}

private fun isObjectMethod(method: ExecutableElement): Boolean {
	val name = method.simpleName.toString()
	val arity = method.parameters.size
	return (name == "equals" && arity == 1) ||
		(name == "hashCode" && arity == 0) ||
		(name == "toString" && arity == 0)
}
