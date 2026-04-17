package com.itsaky.androidide.lsp.kotlin.diagnostic

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("KotlinDiagnosticProvider")

internal data class KotlinDiagnosticExtra(
	val diagnostic: KaDiagnosticWithPsi<*>,
	val compilationEnv: CompilationEnvironment,
)

internal fun CompilationEnvironment.collectDiagnosticsFor(file: Path): DiagnosticResult = try {
	logger.info("Analyzing file: {}", file)
	return doAnalyze(file)
} catch (err: Throwable) {
	if (err is CancellationException) {
		logger.debug("analysis cancelled")
		throw err
	}
	logger.error("An error occurred analyzing file: {}", file, err)
	return DiagnosticResult.NO_UPDATE
}

@OptIn(KaExperimentalApi::class)
private fun CompilationEnvironment.doAnalyze(file: Path): DiagnosticResult {
	var ktFile = ktSymbolIndex.getOpenedKtFile(file)
	if (ktFile == null) {
		onFileOpen(file)
		ktFile = ktSymbolIndex.getOpenedKtFile(file)
	}

	if (ktFile == null) {
		logger.warn("File {} is not accessible", file)
		return DiagnosticResult.NO_UPDATE
	}

	val diagnostics = project.read {
		buildList {
			PsiTreeUtil.collectElementsOfType(ktFile, PsiErrorElement::class.java)
				.forEach { errorElement ->
					add(
						diagnosticItem(
							file = ktFile,
							message = errorElement.errorDescription,
							range = errorElement.textRange,
							severity = DiagnosticSeverity.ERROR,
						)
					)
				}

			analyze(ktFile) {
				ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
					.forEach { diagnostic ->
						add(diagnostic.toDiagnosticItem().apply {
							extra = KotlinDiagnosticExtra(diagnostic, this@doAnalyze)
						})
					}
			}
		}
	}

	logger.info("Found {} diagnostics", diagnostics.size)

	return DiagnosticResult(
		file = file,
		diagnostics = diagnostics
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

private fun KaSeverity.toDiagnosticSeverity(): DiagnosticSeverity {
	return when (this) {
		KaSeverity.ERROR -> DiagnosticSeverity.ERROR
		KaSeverity.WARNING -> DiagnosticSeverity.WARNING
		KaSeverity.INFO -> DiagnosticSeverity.INFO
	}
}

