package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * A `KtFile` instance for an open path must not be reported as a redeclaration of itself once a
 * newer instance for the same path has been registered.
 *
 * `KtSymbolIndex.currentFiles` mints a fresh instance per observed document version, and
 * `DeclarationProvider.ktFilesForPackage` resolves the path to whatever the newest one is. An
 * analysis that started against an older instance therefore sees every declaration in the file
 * twice - once as its own PSI, once through the provider - and reports the whole file as
 * conflicting. That is what reaches the editor as red squiggles over every declaration.
 *
 * Pinning the path for the duration of the analysis is what closes that: while a scope is open, no
 * second instance can be installed, so both doors answer with the same PSI.
 */
internal class StaleKtFileInstanceDiagnosticsTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private val content =
		"""
		package p

		class Widget

		fun render(a: Int, b: Int): Int = extracted(b, a) + a

		private fun extracted(b: Int, a: Int): Int = b * a
		""".trimIndent()

	private fun openDocument(): Path {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
		return path
	}

	/**
	 * Moves the document to [version] and lets a competing request observe it.
	 *
	 * The bump alone only updates [FileManager]: a second `KtFile` for the path is installed by the
	 * index's own current-file refresh, so without that request there is nothing for the pin to hold
	 * back and both tests below would pass unpinned.
	 */
	private fun bumpVersionAndRefresh(
		path: Path,
		version: Int,
	) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, version, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
	}

	@Test
	fun `a version bump inside a pin cannot install a second instance`() {
		val path = openDocument()

		val sameInstance =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				// Outside `read`: unpinned, the competing refresh needs project.write, which cannot be
				// granted while this thread holds the read lock.
				bumpVersionAndRefresh(path, 2)
				// getKtFile is the door DeclarationProvider takes; unpinned it would answer with the
				// instance the competing refresh installs, which is what makes the file conflict with itself.
				live.read { pinned -> env.ktSymbolIndex.getKtFile(path) === pinned }
			}!!

		assertThat(sameInstance).isTrue()
	}

	@Test
	fun `diagnostics stay clean across a version bump during analysis`() {
		val path = openDocument()

		val diagnostics =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				bumpVersionAndRefresh(path, 2)
				live.analyzing(AnalysisPriority.DIAGNOSTICS, noopCancelChecker()) { ktFile ->
					ktFile
						.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
						.map { "${it.factoryName}: ${it.defaultMessage}" }
				}
			}!!

		assertThat(diagnostics).isEmpty()
	}
}
