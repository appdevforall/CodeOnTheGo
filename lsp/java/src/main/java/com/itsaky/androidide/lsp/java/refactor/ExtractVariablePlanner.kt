package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.java.compiler.CompileTask
import com.itsaky.androidide.lsp.refactor.BlockAnchor
import com.itsaky.androidide.lsp.refactor.BlockPlacement
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.refactor.anchorOf
import com.itsaky.androidide.lsp.refactor.blockPlacementFor
import com.itsaky.androidide.lsp.refactor.detectIndentUnit
import com.itsaky.androidide.lsp.refactor.excludeUnsoundOccurrences
import jdkx.lang.model.element.Element
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.util.Elements
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.DoWhileLoopTree
import openjdk.source.tree.EnhancedForLoopTree
import openjdk.source.tree.ForLoopTree
import openjdk.source.tree.Tree
import openjdk.source.tree.WhileLoopTree
import openjdk.source.util.JavacTask
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreeScanner
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
): ExtractionPlan {
	var fileText = ""
	return runCatching {
		val root = task.root(file)
		fileText = root.sourceFile.getCharContent(true).toString()
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
		ExtractionPlan.empty(fileText, documentVersion)
	}
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
			scopeOptionFor(path, candidateElements, span, it, root, trees, elements, positions, fileText)
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
	elements: Elements,
	positions: SourcePositions,
	fileText: String,
): ScopeOption? {
	// javac has no parent pointers, so every getPath is a full-unit walk. One here serves both scans
	// below and the lambda-target lookup, and bounds them to this rung's subtree.
	val scopePath = TreePath.getPath(root, frame.scopeTree) ?: return null

	val anchorForm =
		when (val form = frame.anchorForm) {
			is AnchorForm.ExistingBlock -> {
				if (blockPlacementFor(fileText, form.block, span) is BlockPlacement.Refused) return null
				form
			}

			is AnchorForm.ConvertExpressionBody -> {
				convertExpressionBodyForm(form, scopePath, trees, elements) ?: return null
			}

			is AnchorForm.WrapInBraces -> {
				form
			}
		}

	val matches =
		findOccurrences(candidatePath, candidateElements, frame, scopePath, root, positions, fileText, trees)
	val writes = writeOffsetsFor(candidateElements, frame, scopePath, root, positions, trees)
	// A loop outside this rung's subtree necessarily contains the rung, which is the case
	// hoistsOverLoopWrite reads as sound anyway, so the scan is bounded to the rung.
	val loops = if (writes.isEmpty()) emptyList() else loopSpansWithin(scopePath, root, positions) ?: return null
	if (hoistSkipsWrite(span, frame, anchorForm, loops, writes)) return null

	val block = (anchorForm as? AnchorForm.ExistingBlock)?.block
	val occurrences =
		excludeUnsoundOccurrences(matches, span, writes)
			.filterNot { it != span && hoistsOverLoopWrite(it, frame, loops, writes) }
			.dropWhile { it != span && leadingOccurrenceIsUnservable(fileText, block, it, writes) }

	return ScopeOption(label = frame.label, anchorForm = anchorForm, occurrences = occurrences)
}

/**
 * Whether a *leading* occurrence must be shed before the rewrite can anchor. Both reasons are answered
 * together, in one pass, because each is only meaningful about the site the rewrite would actually
 * anchor on: shedding for one reason promotes the next site, which then has to be asked about both.
 *
 * The first reason is placement -- the site's own statement shares its line inside a multi-line block,
 * so the declaration has nowhere to go (see [blockPlacementFor]). The second is a write between that
 * statement and the occurrence: extracting the trailing `use(limit + 1)` of
 * `if (c) { limit = 5; use(limit + 1); } use(limit + 1);` at the method rung would anchor on the `if`
 * and hoist the declaration above `limit = 5`.
 *
 * Leading sites are dropped rather than declining the rung, so the user's own site stays extractable;
 * a later occurrence never becomes the anchor, so it is left alone.
 */
private fun leadingOccurrenceIsUnservable(
	fileText: String,
	block: BlockAnchor?,
	occurrence: TextSpan,
	writes: List<Int>,
): Boolean {
	if (block == null) return false
	val anchor = anchorOf(block, occurrence) ?: return true
	if (blockPlacementFor(fileText, block, occurrence) is BlockPlacement.Refused) return true
	return writes.any { it in anchor.start until occurrence.start }
}

