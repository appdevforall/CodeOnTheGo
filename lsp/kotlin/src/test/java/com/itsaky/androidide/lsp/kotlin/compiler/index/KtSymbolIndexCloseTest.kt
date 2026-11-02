package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadata
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

class KtSymbolIndexCloseTest : KtLspTest() {
	private val fileIndexReads = AtomicInteger()

	private fun slowFileIndex(): KtFileMetadataIndex {
		val backing = InMemoryIndex(KtFileMetadataDescriptor)
		return KtFileMetadataIndex(
			object : Index<KtFileMetadata> by backing {
				override suspend fun get(key: String): KtFileMetadata? {
					fileIndexReads.incrementAndGet()
					delay(READ_DELAY_MILLIS)
					return backing.get(key)
				}
			},
		)
	}

	private fun jvmIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun visibleSourceIds(): Collection<String>? = null
		}
	}

	@Test
	fun `close stops the worker without draining queued index work`(): Unit =
		runBlocking {
			val path = Paths.get(createSourceFile("Sample.kt", "fun foo() = 1").virtualFile.path)
			val symbols = jvmIndex()
			val index =
				KtSymbolIndex(
					kind = env.ktSymbolIndex.kind,
					project = env.project,
					modules = emptyList(),
					fileIndex = slowFileIndex(),
					sourceIndex = symbols,
					libraryIndex = symbols,
				)
			index.refreshSources()
			repeat(QUEUED_FILES) { index.submitForIndexing(path) }

			index.close()

			assertThat(fileIndexReads.get()).isLessThan(QUEUED_FILES)
		}

	private companion object {
		const val QUEUED_FILES = 30
		const val READ_DELAY_MILLIS = 20L
	}
}
