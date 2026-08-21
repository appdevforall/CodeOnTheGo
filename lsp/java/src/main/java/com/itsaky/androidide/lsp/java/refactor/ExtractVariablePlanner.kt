package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.java.compiler.CompileTask
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.util.Elements
import openjdk.source.tree.CompilationUnitTree
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
	documentVersion: Int,
): ExtractionPlan =
	runCatching {
		val root = task.root(file)
		val fileText = root.sourceFile.getCharContent(true).toString()
		val trees = Trees.instance(task.task)
		val positions = trees.sourcePositions

		val syntax = candidateExpressionsAt(task.task, root, fileText, selectionStart, selectionEnd)
		if (syntax.paths.isEmpty()) return ExtractionPlan.empty(fileText, documentVersion)

		ExtractionPlan(
			fileText = fileText,
			documentVersion = documentVersion,
			candidates =
				syntax.paths.mapNotNull { path ->
					candidateFor(path, task.task.elements, root, trees, positions, fileText)
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
	documentVersion: Int,
): ExtractionPlan =
	runCatching {
		val trees = Trees.instance(task)
		val positions = trees.sourcePositions

		val syntax = candidateExpressionsAt(task, root, fileText, selectionStart, selectionEnd)
		if (syntax.paths.isEmpty()) return ExtractionPlan.empty(fileText, documentVersion)

		ExtractionPlan(
			fileText = fileText,
			documentVersion = documentVersion,
			candidates =
				syntax.paths.mapNotNull { path ->
					candidateFor(path, task.elements, root, trees, positions, fileText)
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
): CandidateExpression? {
	val declaredType = declaredTypeTextFor(path, trees, root) ?: return null
	val span = spanOf(root, positions, path.leaf) ?: return null

	val frames =
		truncateAtCeiling(
			enclosingScopeFrames(path, root, positions, fileText),
			referencedDeclarationCeiling(path, root, positions, trees),
		)
	if (frames.isEmpty()) return null

	val scopes = frames.mapNotNull { scopeOptionFor(path, span, it, root, trees, positions, fileText) }
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
	span: TextSpan,
	frame: ScopeFrame,
	root: CompilationUnitTree,
	trees: Trees,
	positions: SourcePositions,
	fileText: String,
): ScopeOption? {
	val anchorForm =
		when (val form = frame.anchorForm) {
			is AnchorForm.ExistingBlock -> {
				if (blockPlacementFor(fileText, form, span) is BlockPlacement.Refused) return null
				form
			}

			is AnchorForm.ConvertExpressionBody -> {
				convertExpressionBodyForm(form, frame, root, trees) ?: return null
			}

			is AnchorForm.WrapInBraces -> {
				form
			}
		}

	val matches = findOccurrences(candidatePath, frame, root, positions, fileText, trees)
	val writes = writeOffsetsFor(candidatePath, frame, root, positions, trees)
	val sound = excludeUnsoundOccurrences(matches, span, writes)
	val occurrences = servableOccurrences(fileText, anchorForm, sound, span)

	return ScopeOption(label = frame.label, anchorForm = anchorForm, occurrences = occurrences)
}

/**
 * A lambda's `needsReturn` comes from the **functional interface method's** return type, never the
 * body's: `Runnable r = () -> list.add(x);` is legal even though `add` returns `boolean`, and emitting
 * `return list.add(x);` there would not compile. Unresolvable target or method declines the rung.
 */
private fun convertExpressionBodyForm(
	form: AnchorForm.ConvertExpressionBody,
	frame: ScopeFrame,
	root: CompilationUnitTree,
	trees: Trees,
): AnchorForm.ConvertExpressionBody? {
	if (form.returnKeyword == "yield") return form

	// frame.scopeTree is the body expression, so the lambda is its parent. TreePath.getPath walks the
	// unit once, which is what javac offers in the absence of parent pointers.
	val lambdaPath = TreePath.getPath(root, frame.scopeTree)?.parentPath ?: return null
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
