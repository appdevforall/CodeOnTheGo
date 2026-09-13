package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.java.compiler.CompileTask
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.util.JavacTask
import openjdk.source.util.Trees
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("JavaExtractMethodPlanner")

/**
 * The whole plan from one attributed compile.
 *
 * Degrades to `CouldNotAnalyse` whenever anything here throws: the action framework catches only
 * `IllegalArgumentException` and this runs on a scope with no exception handler, so an uncaught throw
 * would crash the app. The refusal is deliberately neutral -- the selection may have been perfectly
 * good, and blaming it would teach the user the wrong thing (R14).
 */
fun buildExtractMethodPlan(
	task: CompileTask,
	file: Path,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int?,
): ExtractMethodPlan {
	var fileText = ""
	return runCatching {
		val root = task.root(file)
		fileText = root.sourceFile.getCharContent(true).toString()
		planFor(task.task, root, fileText, selectionStart, selectionEnd, documentVersion)
	}.getOrElse { error ->
		// Cancellation is the coroutine's business, not a failure to degrade from: swallowing it would
		// leave the action running after its scope was cancelled.
		if (error is CancellationException) throw error
		logger.warn("Failed to build a Java extract-method plan for {}", file, error)
		ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse, fileText, documentVersion)
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
fun buildExtractMethodPlan(
	task: JavacTask,
	root: CompilationUnitTree,
	fileText: String,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int?,
): ExtractMethodPlan =
	runCatching {
		planFor(task, root, fileText, selectionStart, selectionEnd, documentVersion)
	}.getOrElse { error ->
		if (error is CancellationException) throw error
		logger.warn("Failed to build a Java extract-method plan", error)
		ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse, fileText, documentVersion)
	}

/**
 * Every region the selection offers, analysed.
 *
 * When nothing survives, the **first** region's refusal is the one reported: regions arrive innermost
 * first, so that is the reason for the thing closest to the cursor rather than for some ancestor the
 * user was not pointing at.
 */
private fun planFor(
	task: JavacTask,
	root: CompilationUnitTree,
	fileText: String,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int?,
): ExtractMethodPlan {
	val trees = Trees.instance(task)
	val positions = trees.sourcePositions

	val regions = resolveExtractionRegions(task, root, positions, fileText, selectionStart, selectionEnd)
	if (regions.isEmpty()) {
		return ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion, fileText, documentVersion)
	}

	val results = regions.map { analyseRegion(it, task, root, trees, positions, fileText) }
	val candidates = results.filterIsInstance<AnalysisResult.Analysed>().map { it.candidate }
	if (candidates.isEmpty()) {
		val refusal =
			results.filterIsInstance<AnalysisResult.Refused>().firstOrNull()?.refusal
				?: ExtractionRefusal.NotASingleRegion
		return ExtractMethodPlan.refused(refusal, fileText, documentVersion)
	}

	return ExtractMethodPlan(
		fileText = fileText,
		documentVersion = documentVersion,
		candidates = candidates,
		refusal = null,
	)
}
