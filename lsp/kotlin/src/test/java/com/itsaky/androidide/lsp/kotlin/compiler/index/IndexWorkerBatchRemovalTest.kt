package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadata
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Paths
import java.time.Instant

/**
 * Verifies the ADFA-4332 wiring: a run of consecutive [IndexCommand.RemoveFromIndex]
 * commands must collapse the symbol-index deletes into ONE batched
 * [JvmSymbolIndex.removeBySources] call (a single SQLite transaction) instead of
 * N separate [Index.removeBySource] calls (N transactions — the Sentry N+1).
 */
@RunWith(JUnit4::class)
class IndexWorkerBatchRemovalTest {

    /**
     * Counting decorator over a real backing [Index] that records how the batched
     * vs. per-source removal APIs are exercised. This is the mutation-sensitive
     * probe: if the wiring regressed to calling [removeBySource] in a loop, the
     * `removeBySourceCalls` counter would climb and `removeBySourcesBatches` stay 0.
     */
    private class CountingIndex<T : org.appdevforall.codeonthego.indexing.api.Indexable>(
        private val backing: Index<T>,
    ) : Index<T> by backing {
        var removeBySourceCalls = 0
            private set
        var removeBySourcesBatches = 0
            private set
        val removedInLargestBatch = mutableListOf<String>()

        override suspend fun removeBySource(sourceId: String) {
            removeBySourceCalls++
            backing.removeBySource(sourceId)
        }

        override suspend fun removeBySources(sourceIds: Collection<String>) {
            removeBySourcesBatches++
            if (sourceIds.size >= removedInLargestBatch.size) {
                removedInLargestBatch.clear()
                removedInLargestBatch.addAll(sourceIds)
            }
            backing.removeBySources(sourceIds)
        }
    }

    private fun symbol(sourceId: String) = JvmSymbol(
        key = "$sourceId#Type",
        sourceId = sourceId,
        name = "com/example/Type",
        shortName = "Type",
        packageName = "com.example",
        kind = JvmSymbolKind.CLASS,
        language = JvmSourceLanguage.KOTLIN,
        data = JvmClassInfo(),
    )

    private fun fileMeta(path: String) = KtFileMetadata(
        filePath = path,
        packageFqName = "com.example",
        lastModified = Instant.EPOCH,
        modificationStamp = 1L,
        isIndexed = true,
        symbolKeys = listOf("$path#Type"),
    )

    private fun makeSymbolIndex(
        backing: Index<JvmSymbol> = InMemoryIndex(JvmSymbolDescriptor),
    ): Pair<CountingIndex<JvmSymbol>, JvmSymbolIndex> {
        val counting = CountingIndex(backing)
        val index = object : JvmSymbolIndex(counting, BackgroundIndexer(counting)) {
            override fun isActive(sourceId: String): Boolean = true
        }
        return counting to index
    }

    private fun makeFileIndex() =
        KtFileMetadataIndex(InMemoryIndex(KtFileMetadataDescriptor))

    private fun removeCmd(path: String) =
        IndexCommand.RemoveFromIndex(Paths.get(path))

    @Test
    fun `N consecutive removals collapse into ONE batched removeBySources`() = runTest {
        val paths = (1..5).map { "/proj/File$it.kt" }
        val (counting, symbolIndex) = makeSymbolIndex()
        val fileIndex = makeFileIndex()

        // Seed: one symbol + one metadata record per file.
        symbolIndex.insertAll(paths.asSequence().map { symbol(it) })
        for (p in paths) fileIndex.upsert(fileMeta(p))

        // The queue holds N-1 further removals after the first, then runs dry.
        val rest = ArrayDeque(paths.drop(1).map { removeCmd(it) as IndexCommand })
        val pushedBack = mutableListOf<IndexCommand>()

        applyRemovals(
            first = removeCmd(paths.first()) as IndexCommand.RemoveFromIndex,
            fileIndex = fileIndex,
            sourceIndex = symbolIndex,
            pollNext = { rest.removeFirstOrNull() },
            pushBack = { pushedBack.add(it) },
        )

        // The fix: exactly one batched transaction, zero per-source deletes.
        assertThat(counting.removeBySourcesBatches).isEqualTo(1)
        assertThat(counting.removeBySourceCalls).isEqualTo(0)
        assertThat(counting.removedInLargestBatch).containsExactlyElementsIn(paths)
        assertThat(pushedBack).isEmpty()

        // Correctness: every symbol and metadata record is gone.
        for (p in paths) {
            symbolIndex.activateSource(p)
            assertThat(symbolIndex.findByKey("$p#Type")).isNull()
            assertThat(fileIndex.get(p)).isNull()
        }
    }

    @Test
    fun `a non-removal command stops the batch and is pushed back unconsumed`() = runTest {
        val rmPaths = listOf("/proj/A.kt", "/proj/B.kt")
        val (counting, symbolIndex) = makeSymbolIndex()
        val fileIndex = makeFileIndex()
        symbolIndex.insertAll(rmPaths.asSequence().map { symbol(it) })
        for (p in rmPaths) fileIndex.upsert(fileMeta(p))

        val interloper: IndexCommand = IndexCommand.IndexingComplete
        val queue = ArrayDeque(
            listOf(removeCmd(rmPaths[1]) as IndexCommand, interloper)
        )
        val pushedBack = mutableListOf<IndexCommand>()

        applyRemovals(
            first = removeCmd(rmPaths[0]) as IndexCommand.RemoveFromIndex,
            fileIndex = fileIndex,
            sourceIndex = symbolIndex,
            pollNext = { queue.removeFirstOrNull() },
            pushBack = { pushedBack.add(it) },
        )

        // Both removals batched together; the non-removal command preserved.
        assertThat(counting.removeBySourcesBatches).isEqualTo(1)
        assertThat(counting.removeBySourceCalls).isEqualTo(0)
        assertThat(counting.removedInLargestBatch).containsExactlyElementsIn(rmPaths)
        assertThat(pushedBack).containsExactly(interloper)
    }

    @Test
    fun `InMemory and SQLite-style backings give identical removeBySources results (parity)`() = runTest {
        // Parity at the primitive level: removeBySources must equal N removeBySource.
        val paths = listOf("/p/X.kt", "/p/Y.kt", "/p/Z.kt")

        val batched = InMemoryIndex(JvmSymbolDescriptor)
        val oneByOne = InMemoryIndex(JvmSymbolDescriptor)
        for (p in paths) {
            batched.insert(symbol(p))
            oneByOne.insert(symbol(p))
        }

        batched.removeBySources(paths)
        for (p in paths) oneByOne.removeBySource(p)

        val left = batched.query(IndexQuery.ALL).toList()
        val right = oneByOne.query(IndexQuery.ALL).toList()
        assertThat(left).isEmpty()
        assertThat(left).containsExactlyElementsIn(right)
    }

    @Test
    fun `removeBySources only deletes the named sources`() = runTest {
        val backing = InMemoryIndex(JvmSymbolDescriptor)
        backing.insert(symbol("/p/Keep.kt"))
        backing.insert(symbol("/p/Drop1.kt"))
        backing.insert(symbol("/p/Drop2.kt"))

        backing.removeBySources(listOf("/p/Drop1.kt", "/p/Drop2.kt"))

        val remaining = backing.query(IndexQuery.ALL).map { it.sourceId }.toList()
        assertThat(remaining).containsExactly("/p/Keep.kt")
    }
}
