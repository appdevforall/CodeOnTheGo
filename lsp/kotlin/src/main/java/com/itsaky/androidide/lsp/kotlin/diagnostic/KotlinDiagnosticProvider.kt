package com.itsaky.androidide.lsp.kotlin.diagnostic

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationKind
import com.itsaky.androidide.lsp.kotlin.compiler.Compiler
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.io.path.pathString
import org.jetbrains.kotlin.analysis.api.analyze as ktAnalyze

class KotlinDiagnosticProvider(
	private val compiler: Compiler,
	private val scope: CoroutineScope
) : AutoCloseable {

	companion object {
		private val logger = LoggerFactory.getLogger(KotlinDiagnosticProvider::class.java)
	}

	private val analyzeTimestamps = ConcurrentHashMap<Path, Instant>()

	fun analyze(file: Path): DiagnosticResult =
		try {
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
	private fun doAnalyze(file: Path): DiagnosticResult {
		val modifiedAt = FileManager.getLastModified(file)
		val analyzedAt = analyzeTimestamps[file]
		if (analyzedAt?.isAfter(modifiedAt) == true) {
			logger.debug("Skipping analysis. File unmodified.")
			return DiagnosticResult.NO_UPDATE
		}

		logger.info("fetch document contents")
		val fileContents = FileManager.getDocumentContents(file)
			.replace("\r", "")

		val env = compiler.compilationEnvironmentFor(CompilationKind.Default)
		val virtualFile = compiler.fileSystem.refreshAndFindFileByPath(file.pathString)
		if (virtualFile == null) {
			logger.warn("Unable to find virtual file for path: {}", file.pathString)
			return DiagnosticResult.NO_UPDATE
		}

		val ktFile = env.psiManager.findFile(virtualFile)
		if (ktFile == null) {
			logger.warn("Unable to find KtFile for path: {}", file.pathString)
			return DiagnosticResult.NO_UPDATE
		}

		if (ktFile !is KtFile) {
			logger.warn(
				"Expected KtFile, but found {} for path:{}",
				ktFile.javaClass,
				file.pathString
			)
			return DiagnosticResult.NO_UPDATE
		}

		val inMemoryPsi = compiler.defaultKotlinParser
			.createFile(file.name, fileContents)
		inMemoryPsi.originalFile = ktFile

		val rawDiagnostics = analyzeCopy(
			useSiteElement = inMemoryPsi,
			resolutionMode = KaDanglingFileResolutionMode.PREFER_SELF,
		) {
			logger.info("ktFile.text={}", inMemoryPsi.text)
			inMemoryPsi.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
		}

		logger.info("Found {} diagnostics", rawDiagnostics.size)

		return DiagnosticResult(
			file = file,
			diagnostics = rawDiagnostics.map { rawDiagnostic ->
				rawDiagnostic.toDiagnosticItem()
			}
		).also {
			analyzeTimestamps[file] = Instant.now()
		}
	}

	internal fun clearTimestamp(file: Path) {
		analyzeTimestamps.remove(file)
	}

	override fun close() {
		scope.cancelIfActive("diagnostic provider is being destroyed")
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

private fun TextRange.toRange(containingFile: PsiFile): Range {
	val doc = PsiDocumentManager.getInstance(containingFile.project)
		.getDocument(containingFile) ?: return Range.NONE
	val startLine = doc.getLineNumber(startOffset)
	val startCol = startOffset - doc.getLineStartOffset(startLine)
	val endLine = doc.getLineNumber(endOffset)
	val endCol = endOffset - doc.getLineStartOffset(endLine)
	return Range(
		start = Position(
			line = startLine,
			column = startCol,
			index = startOffset,
		),
		end = Position(
			line = endLine,
			column = endCol,
			index = endOffset,
		)
	)
}
