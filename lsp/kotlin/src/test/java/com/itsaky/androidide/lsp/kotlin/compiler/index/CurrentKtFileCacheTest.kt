package com.itsaky.androidide.lsp.kotlin.compiler.index

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The current-file cache, exercised through the pin API that is now the only way to acquire an
 * instance. The pinned file may not leave its scope, so identity is compared inside the block, or
 * through an identity hash captured inside it.
 */
internal class CurrentKtFileCacheTest : KtLspTest() {
	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(docCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun docCloseEvent(path: Path) = DocumentCloseEvent(path)

	/** The [Path] under the first source root that [createSourceFile] wrote [relativePath] to. */
	private fun sourcePath(relativePath: String): Path = env.sourceRoots.first().resolve(relativePath)

	/** Registers [path] as an active document at version 1 with [content]. */
	private fun openDocument(
		path: Path,
		content: String,
	) {
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
	}

	private fun changeDocument(
		path: Path,
		content: String,
		version: Int,
	) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, content, content, version, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
	}

	/** The identity of the instance one pin on [path] resolves to, since the instance itself cannot escape. */
	private fun pinnedIdentity(path: Path): Int? =
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			live.read { System.identityHashCode(it) }
		}

	@Test
	fun `same version returns same instance`() {
		createSourceFile("A.kt", "fun a() {}")
		val path = sourcePath("A.kt")
		openDocument(path, "fun a() {}")

		val first = pinnedIdentity(path)
		val second = pinnedIdentity(path)

		assertNotNull(first)
		assertEquals(first, second)
	}

	@Test
	fun `new version returns new instance reflecting new content`() {
		createSourceFile("B.kt", "fun b() {}")
		val path = sourcePath("B.kt")
		openDocument(path, "fun b() {}")
		val v1 = pinnedIdentity(path)

		changeDocument(path, "fun b() {}\nfun c() {}", 2)
		val v2 =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				live.read { System.identityHashCode(it) to it.text }
			}!!

		assertNotEquals(v1, v2.first)
		assertEquals("fun b() {}\nfun c() {}", v2.second)
	}

	@Test
	fun `repeated requests at the same version reuse one instance`() {
		createSourceFile("D.kt", "fun d() {}")
		val path = sourcePath("D.kt")
		openDocument(path, "fun d() {}")

		val identities = (1..16).map { pinnedIdentity(path) }

		assertNotNull(identities.first())
		identities.forEach { assertEquals(identities.first(), it) }
	}

	@Test
	fun `refreshed file resolves against new content via analysis`() {
		createSourceFile("E.kt", "fun e(): Int = 1")
		val path = sourcePath("E.kt")
		openDocument(path, "fun e(): Int = 1")
		pinnedIdentity(path)

		changeDocument(path, "fun e(): Int = 1\nfun f(): Int = e()", 2)

		// `f` calling `e` must resolve (no UNRESOLVED_REFERENCE). Keep `.defaultMessage` inside the
		// analysis: reading a diagnostic outside its session throws KaInaccessibleLifetimeOwnerAccessException
		// instead of a clean assertion diff.
		val diagnosticMessages =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				live.analyzing(AnalysisPriority.DIAGNOSTICS, noopCancelChecker()) { ktFile ->
					ktFile
						.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
						.map { it.defaultMessage }
				}
			}
		assertEquals(emptyList<String>(), diagnosticMessages)
	}

	@Test
	fun `invalidateCurrent then a new pin reparses`() {
		createSourceFile("G.kt", "fun g() {}")
		val path = sourcePath("G.kt")
		openDocument(path, "fun g() {}")
		val first = pinnedIdentity(path)

		env.ktSymbolIndex.invalidateCurrent(path)
		val second = pinnedIdentity(path)

		assertNotNull(first)
		assertNotEquals(first, second)
	}

	@OptIn(UnpinnedKtFileAccess::class)
	@Test
	fun `peekLiveKtFile returns the same instance after a completed refresh`() {
		createSourceFile("H.kt", "fun h() {}")
		val path = sourcePath("H.kt")
		openDocument(path, "fun h() {}")
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }

		val peeked = env.ktSymbolIndex.peekLiveKtFile(path)

		assertNotNull(peeked)
		val samePinnedInstance = env.ktSymbolIndex.withLiveKtFile(path) { live -> live.read { it === peeked } }
		assertTrue(samePinnedInstance!!)
	}

	@OptIn(UnpinnedKtFileAccess::class)
	@Test
	fun `getKtFile returns the current cached instance for an active document instead of reloading from disk`() {
		createSourceFile("I.kt", "fun i() {}")
		val path = sourcePath("I.kt")
		openDocument(path, "fun i() {}")
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		val current = env.ktSymbolIndex.peekLiveKtFile(path)

		val viaGetKtFile = env.ktSymbolIndex.getKtFile(path)

		assertNotNull(current)
		assertSame(current, viaGetKtFile)
	}

	@OptIn(UnpinnedKtFileAccess::class)
	@Test
	fun `peekLiveKtFile returns null for an active document whose refresh has not been triggered`() {
		createSourceFile("J.kt", "fun j() {}")
		val path = sourcePath("J.kt")
		openDocument(path, "fun j() {}")
		// Nothing acquires or refreshes this path, so no refresh has been launched for it.

		val peeked = env.ktSymbolIndex.peekLiveKtFile(path)

		assertNull(peeked)
	}
}
