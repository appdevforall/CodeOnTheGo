package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("ExtractMethodPlanner")

/**
 * Computes the whole [ExtractMethodPlan] in one background analysis pass.
 *
 * Anything thrown in this pipeline degrades to a refusal plus a log line: the action framework
 * catches only `IllegalArgumentException` and this runs on a scope with no exception handler, so an
 * uncaught throw would crash the app (R16). Cancellation is the exception -- it is re-thrown, since a
 * cancelled action has no result to report and the coroutine machinery already handles it.
 *
 * Everything that is not "your selection is not one region" refuses with [ExtractionRefusal.CouldNotAnalyse]:
 * blaming a selection that may have been fine is worse than saying nothing useful.
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
		env.ktSymbolIndex.withLiveKtFile(nioPath) { live ->
			live.read { ktFile ->
				val fileText = ktFile.text
				val region =
					resolveExtractionRegion(ktFile, selectionStart, selectionEnd)
						?: return@read ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion, fileText, documentVersion)

				live.analyzing(AnalysisPriority.INTERACTIVE, cancelChecker) {
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
						// A region with no reason at all cannot happen; if it does, saying nothing useful beats
						// blaming the selection.
						val refusal =
							results.filterIsInstance<SignatureResult.Refused>().firstOrNull()?.refusal
								?: ExtractionRefusal.CouldNotAnalyse
						return@analyzing ExtractMethodPlan.refused(refusal, fileText, documentVersion)
					}

					ExtractMethodPlan(
						fileText = fileText,
						documentVersion = documentVersion,
						candidates = candidates,
						refusal = null,
					)
				}
			}
		} ?: ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse)
	}.getOrElse { error ->
		if (error is CancellationException) throw error
		logger.warn("Failed to build extract-method plan for {}", nioPath, error)
		ExtractMethodPlan.refused(ExtractionRefusal.CouldNotAnalyse)
	}
