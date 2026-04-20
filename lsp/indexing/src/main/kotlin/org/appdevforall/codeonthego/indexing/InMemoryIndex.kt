package org.appdevforall.codeonthego.indexing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.iterator
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A thread-safe, in-memory [Index] backed by [ConcurrentHashMap].
 *
 * Optimized for small-to-medium datasets (source files, typically
 * hundreds to low thousands of entries) that change frequently.
 *
 * Data layout:
 * - [primaryMap]: key → entry (O(1) point lookup)
 * - [sourceMap]: sourceId → set of keys (O(1) bulk removal)
 * - [fieldMaps]: fieldName → (fieldValue → set of keys) (equality filter)
 * - [prefixBuckets]: fieldName → (lowercased first char → list of (value, key))
 *                    Provides a ~36-way partition for prefix search.
 *
 * All mutations go through [lock] in write mode for consistency
 * across the multiple maps. Reads use read mode.
 *
 * @param T The indexed entry type.
 * @param descriptor Defines queryable fields and serialization.
 */
class InMemoryIndex<T : Indexable>(
    override val descriptor: IndexDescriptor<T>,
    override val name: String = "memory:${descriptor.name}",
) : Index<T> {

    private val primaryMap = ConcurrentHashMap<String, T>(256)
    private val sourceMap = ConcurrentHashMap<String, MutableSet<String>>(32)
    private val fieldMaps = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>>()
    private val prefixBuckets = ConcurrentHashMap<String, ConcurrentHashMap<Char, MutableList<PrefixEntry>>>()

    private val lock = ReentrantReadWriteLock()

    private data class PrefixEntry(val lowerValue: String, val key: String)

    init {
        for (field in descriptor.fields) {
            fieldMaps[field.name] = ConcurrentHashMap()
            if (field.prefixSearchable) {
                prefixBuckets[field.name] = ConcurrentHashMap()
            }
        }
    }

    override fun query(query: IndexQuery): Flow<T> = flow {
        val keys = resolveMatchingKeys(query)
        var emitted = 0
        val limit = if (query.limit <= 0) Int.MAX_VALUE else query.limit

        for (key in keys) {
            if (emitted >= limit) break
            val entry = primaryMap[key] ?: continue
            emit(entry)
            emitted++
        }
    }

    override suspend fun get(key: String): T? = primaryMap[key]

    override suspend fun containsSource(sourceId: String): Boolean =
        sourceMap.containsKey(sourceId)

    override fun distinctValues(fieldName: String): Flow<String> = flow {
        val fieldMap = fieldMaps[fieldName] ?: return@flow
        lock.read {
            for (value in fieldMap.keys) {
                emit(value)
            }
        }
    }

    override suspend fun insert(entries: Flow<T>) {
        entries.collect { entry -> insertSingle(entry) }
    }

    override suspend fun insertAll(entries: Sequence<T>) {
        lock.write {
            for (entry in entries) {
                insertSingleLocked(entry)
            }
        }
    }

    override suspend fun insert(entry: T) = insertSingle(entry)

    override suspend fun removeBySource(sourceId: String) = lock.write {
        val keys = sourceMap.remove(sourceId) ?: return@write
        for (key in keys) {
            val entry = primaryMap.remove(key) ?: continue
            removeFromSecondaryIndexes(entry)
        }
    }

    override suspend fun clear() = lock.write {
        primaryMap.clear()
        sourceMap.clear()
        fieldMaps.values.forEach { it.clear() }
        prefixBuckets.values.forEach { it.clear() }
    }

    val size: Int get() = primaryMap.size
    val sourceCount: Int get() = sourceMap.size

    /**
     * Resolves the set of keys matching the query by intersecting
     * the results of each predicate.
     *
     * Starts with the most selective predicate to minimize the
     * intersection set.
     */
    private fun resolveMatchingKeys(query: IndexQuery): Sequence<String> = lock.read {
        var candidates: Set<String>? = null

        if (query.key != null) {
            return@read if (primaryMap.containsKey(query.key)) {
                sequenceOf(query.key)
            } else {
                emptySequence()
            }
        }

        if (query.sourceId != null) {
            candidates = intersect(candidates, sourceMap[query.sourceId])
        }

        for ((field, value) in query.exactMatch) {
            val fieldMap = fieldMaps[field] ?: return@read emptySequence()
            candidates = intersect(candidates, fieldMap[value])
        }

        for ((field, prefix) in query.prefixMatch) {
            val buckets = prefixBuckets[field] ?: return@read emptySequence()
            val lowerPrefix = prefix.lowercase()
            val firstChar = lowerPrefix.firstOrNull() ?: continue
            val bucket = buckets[firstChar] ?: return@read emptySequence()

            val matching = bucket.asSequence()
                .filter { it.lowerValue.startsWith(lowerPrefix) }
                .map { it.key }
                .toSet()

            candidates = intersect(candidates, matching)
        }

        for ((field, mustExist) in query.presence) {
            val fieldMap = fieldMaps[field] ?: return@read emptySequence()
            val allKeysWithField = fieldMap.values.flatMapTo(mutableSetOf()) { it }
            candidates = if (mustExist) {
                intersect(candidates, allKeysWithField)
            } else {
                // Keys that DON'T have this field
                val allKeys = primaryMap.keys.toMutableSet()
                allKeys.removeAll(allKeysWithField)
                intersect(candidates, allKeys)
            }
        }

        candidates?.asSequence() ?: primaryMap.keys.asSequence()
    }

    private fun intersect(current: Set<String>?, other: Set<String>?): Set<String>? {
        if (other == null) return current
        if (current == null) return other
        return current.intersect(other)
    }

    private fun insertSingle(entry: T) = lock.write {
        insertSingleLocked(entry)
    }

    private fun insertSingleLocked(entry: T) {
        val existing = primaryMap[entry.key]
        if (existing != null) {
            removeFromSecondaryIndexes(existing)
        }

        primaryMap[entry.key] = entry
        sourceMap.getOrPut(entry.sourceId) { mutableSetOf() }.add(entry.key)

        val fields = descriptor.fieldValues(entry)
        for ((fieldName, value) in fields) {
            if (value == null) continue

            fieldMaps[fieldName]
                ?.getOrPut(value) { mutableSetOf() }
                ?.add(entry.key)

            val buckets = prefixBuckets[fieldName]
            if (buckets != null) {
                val lower = value.lowercase()
                val firstChar = lower.firstOrNull() ?: continue
                buckets.getOrPut(firstChar) { mutableListOf() }
                    .add(PrefixEntry(lower, entry.key))
            }
        }
    }

    private fun removeFromSecondaryIndexes(entry: T) {
        val fields = descriptor.fieldValues(entry)
        for ((fieldName, value) in fields) {
            if (value == null) continue

            fieldMaps[fieldName]?.get(value)?.remove(entry.key)

            val buckets = prefixBuckets[fieldName]
            if (buckets != null) {
                val lower = value.lowercase()
                val firstChar = lower.firstOrNull() ?: continue
                buckets[firstChar]?.removeAll { it.key == entry.key }
            }
        }
        // Note: sourceMap is handled by the caller
    }
}
