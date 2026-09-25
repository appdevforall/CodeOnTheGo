package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CopyOnWriteArrayList

/** When an indexing pass optimizes the index behind a [JvmSymbolIndex]. */
@RunWith(JUnit4::class)
class JvmSymbolIndexOptimizeTest {
	/** Records which of the test's sources the index held each time it was optimized. */
	private class RecordingIndex(
		private val backing: Index<JvmSymbol>,
	) : Index<JvmSymbol> by backing {
		val sourcesAtOptimize = CopyOnWriteArrayList<Set<String>>()

		override suspend fun optimize() {
			sourcesAtOptimize += listOf(FIRST, SECOND).filter { backing.containsSource(it) }.toSet()
		}
	}

	@Test
	fun `a pass with a gap between submissions optimizes once, after every job`() =
		runTest {
			val backing = RecordingIndex(InMemoryIndex(JvmSymbolDescriptor))
			val index = JvmSymbolIndex(backing, BackgroundIndexer(backing))

			val first = index.indexSource(FIRST, skipIfExists = false) { sequenceOf(classifier("Foo", it)) }
			// The gap: the first job finishes before the second is submitted, leaving the indexer idle mid-pass.
			first.join()
			val second = index.indexSource(SECOND, skipIfExists = false) { sequenceOf(classifier("Bar", it)) }
			index.optimizeAfter(listOf(first, second))

			assertThat(backing.sourcesAtOptimize).isEqualTo(listOf(setOf(FIRST, SECOND)))
		}

	@Test
	fun `a pass that submits nothing does not optimize`() =
		runTest {
			val backing = RecordingIndex(InMemoryIndex(JvmSymbolDescriptor))
			val index = JvmSymbolIndex(backing, BackgroundIndexer(backing))

			index.optimizeAfter(emptyList())

			assertThat(backing.sourcesAtOptimize).isEmpty()
		}

	private fun classifier(
		simpleName: String,
		sourceId: String,
	) = JvmSymbol(
		key = "com.example.$simpleName",
		sourceId = sourceId,
		name = "com.example.$simpleName",
		shortName = simpleName,
		packageName = "com.example",
		kind = JvmSymbolKind.CLASS,
		language = JvmSourceLanguage.JAVA,
		data = JvmClassInfo(internalName = "com/example/$simpleName"),
	)

	private companion object {
		const val FIRST = "/libs/first.jar"
		const val SECOND = "/libs/second.jar"
	}
}
