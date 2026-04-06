package org.appdevforall.codeonthego.indexing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.ReadableIndex
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Merges query results from multiple [ReadableIndex] instances.
 *
 * @param T The indexed type.
 * @param indexes The indexes to merge, in priority order.
 */
class MergedIndex<T : Indexable>(
    private val indexes: List<ReadableIndex<T>>,
) : ReadableIndex<T>, Closeable {

    constructor(vararg indexes: ReadableIndex<T>) : this(indexes.toList())

    override fun query(query: IndexQuery): Flow<T> = channelFlow {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val limit = if (query.limit <= 0) Int.MAX_VALUE else query.limit
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)

        // Launch a producer coroutine per index.
        // channelFlow provides structured concurrency: when the
        // collector stops (limit reached), all producers are cancelled.
        for (index in indexes) {
            launch {
                index.query(query).collect { entry ->
                    if (emitted.get() >= limit) {
                        return@collect
                    }
                    if (seen.add(entry.key)) {
                        send(entry)
                        if (emitted.incrementAndGet() >= limit) {
                            // Close the channel - cancels other producers
                            channel.close()
                            return@collect
                        }
                    }
                }
            }
        }
    }

    override suspend fun get(key: String): T? {
        // First match wins (priority order)
        for (index in indexes) {
            val result = index.get(key)
            if (result != null) return result
        }
        return null
    }

    override suspend fun containsSource(sourceId: String): Boolean {
        return indexes.any { it.containsSource(sourceId) }
    }

    override fun distinctValues(fieldName: String): Flow<String> = channelFlow {
        val seen = ConcurrentHashMap.newKeySet<String>()
        for (index in indexes) {
            launch {
                index.distinctValues(fieldName).collect { value ->
                    if (seen.add(value)) {
                        send(value)
                    }
                }
            }
        }
    }

    override fun close() {
        for (index in indexes) {
            if (index is Closeable) index.close()
        }
    }
}
