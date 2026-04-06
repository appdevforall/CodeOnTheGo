package org.appdevforall.codeonthego.indexing.api

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * Read-only view of an index.
 *
 * All query methods return [Flow]s and results are produced lazily.
 * The consumer decides how many to take, which dispatcher to
 * collect on, and whether to buffer.
 *
 * @param T The indexed type.
 */
interface ReadableIndex<T : Indexable> {

    /**
     * Query the index. Returns a lazy [Flow] of matching entries.
     *
     * Results are not guaranteed to be in any particular order
     * unless the implementation specifies otherwise.
     *
     * If [IndexQuery.limit] is 0, all matches are emitted.
     */
    fun query(query: IndexQuery): Flow<T>

    /**
     * Point lookup by key. Returns null if not found.
     */
    suspend fun get(key: String): T?

    /**
     * Fast existence check for a source.
     */
    suspend fun containsSource(sourceId: String): Boolean

    /**
     * Returns distinct values for a given field across all entries.
     *
     * Useful for enumerating packages, kinds, etc. without
     * deserializing full entries.
     *
     * @param fieldName Must be one of the fields declared in the
     *                  [IndexDescriptor].
     */
    fun distinctValues(fieldName: String): Flow<String>
}

/**
 * Write interface for mutating an index.
 *
 * Accepts [Flow]s for streaming inserts so that the producer can
 * yield entries one at a time without holding the entire set
 * in memory.
 */
interface WritableIndex<T : Indexable> {

    /**
     * Insert entries from a [Flow].
     *
     * Entries are consumed lazily from the flow and batched
     * internally for throughput. If an entry with the same key
     * already exists, it is replaced.
     *
     * The flow is collected on the caller's dispatcher; the
     * implementation handles its own threading for storage I/O.
     */
    suspend fun insert(entries: Flow<T>)

    /**
     * Convenience: insert a sequence (also lazy).
     */
    suspend fun insertAll(entries: Sequence<T>)

    /**
     * Convenience: insert a single entry.
     */
    suspend fun insert(entry: T)

    /**
     * Remove all entries from the given source.
     */
    suspend fun removeBySource(sourceId: String)

    /**
     * Remove all entries.
     */
    suspend fun clear()
}

/**
 * A complete index with read, write, and lifecycle management.
 *
 * @param T The indexed type.
 */
interface Index<T : Indexable> : ReadableIndex<T>, WritableIndex<T>, Closeable {

    /** Human-readable name for logging. */
    val name: String

    /** The descriptor governing serialization and field extraction. */
    val descriptor: IndexDescriptor<T>

    override fun close() {}
}
