package org.appdevforall.codeonthego.indexing

import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.ReadableIndex
import java.io.Closeable

/**
 * Merges query results from multiple [ReadableIndex] instances.
 *
 * Indexes are queried sequentially in the order they are provided.
 * Duplicate keys (same entry present in more than one backing index)
 * are deduplicated — the first occurrence wins.
 *
 * @param T The indexed type.
 * @param indexes The indexes to merge, in priority order.
 */
class MergedIndex<T : Indexable>(
    private val indexes: List<ReadableIndex<T>>,
) : ReadableIndex<T>, Closeable {

    constructor(vararg indexes: ReadableIndex<T>) : this(indexes.toList())

    override fun query(query: IndexQuery): Sequence<T> = sequence {
        val limit = if (query.limit <= 0) Int.MAX_VALUE else query.limit
        val seen = mutableSetOf<String>()
        var total = 0
        for (index in indexes) {
            if (total >= limit) break
            for (entry in index.query(query)) {
                if (total >= limit) break
                if (seen.add(entry.key)) {
                    yield(entry)
                    total++
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

    override fun distinctValues(fieldName: String): Sequence<String> = sequence {
        val seen = mutableSetOf<String>()
        for (index in indexes) {
            for (value in index.distinctValues(fieldName)) {
                if (seen.add(value)) yield(value)
            }
        }
    }

    override fun close() {
        for (index in indexes) {
            if (index is Closeable) index.close()
        }
    }
}
