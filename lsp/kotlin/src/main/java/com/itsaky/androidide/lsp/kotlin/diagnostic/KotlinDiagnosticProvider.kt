package com.itsaky.androidide.lsp.kotlin.diagnostic

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.progress.ICancelChecker
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("KotlinDiagnosticProvider")

internal data class KotlinDiagnosticExtra<out ActionT : DiagnosticAction>(
	val compilationEnv: CompilationEnvironment,
	val action: ActionT,
)

@Suppress("UNCHECKED_CAST")
internal inline fun <reified T : DiagnosticAction> KotlinDiagnosticExtra<*>?.asAction(): KotlinDiagnosticExtra<T>? =
	if (this?.action is T) this as KotlinDiagnosticExtra<T> else null

internal sealed interface DiagnosticAction {
	data object None : DiagnosticAction

	data class ResolveReference(
		val referenceName: String,
	) : DiagnosticAction

	data object NullSafetyFix : DiagnosticAction
}

context(env: CompilationEnvironment)
internal fun collectDiagnosticsFor(
	file: Path,
	cancelChecker: ICancelChecker,
): DiagnosticResult {
	try {
		logger.info("analyzing file: {}", file)
		return doAnalyze(file, cancelChecker)
	} catch (err: Throwable) {
		if (err is CancellationException) {
			logger.debug("analysis cancelled")
			throw err
		}
		logger.error("an error occurred analyzing file: {}", file, err)
		return DiagnosticResult.NO_UPDATE
	}
}

@OptIn(KaExperimentalApi::class)
context(env: CompilationEnvironment)
private fun doAnalyze(
	file: Path,
	cancelChecker: ICancelChecker,
): DiagnosticResult {
	/*
	 * Diagnostics yield to completion but preempt indexing. The wrapped checker turns a scheduler
	 * preemption into an AnalysisPreemptedException, which CompilationEnvironment's fileAnalyzer catches
	 * to re-schedule this run once the higher-priority work finishes.
	 */
	val checker = ScheduledCancelChecker(cancelChecker)

	var superseded = false
	val result =
		env.ktSymbolIndex.withLiveKtFile(file) { live ->
			val diagnostics =
				live.analyzing(AnalysisPriority.DIAGNOSTICS, checker) { ktFile ->
					buildList {
						PsiTreeUtil
							.collectElementsOfType(ktFile, PsiErrorElement::class.java)
							.forEach { errorElement ->
								checker.abortIfCancelled()
								add(
									diagnosticItem(
										file = ktFile,
										message = errorElement.errorDescription,
										range = errorElement.textRange,
										severity = DiagnosticSeverity.ERROR,
									),
								)
							}

						/*
						 * analyzeMaybeDangling installs a CancelCheckerProgressIndicator, so this is cancellable
						 * mid-`analyze`: it aborts at the compiler's internal checkCanceled() once `checker` reports
						 * preemption/cancellation. (Previously this analysis was not cancellable at all.)
						 */
						ktFile
							.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
							.forEach { diagnostic ->
								checker.abortIfCancelled()
								// Extract plain data while still inside the analyze context; never let
								// the KaLifetimeOwner diagnostic escape (see KotlinDiagnosticExtra).
								val action =
									when (diagnostic) {
										is KaFirDiagnostic.UnresolvedReference -> {
											DiagnosticAction.ResolveReference(
												diagnostic.reference,
											)
										}

										is KaFirDiagnostic.UnsafeCall -> {
											DiagnosticAction.NullSafetyFix
										}

										else -> {
											DiagnosticAction.None
										}
									}

								add(
									diagnostic.toDiagnosticItem().apply {
										extra = KotlinDiagnosticExtra(env, action)
									},
								)
							}
					}
				}

			if (live.isStale) {
				// The document moved on while this ran, so these diagnostics describe text the user has
				// already replaced. Publishing them would paint the editor with stale squiggles.
				superseded = true
				null
			} else {
				logger.info("Found {} diagnostics", diagnostics.size)
				DiagnosticResult(file = file, diagnostics = diagnostics)
			}
		}

	if (result != null) {
		return result
	}

	if (superseded) {
		logger.debug("dropping superseded diagnostics for {}", file)
		/*
		 * On the debounced path this is a self-send: doAnalyze runs as fileAnalyzer's own action, so the
		 * send reads to the worker as a newer key and cancels the run it came from. Deliberate - the
		 * reschedule still lands, and the only casualty is the NO_UPDATE publish below, which had nothing
		 * to say anyway. Reached from KotlinLanguageServer.analyze() instead, it is a plain reschedule.
		 */
		env.fileAnalyzer.schedule(file)
	} else {
		logger.warn("File {} is not accessible", file)
	}
	return DiagnosticResult.NO_UPDATE
}

private fun KaDiagnosticWithPsi<*>.toDiagnosticItem(): DiagnosticItem {
	val severity = severity.toDiagnosticSeverity()
	return diagnosticItem(
		file = psi.containingFile,
		message = defaultMessage,
		range = psi.textRange,
		severity = severity,
	)
}

private fun diagnosticItem(
	file: PsiFile,
	message: String,
	range: TextRange,
	severity: DiagnosticSeverity,
) = DiagnosticItem(
	message = message,
	code = "",
	range = range.toRange(file),
	source = "kotlin",
	severity = severity,
)

private fun KaSeverity.toDiagnosticSeverity(): DiagnosticSeverity =
	when (this) {
		KaSeverity.ERROR -> DiagnosticSeverity.ERROR
		KaSeverity.WARNING -> DiagnosticSeverity.WARNING
		KaSeverity.INFO -> DiagnosticSeverity.INFO
	}
