package com.itsaky.androidide.lsp.kotlin.diagnostic

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.toKotlinInstant

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
	val managed = fileManager.getOpenFile(file)
	if (managed == null) {
		logger.warn("Attempt to analyze non-open file: {}", file)
		return DiagnosticResult.NO_UPDATE
	}

	val analyzedAt = managed.analyzeTimestamp
	val modifiedAt = FileManager.getLastModified(file)
	if (analyzedAt > modifiedAt.toKotlinInstant()) {
		logger.debug("Skipping analysis. File unmodified.")
		return DiagnosticResult.NO_UPDATE
	}

	val rawDiagnostics = managed.analyze { ktFile ->
		ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
	}

	logger.info("Found {} diagnostics", rawDiagnostics.size)

	return DiagnosticResult(
		file = file, diagnostics = rawDiagnostics.map { rawDiagnostic ->
			rawDiagnostic.toDiagnosticItem()
		}).also {
		managed.analyzeTimestamp = Clock.System.now()
	}
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

