package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A pinned path resolves to one `KtFile` instance for the whole scope, whichever door asks.
 *
 * The pinned instance is deliberately never carried out of a `read` block - the scope guard rejects
 * that - so these tests compare identity inside the block, or through an identity hash captured
 * inside it.
 */
internal class LiveKtFilePinTest : KtLspTest() {
	companion object {
		private const val REFRESH_TIMEOUT_SECONDS = 10L
		private const val POLL_INTERVAL_MILLIS = 20L
	}

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

	private fun bumpVersion(
		path: Path,
		version: Int,
	) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, version, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
	}

	@Test
	fun `a version bump inside a pin does not install a second instance`() {
		val path = openDocument()

		val doorsAgree =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				bumpVersion(path, 2)
				/*
				 * In production this second request is any other getCurrentKtFile caller - the refresh
				 * scheduler, completion, a code action - running while the pinned analysis is still going;
				 * unpinned it installs a superseding instance for the same path. getKtFile is the
				 * resolution-side door DeclarationProvider takes. Both must answer with the pinned instance.
				 */
				val superseding = env.ktSymbolIndex.getCurrentKtFile(path).get()
				live.read { it === superseding && it === env.ktSymbolIndex.getKtFile(path) }
			}!!

		assertThat(doorsAgree).isTrue()
	}

	@Test
	fun `the resolution door keeps the pinned instance after the document is closed`() {
		val path = openDocument()

		val doorAgrees =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				/*
				 * Closing a tab mid-analysis is CompilationEnvironment.onFileClosed, which drops the
				 * current-file cache for the path. Unpinned, the resolution door then falls through to a
				 * freshly loaded disk instance while the analysis is still holding the live one.
				 */
				FileManager.onDocumentClose(DocumentCloseEvent(path))
				openedPaths.remove(path)
				env.ktSymbolIndex.invalidateCurrent(path)
				live.read { it === env.ktSymbolIndex.getKtFile(path) }
			}!!

		assertThat(doorAgrees).isTrue()
	}

	@Test
	fun `isStale reports a version bump that happened during the pin`() {
		val path = openDocument()

		val staleness =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				val before = live.isStale
				bumpVersion(path, 2)
				before to live.isStale
			}!!

		assertThat(staleness).isEqualTo(false to true)
	}

	@Test
	fun `a nested pin on the same path reuses the outer instance`() {
		val path = openDocument()

		val instances =
			env.ktSymbolIndex.withLiveKtFile(path) { outer ->
				val innerId =
					env.ktSymbolIndex.withLiveKtFile(path) { inner ->
						inner.read { System.identityHashCode(it) }
					}!!
				outer.read { System.identityHashCode(it) } to innerId
			}!!

		assertThat(instances.second).isEqualTo(instances.first)
	}

	@Test
	fun `a refresh owed during a pin is applied after release`() {
		val path = openDocument()

		val pinnedId =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				bumpVersion(path, 2)
				// Answered from the pin, which leaves the refresh for the new version owed.
				env.ktSymbolIndex.getCurrentKtFile(path).get()
				val id = live.read { System.identityHashCode(it) }
				val cached = env.ktSymbolIndex.getCurrentKtFileIfPresent(path)
				assertThat(System.identityHashCode(cached)).isEqualTo(id)
				id
			}!!

		// Nothing below asks for the current file, so only the release's own deferred refresh can
		// replace the cached instance.
		assertThat(awaitInstanceChange(path, pinnedId)).isTrue()
	}

	private fun awaitInstanceChange(
		path: Path,
		staleId: Int,
	): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REFRESH_TIMEOUT_SECONDS)
		while (System.nanoTime() < deadline) {
			val current = env.ktSymbolIndex.getCurrentKtFileIfPresent(path)
			if (current != null && System.identityHashCode(current) != staleId) return true
			Thread.sleep(POLL_INTERVAL_MILLIS)
		}
		return false
	}

	@Test
	fun `the pinned file must not escape its scope`() {
		val path = openDocument()

		val failure =
			runCatching {
				env.ktSymbolIndex.withLiveKtFile(path) { live -> live.read { it } }
			}.exceptionOrNull()

		assertThat(failure).isInstanceOf(IllegalStateException::class.java)
	}

	@Test
	fun `a pin on a path with no open document still yields the disk instance`() {
		createSourceFile("Closed.kt", content)
		val path = env.sourceRoots.first().resolve("Closed.kt")

		val file = env.ktSymbolIndex.withLiveKtFile(path) { live -> live.read { it.name } }

		assertThat(file).isEqualTo("Closed.kt")
	}
}
