package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.psi.KtFile
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A Kotlin file deleted from disk must leave the index: its metadata row, its cached disk copy, and
 * every resolution that went through it.
 */
internal class KotlinFileRemovalTest : KtLspTest() {
	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private val declaringContent = "package p\n\nfun foo() = 1\n"

	private class Sources(
		val declaring: Path,
		val calling: KtFile,
	)

	/** A file declaring `foo`, and a caller whose analysis loads the declaring file's disk copy. */
	private fun createSources(): Sources {
		createSourceFile("p/A.kt", declaringContent)
		val calling = createSourceFile("p/B.kt", "package p\n\nfun bar() = foo()\n")
		return Sources(env.sourceRoots.first().resolve("p/A.kt"), calling)
	}

	private fun diagnosticNames(ktFile: KtFile): List<String> =
		analyze(ktFile) {
			ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).map { it.factoryName }
		}

	/** Whether the index worker drops the metadata row for [path] within the timeout. */
	private fun rowRemoved(path: Path): Boolean =
		runBlocking {
			withTimeoutOrNull(10.seconds) {
				while (env.ktSymbolIndex.fileIndex.contains(path.pathString)) {
					delay(20.milliseconds)
				}
			} != null
		}

	/*
	 * The unit-test application grants platform write access only inside runWriteAction, and the
	 * modification events onFileRemoved publishes assert it. Production's MockApplication always grants it.
	 */
	private fun removeInWriteAction(path: Path) {
		ApplicationManager.getApplication().runWriteAction { runBlocking { env.onFileRemoved(path) } }
	}

	@Test
	fun deletedFileLosesItsRowAndDiskCopy() {
		val sources = createSources()
		assertThat(diagnosticNames(sources.calling)).doesNotContain("UNRESOLVED_REFERENCE")
		assertThat(env.ktSymbolIndex.isDiskCopyCached(sources.declaring)).isTrue()

		Files.delete(sources.declaring)
		env.ktSymbolIndex.syncIndexInBackground()
		removeInWriteAction(sources.declaring)
		val rowRemoved = rowRemoved(sources.declaring)

		assertThat(rowRemoved).isTrue()
		assertThat(env.ktSymbolIndex.isDiskCopyCached(sources.declaring)).isFalse()
		assertThat(diagnosticNames(sources.calling)).contains("UNRESOLVED_REFERENCE")
	}

	/*
	 * The removal handler used to run only for paths that still existed, so a gone path reaching it is
	 * new: the VirtualFile may be stale or missing. The index worker is not started, so the deleted
	 * file's row stays and nothing but the invalidation can drop the caller's earlier resolution.
	 */
	@Test
	fun removalOfAGoneFileInvalidatesTheModule() {
		val sources = createSources()
		assertThat(diagnosticNames(sources.calling)).doesNotContain("UNRESOLVED_REFERENCE")

		Files.delete(sources.declaring)
		val failure = runCatching { removeInWriteAction(sources.declaring) }.exceptionOrNull()

		assertThat(failure).isNull()
		assertThat(diagnosticNames(sources.calling)).contains("UNRESOLVED_REFERENCE")
	}

	@Test
	fun removalOfAnOpenFileClearsItsRow() {
		val sources = createSources()
		FileManager.onDocumentOpen(DocumentOpenEvent(sources.declaring, declaringContent, 1))
		openedPaths.add(sources.declaring)
		assertThat(diagnosticNames(sources.calling)).doesNotContain("UNRESOLVED_REFERENCE")

		Files.delete(sources.declaring)
		env.ktSymbolIndex.syncIndexInBackground()
		removeInWriteAction(sources.declaring)

		assertThat(rowRemoved(sources.declaring)).isTrue()
	}
}
