package com.itsaky.androidide.lsp.kotlin.diagnostic

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
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

internal data class KotlinDiagnosticExtra(
	val compilationEnv: CompilationEnvironment,
	val action: DiagnosticAction,
)

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
	val ktFile = env.ktSymbolIndex.getCurrentKtFile(file).get()
	if (ktFile == null) {
		logger.warn("File {} is not accessible", file)
		return DiagnosticResult.NO_UPDATE
	}

	val diagnostics =
		env.project.read {
			buildList {
				PsiTreeUtil
					.collectElementsOfType(ktFile, PsiErrorElement::class.java)
					.forEach { errorElement ->
						cancelChecker.abortIfCancelled()
						add(
							diagnosticItem(
								file = ktFile,
								message = errorElement.errorDescription,
								range = errorElement.textRange,
								severity = DiagnosticSeverity.ERROR,
							),
						)
					}

				// This should be canceled as well
				// The analysis API uses a no-op implementation of
				// Intellij's ProgressManager for cancellations, so the following
				// isn't really cancellable at the moment
				analyzeMaybeDangling(ktFile) {
					ktFile
						.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
						.forEach { diagnostic ->
							cancelChecker.abortIfCancelled()
							// Extract plain data while still inside the analyze context; never let
							// the KaLifetimeOwner diagnostic escape (see KotlinDiagnosticExtra).
							val action =
								when (diagnostic) {
									is KaFirDiagnostic.UnresolvedReference -> DiagnosticAction.ResolveReference(diagnostic.reference)
									is KaFirDiagnostic.UnsafeCall -> DiagnosticAction.NullSafetyFix
									else -> DiagnosticAction.None
								}

							logger.info("diagnostic={} action={}", diagnostic.javaClass, action.javaClass)
							add(
								diagnostic.toDiagnosticItem().apply {
									extra = KotlinDiagnosticExtra(env, action)
								},
							)
						}
				}
			}
		}

	logger.info("Found {} diagnostics", diagnostics.size)

	return DiagnosticResult(
		file = file,
		diagnostics = diagnostics,
	)
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
