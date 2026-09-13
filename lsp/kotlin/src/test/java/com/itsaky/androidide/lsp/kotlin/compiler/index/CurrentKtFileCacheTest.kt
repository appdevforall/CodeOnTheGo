package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The current-file cache, exercised through the pin API that is now the only way to acquire an
 * instance. The pinned file may not leave its scope, so every identity comparison happens inside a
 * `read` block, against a reference obtained from the one door that hands one out.
 */
internal class CurrentKtFileCacheTest : KtLspTest() {
	companion object {
		private const val CONCURRENT_REQUESTS = 16
	}

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

	/**
	 * The instance the current-file cache holds for [path], forcing a refresh first.
	 *
	 * [KtSymbolIndex.peekLiveKtFile] is the one door that hands out a reference, which is what lets the
	 * assertions below be real identity comparisons rather than identity-hash comparisons.
	 */
	@OptIn(UnpinnedKtFileAccess::class)
	private fun currentInstance(path: Path): KtFile? {
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
		return env.ktSymbolIndex.peekLiveKtFile(path)
	}

	/** Whether one pin on [path] resolves to [expected], compared inside the block since the pinned file cannot escape. */
	private fun pinResolvesTo(
		path: Path,
		expected: KtFile?,
	): Boolean? =
		env.ktSymbolIndex.withLiveKtFile(path) { live ->
			live.read { it === expected }
		}

	@Test
	fun `same version returns same instance`() {
		createSourceFile("A.kt", "fun a() {}")
		val path = sourcePath("A.kt")
		openDocument(path, "fun a() {}")
		val instance = currentInstance(path)

		val first = pinResolvesTo(path, instance)
		val second = pinResolvesTo(path, instance)

		assertNotNull(instance)
		assertTrue(first!!)
		assertTrue(second!!)
	}

	@Test
	fun `new version returns new instance reflecting new content`() {
		createSourceFile("B.kt", "fun b() {}")
		val path = sourcePath("B.kt")
		openDocument(path, "fun b() {}")
		val v1 = currentInstance(path)

		changeDocument(path, "fun b() {}\nfun c() {}", 2)
		val v2 =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				live.read { (it !== v1) to it.text }
			}!!

		assertNotNull(v1)
		assertTrue(v2.first)
		assertEquals("fun b() {}\nfun c() {}", v2.second)
	}

	/**
	 * Requests that overlap the very first parse must share it.
	 *
	 * The parse runs on the index's own executor, so requests issued before it completes hit an
	 * *incomplete* cache entry - the window a per-version single-flight exists for. Genuinely
	 * concurrent, because the only remaining acquisition door blocks until its instance is resolved:
	 * issuing the requests sequentially would only ever see a settled entry.
	 *
	 * Identity is captured into an identity set from inside each block. The references outlive their
	 * scopes, which is not safe for analysis, but counting distinct instances is all that happens to
	 * them and it is the only exact way to compare instances acquired on different threads.
	 */
	@OptIn(UnpinnedKtFileAccess::class)
	@Test
	fun `concurrent requests at same version parse once`() {
		createSourceFile("D.kt", "fun d() {}")
		val path = sourcePath("D.kt")
		openDocument(path, "fun d() {}")

		val seen = Collections.newSetFromMap(IdentityHashMap<KtFile, Boolean>())
		val acquired =
			runBlocking {
				(1..CONCURRENT_REQUESTS)
					.map {
						async(Dispatchers.Default) {
							env.ktSymbolIndex.withLiveKtFileAsync(path) { live ->
								live.read { synchronized(seen) { seen.add(it) } }
							}
						}
					}.awaitAll()
			}

		assertEquals(CONCURRENT_REQUESTS, acquired.count { it != null })
		assertEquals(1, seen.size)
		// The instance every request resolved to is also the one the cache settled on: a second parse
		// would leave the cache holding an instance no pin ever saw.
		assertTrue(seen.contains(env.ktSymbolIndex.peekLiveKtFile(path)))
	}

	@Test
	fun `refreshed file resolves against new content via analysis`() {
		createSourceFile("E.kt", "fun e(): Int = 1")
		val path = sourcePath("E.kt")
		openDocument(path, "fun e(): Int = 1")
		currentInstance(path)

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
		val first = currentInstance(path)

		env.ktSymbolIndex.invalidateCurrent(path)
		val reparsed =
			env.ktSymbolIndex.withLiveKtFile(path) { live ->
				live.read { it !== first }
			}!!

		assertNotNull(first)
		assertTrue(reparsed)
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

	@OptIn(UnpinnedKtFileAccess::class, ResolutionSideKtFileAccess::class)
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
