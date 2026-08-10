package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("ExtractMethodPlanner")

/**
 * Computes the whole [ExtractMethodPlan] in one background analysis pass.
 *
 * The current `KtFile` is fetched *before* entering [read] -- blocking on `getCurrentKtFile(...).get()`
 * inside `project.read` deadlocks.
 *
 * Anything thrown in this pipeline degrades to a refusal plus a log line: the action framework
 * catches only `IllegalArgumentException` and this runs on a scope with no exception handler, so an
 * uncaught throw would crash the app (R16).
 */
internal fun buildExtractMethodPlan(
	env: AbstractCompilationEnvironment,
	nioPath: Path,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int,
	cancelChecker: ScheduledCancelChecker,
): ExtractMethodPlan =
	runCatching {
		val ktFile =
			env.ktSymbolIndex.getCurrentKtFile(nioPath).get()
				?: return ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion)

		env.project.read {
			val fileText = ktFile.text
			val region =
				resolveExtractionRegion(ktFile, selectionStart, selectionEnd)
					?: return@read ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion, fileText, documentVersion)

			analyzeMaybeDangling(ktFile, AnalysisPriority.INTERACTIVE, cancelChecker) {
				val results =
					when (region) {
						is ExtractionRegion.Expressions -> {
							region.candidates.map { buildCandidate(listOf(it), isExpression = true, fileText = fileText) }
						}

						is ExtractionRegion.Statements -> {
							listOf(buildCandidate(region.statements, isExpression = false, fileText = fileText))
						}
					}

				val candidates = results.filterIsInstance<SignatureResult.Success>().map { it.candidate }
				if (candidates.isEmpty()) {
					// The innermost region is the one the user pointed at, so its reason is the one to show.
					val refusal =
						results.filterIsInstance<SignatureResult.Refused>().firstOrNull()?.refusal
							?: ExtractionRefusal.NotASingleRegion
					return@analyzeMaybeDangling ExtractMethodPlan.refused(refusal, fileText, documentVersion)
				}

				ExtractMethodPlan(
					fileText = fileText,
					documentVersion = documentVersion,
					candidates = candidates,
					// Only meaningful while the innermost candidate survived: otherwise the selection no
					// longer corresponds to the first option shown.
					selectionMatchedCandidate =
						region is ExtractionRegion.Expressions &&
							region.selectionMatchedInnermost &&
							candidates.first().span == region.span,
					refusal = null,
				)
			}
		}
	}.getOrElse { error ->
		logger.warn("Failed to build extract-method plan for {}", nioPath, error)
		ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion)
	}
