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
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.math.log

private val logger = LoggerFactory.getLogger("KotlinDiagnosticProvider")

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
		analyze(ktFile) {
			ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
				.map { it.toDiagnosticItem() }
		}
	}

	logger.info("Found {} diagnostics", diagnostics.size)

	return DiagnosticResult(
		file = file,
		diagnostics = diagnostics
	)
}

private fun KaDiagnosticWithPsi<*>.toDiagnosticItem(): DiagnosticItem {
	val range = psi.textRange.toRange(psi.containingFile)
	val severity = severity.toDiagnosticSeverity()
	return DiagnosticItem(
		message = defaultMessage,
		code = "",
		range = range,
		source = "Kotlin",
		severity = severity,
	)
}

private fun KaSeverity.toDiagnosticSeverity(): DiagnosticSeverity {
	return when (this) {
		KaSeverity.ERROR -> DiagnosticSeverity.ERROR
		KaSeverity.WARNING -> DiagnosticSeverity.WARNING
		KaSeverity.INFO -> DiagnosticSeverity.INFO
	}
}

