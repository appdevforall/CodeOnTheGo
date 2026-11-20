package com.itsaky.androidide.lsp.kotlin

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.KotlinModuleLayout
import com.itsaky.androidide.lsp.kotlin.completion.doComplete
import com.itsaky.androidide.lsp.kotlin.diagnostic.DiagnosticsOutcome
import com.itsaky.androidide.lsp.kotlin.diagnostic.collectDiagnosticsFor
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.variantLayout
import com.itsaky.androidide.lsp.kotlin.navigation.findDefinitionAt
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `VariantConfig` is declared in both `src/debug` and `src/release`, each with its own property.
 * With `debug` selected, Kotlin analysis must see the debug declaration and never the release one.
 */
class VariantSourceSetsTest : KtLspTest() {
	// Completion runs on the opened document's live KtFile, which must be physical as in production.
	override val enableParserEventSystem = true

	private lateinit var use: Path
	private lateinit var completion: Path
	private lateinit var releaseConfig: Path

	override fun layout(baseDir: Path): KotlinModuleLayout {
		use = write(baseDir, "src/main/kotlin/p/Use.kt", USE)
		completion = write(baseDir, "src/main/kotlin/p/Complete.kt", "package p\n\nfun c() = VariantConfig.\n")
		write(baseDir, "src/debug/kotlin/p/VariantConfig.kt", "package p\n\nobject VariantConfig {\n\tval debugOnly = 1\n}\n")
		releaseConfig = write(baseDir, "src/release/kotlin/p/VariantConfig.kt", RELEASE_CONFIG)
		return variantLayout(baseDir, listOf("debug", "release"), selected = "debug")
	}

	@After
	fun tearDown() {
		FileManager.onDocumentClose(DocumentCloseEvent(completion))
		ProjectManagerImpl.getInstance().workspace = null
	}

	@Test
	fun `diagnostics resolve the selected variant's sources and flag another variant's`() {
		val outcome = context(env) { collectDiagnosticsFor(use, ICancelChecker.NOOP) }

		val errors = (outcome as DiagnosticsOutcome.Collected).result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }

		// Line 4 reads releaseOnly; line 2 reads debugOnly and must resolve.
		assertThat(errors.map { it.range.start.line }.distinct()).containsExactly(4)
	}

	@Test
	fun `definition goes to the selected variant's declaration`() {
		val offset = use.readText().indexOf("VariantConfig.debugOnly") + 2

		val files =
			runBlocking { context(env) { findDefinitionAt(DefinitionParams(use, Position(0, 0, offset), ICancelChecker.NOOP)) } }
				.locations
				.map { it.file.toString() }

		assertThat(files).isNotEmpty()
		assertThat(files.filterNot { "/src/debug/kotlin/" in it }).isEmpty()
	}

	@Test
	fun `completion offers the selected variant's members and not another variant's`() {
		val text = completion.readText()
		FileManager.onDocumentOpen(DocumentOpenEvent(completion, text, 1))
		val offset = text.indexOf("VariantConfig.") + "VariantConfig.".length

		val labels =
			context(env) { doComplete(CompletionParams(Position(0, 0, offset), completion, ICancelChecker.NOOP).apply { prefix = "" }) }
				.items
				.map { it.ideLabel.toString() }

		assertThat(labels).contains("debugOnly")
		assertThat(labels).doesNotContain("releaseOnly")
	}

	@Test
	fun `a source of a variant that is not selected gets no diagnostics`() {
		val server = KotlinLanguageServer()
		try {
			val result = runBlocking { server.analyze(releaseConfig) }

			// Cleared for this file, not merely "no update": an earlier variant's errors must go away.
			assertThat(result.file).isEqualTo(releaseConfig)
			assertThat(result.diagnostics).isEmpty()
		} finally {
			server.shutdown()
		}
	}

	private companion object {
		const val RELEASE_CONFIG = "package p\n\nobject VariantConfig {\n\tval releaseOnly = 2\n}\n"
		const val USE = "package p\n\nfun d() = VariantConfig.debugOnly\n\nfun r() = VariantConfig.releaseOnly\n"
	}

	private fun write(
		baseDir: Path,
		relativePath: String,
		content: String,
	): Path = baseDir.resolve(relativePath).also { it.createParentDirectories().writeText(content) }
}
