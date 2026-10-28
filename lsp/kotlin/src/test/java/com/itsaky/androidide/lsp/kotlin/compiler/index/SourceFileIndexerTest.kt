package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.progress.ICancelChecker
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

class SourceFileIndexerTest : KtLspTest() {
	private fun buildSymbolIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun visibleSourceIds(): Collection<String>? = null
		}
	}

	private fun buildFileIndex() = KtFileMetadataIndex(InMemoryIndex(KtFileMetadataDescriptor))

	@Test
	fun `indexer ignores local property inside function body`(): Unit =
		runBlocking {
			val file =
				createSourceFile(
					"PollingWorker.kt",
					"""
					fun doWork() {
						for (orderId in listOf("1", "2")) {
							try {
								val response = fetchStatus(orderId)
								if (response != null) {
									val otpCode = response.otpCode ?: response.data?.otpCode
									val status = response.data?.status ?: response.localStatus
									if (otpCode != null) println(otpCode)
								}
							} catch (_: Exception) {}
						}
					}
					fun fetchStatus(id: String): Any? = null
					""".trimIndent(),
				)

			val symbolsIndex = buildSymbolIndex()

			indexSourceFile(env.project, file, mockk(relaxed = true), symbolsIndex, ICancelChecker.NOOP)

			val names = symbolsIndex.findByPrefix("").map { it.shortName }.toSet()
			assertThat(names).containsExactly("doWork", "fetchStatus")
		}

	@Test
	fun `indexer ignores local function inside function body`(): Unit =
		runBlocking {
			val file =
				createSourceFile(
					"Helpers.kt",
					"""
					fun outer(): Int {
						fun inner() = 42
						return inner()
					}
					""".trimIndent(),
				)

			val symbolsIndex = buildSymbolIndex()

			indexSourceFile(env.project, file, mockk(relaxed = true), symbolsIndex, ICancelChecker.NOOP)

			val names = symbolsIndex.findByPrefix("").map { it.shortName }.toSet()
			assertThat(names).containsExactly("outer")
		}

	@Test
	fun `top-level property and function are both indexed`(): Unit =
		runBlocking {
			val file =
				createSourceFile(
					"TopLevel.kt",
					"""
					val globalConfig: String = "default"
					fun compute(): Int = 0
					""".trimIndent(),
				)

			val symbolsIndex = buildSymbolIndex()

			indexSourceFile(env.project, file, mockk(relaxed = true), symbolsIndex, ICancelChecker.NOOP)

			val names = symbolsIndex.findByPrefix("").map { it.shortName }.toSet()
			assertThat(names).containsExactly("globalConfig", "compute")
		}

	@Test
	fun `indexing a new file reports a write`(): Unit =
		runBlocking {
			val file = createSourceFile("Fresh.kt", "fun fresh() = 0")

			val wrote = indexSourceFile(env.project, file, buildFileIndex(), buildSymbolIndex(), ICancelChecker.NOOP)

			assertThat(wrote).isTrue()
		}

	@Test
	fun `indexing an unchanged file reports no write`(): Unit =
		runBlocking {
			val file = createSourceFile("Unchanged.kt", "fun unchanged() = 0")
			val fileIndex = buildFileIndex()
			val symbolsIndex = buildSymbolIndex()
			indexSourceFile(env.project, file, fileIndex, symbolsIndex, ICancelChecker.NOOP)

			val wrote = indexSourceFile(env.project, file, fileIndex, symbolsIndex, ICancelChecker.NOOP)

			assertThat(wrote).isFalse()
		}

	@Test
	fun `indexing into a closed symbol index reports no write`(): Unit =
		runBlocking {
			val file = createSourceFile("Closed.kt", "fun closed() = 0")
			val closedBacking =
				object : Index<JvmSymbol> by InMemoryIndex(JvmSymbolDescriptor) {
					override val isOpen = false
				}
			val symbolsIndex =
				object : JvmSymbolIndex(closedBacking, BackgroundIndexer(closedBacking)) {
					override fun visibleSourceIds(): Collection<String>? = null
				}

			val wrote = indexSourceFile(env.project, file, buildFileIndex(), symbolsIndex, ICancelChecker.NOOP)

			assertThat(wrote).isFalse()
		}

	@Test
	fun `isMissingClasspathFile detects a direct NoSuchFileException`() {
		assertThat(NoSuchFileException("/caches/transforms/x/classes.jar").isMissingClasspathFile())
			.isTrue()
	}

	@Test
	fun `isMissingClasspathFile detects a wrapped NoSuchFileException`() {
		// Mirrors the observed CODEONTHEGO-D chain: FIR wraps the original NoSuchFileException.
		val wrapped = RuntimeException("Error while resolving", NoSuchFileException("classes.jar"))
		assertThat(wrapped.isMissingClasspathFile()).isTrue()
	}

	@Test
	fun `isMissingClasspathFile detects a deeply nested NoSuchFileException`() {
		val deep =
			IllegalArgumentException(
				"phase COMPANION_GENERATION",
				IllegalStateException("lazyResolve", RuntimeException(NoSuchFileException("classes.jar"))),
			)
		assertThat(deep.isMissingClasspathFile()).isTrue()
	}

	@Test
	fun `isMissingClasspathFile detects a nested FileNotFoundException`() {
		val wrapped = RuntimeException("read failed", FileNotFoundException("classes.jar"))
		assertThat(wrapped.isMissingClasspathFile()).isTrue()
	}

	@Test
	fun `isMissingClasspathFile is false for unrelated exceptions`() {
		assertThat(IllegalStateException("boom").isMissingClasspathFile()).isFalse()
		assertThat(
			IllegalArgumentException("bad", NullPointerException("npe")).isMissingClasspathFile(),
		).isFalse()
	}
}
