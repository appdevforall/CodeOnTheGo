package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.progress.ICancelChecker
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test

class SourceFileIndexerTest : KtLspTest() {

	private fun buildSymbolIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun isActive(sourceId: String): Boolean = true
		}
	}

	@Test
	fun `indexer ignores local property inside function body`(): Unit = runBlocking {
		val file = createSourceFile(
			"PollingWorker.kt", """
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
        """.trimIndent()
		)

		val symbolsIndex = buildSymbolIndex()

		indexSourceFile(env.project, file, mockk(relaxed = true), symbolsIndex, ICancelChecker.NOOP)

		val names = symbolsIndex.findByPrefix("").map { it.shortName }.toSet()
		assertThat(names).containsExactly("doWork", "fetchStatus")
	}

	@Test
	fun `indexer ignores local function inside function body`(): Unit = runBlocking {
		val file = createSourceFile(
			"Helpers.kt", """
            fun outer(): Int {
                fun inner() = 42
                return inner()
            }
        """.trimIndent()
		)

		val symbolsIndex = buildSymbolIndex()

		indexSourceFile(env.project, file, mockk(relaxed = true), symbolsIndex, ICancelChecker.NOOP)

		val names = symbolsIndex.findByPrefix("").map { it.shortName }.toSet()
		assertThat(names).containsExactly("outer")
	}

	@Test
	fun `top-level property and function are both indexed`(): Unit = runBlocking {
		val file = createSourceFile(
			"TopLevel.kt", """
            val globalConfig: String = "default"
            fun compute(): Int = 0
        """.trimIndent()
		)

		val symbolsIndex = buildSymbolIndex()

		indexSourceFile(env.project, file, mockk(relaxed = true), symbolsIndex, ICancelChecker.NOOP)

		val names = symbolsIndex.findByPrefix("").map { it.shortName }.toSet()
		assertThat(names).containsExactly("globalConfig", "compute")
	}
}