/**
 * Whether hoisting to this rung would carry the declaration over a write to something the expression
 * reads -- which compiles, and silently freezes the value, so declining the rung is the only signal
 * available. The inner rungs survive, so the user is never left with nothing.
 *
 * A write between the anchor statement and the candidate is simply skipped: extracting `limit + 1` from
 * `if (c) { limit = 5; foo(limit + 1); }` at the method rung anchors on the `if` and reads the
 * pre-assignment value.
 */
private fun hoistSkipsWrite(
	span: TextSpan,
	frame: ScopeFrame,
	anchorForm: AnchorForm,
	loops: List<TextSpan>,
	writes: List<Int>,
): Boolean {
	if (writes.isEmpty()) return false

	if (anchorForm is AnchorForm.ExistingBlock) {
		val anchor = anchorOf(anchorForm.block, span)
		if (anchor != null && writes.any { it in anchor.start until span.start }) return true
	}

	return hoistsOverLoopWrite(span, frame, loops, writes)
}

/**
 * Whether [target] sits in a loop the rung does not, which a write inside that loop makes unsound:
 * `while (limit < 10) { foo(limit + 1); limit++; }` hoisted out of the loop evaluates once and feeds
 * every iteration the same value.
 *
 * Asked per served occurrence, not just about the candidate. An occurrence can sit in a loop the
 * candidate is not in at all -- `use(limit + 1); while (limit < 10) { use(limit + 1); limit++; }` -- and
 * folding that one freezes the loop.
 *
 * Loops are visited innermost-first, and the first one containing the rung ends the walk: from there
 * outwards the declaration re-runs with every iteration, so nothing is skipped.
 */
private fun hoistsOverLoopWrite(
	target: TextSpan,
	frame: ScopeFrame,
	loops: List<TextSpan>,
	writes: List<Int>,
): Boolean {
	for (loop in loops.filter { it.start <= target.start && target.end <= it.end }.sortedBy { it.length }) {
		if (loop.start <= frame.scopeSpan.start && frame.scopeSpan.end <= loop.end) return false
		if (writes.any { it in loop.start until loop.end }) return true
	}
	return false
}

/** Null when javac has no positions for some loop here, leaving containment unanswerable either way. */
private fun loopSpansWithin(
	scopePath: TreePath,
	root: CompilationUnitTree,
	positions: SourcePositions,
): List<TextSpan>? {
	val spans = mutableListOf<TextSpan>()
	var incomplete = false
	object : TreeScanner<Unit, Unit>() {
		override fun scan(
			tree: Tree?,
			p: Unit?,
		): Unit? {
			if (tree is WhileLoopTree || tree is DoWhileLoopTree || tree is ForLoopTree || tree is EnhancedForLoopTree) {
				val span = spanOf(root, positions, tree)
				if (span == null) incomplete = true else spans += span
			}
			return super.scan(tree, p)
		}
	}.scan(scopePath.leaf, null)
	return if (incomplete) null else spans
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
	elements: Elements,
): AnchorForm.ConvertExpressionBody? {
	if (form.returnKeyword == "yield") return form

	// scopePath's leaf is the body expression, so the lambda is its parent.
	val lambdaPath = scopePath.parentPath ?: return null
	val target = runCatching { trees.getTypeMirror(lambdaPath) }.getOrNull() ?: return null
	if (target !is DeclaredType) return null
	val abstractMethod = singleAbstractMethodOf(target, elements) ?: return null
	return form.copy(needsReturn = abstractMethod.returnType.kind != TypeKind.VOID)
}

/**
 * `getAllMembers` rather than `enclosedElements`, because a functional interface may *inherit* its
 * single abstract method -- `interface Mapper extends Function<String, Integer> {}` encloses nothing at
 * all, and reading only what it declares declined every lambda typed as one.
 *
 * `Object`'s methods are re-declarable on a functional interface without counting against its single
 * abstract method, so they are discounted by name and arity.
 */
private fun singleAbstractMethodOf(
	target: DeclaredType,
	elements: Elements,
): ExecutableElement? {
	val element = runCatching { target.asElement() }.getOrNull() as? TypeElement ?: return null
	return runCatching { elements.getAllMembers(element) }
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
