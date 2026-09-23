package org.appdevforall.codeonthego.indexing

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
 * An entry is identified by its source and key together, matching [SQLiteIndex]: the same key in
 * two sources is two entries, and a key lookup matching several returns the smallest source id.
 *
 * Data layout:
 * - [rows]: (sourceId, key) -> entry
 * - [sourceMap]: sourceId -> rows (O(1) bulk removal)
 * - [keyMap]: key -> rows (key lookup)
 * - [fieldMaps]: fieldName -> (fieldValue -> rows) (equality filter)
 * - [prefixBuckets]: fieldName -> (lowercased first char -> list of (value, row))
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
	private val rows = ConcurrentHashMap<RowId, T>(256)
	private val sourceMap = ConcurrentHashMap<String, MutableSet<RowId>>(32)
	private val keyMap = ConcurrentHashMap<String, MutableSet<RowId>>(256)
	private val fieldMaps = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<RowId>>>()
	private val prefixBuckets = ConcurrentHashMap<String, ConcurrentHashMap<Char, MutableList<PrefixEntry>>>()

	private val lock = ReentrantReadWriteLock()

	private data class RowId(
		val sourceId: String,
		val key: String,
	)

	private data class PrefixEntry(
		val lowerValue: String,
		val row: RowId,
	)

	init {
		for (field in descriptor.fields) {
			fieldMaps[field.name] = ConcurrentHashMap()
			if (field.prefixSearchable) {
				prefixBuckets[field.name] = ConcurrentHashMap()
			}
		}
	}

	override fun query(query: IndexQuery): Sequence<T> {
		val matching = resolveMatchingRows(query)
		val ordered = if (query.key != null) matching.sortedBy { it.sourceId } else matching
		val limit = if (query.limit <= 0) Int.MAX_VALUE else query.limit
		return ordered
			.asSequence()
			.mapNotNull { rows[it] }
			.take(limit)
	}

	override suspend fun get(key: String): T? =
		lock.read {
			keyMap[key]?.minByOrNull { it.sourceId }?.let { rows[it] }
		}

	override suspend fun containsSource(sourceId: String): Boolean = sourceMap.containsKey(sourceId)

	/**
	 * Projects [fieldName] out of the matching entries.
	 *
	 * Unlike the SQLite index there is no column to scan, so this reduces over the matched entries.
	 * That is acceptable at the sizes this index is built for (hundreds to low thousands of entries);
	 * it is not a general substitute for a column projection.
	 */
	override fun distinctValues(
		fieldName: String,
		query: IndexQuery,
	): Sequence<String> {
		if (!fieldMaps.containsKey(fieldName)) {
			return emptySequence()
		}

		val limit = if (query.limit <= 0) Int.MAX_VALUE else query.limit
		return lock
			.read {
				val values = LinkedHashSet<String>()
				for (row in resolveMatchingRows(query)) {
					if (values.size >= limit) {
						break
					}
					val entry = rows[row] ?: continue
					descriptor.fieldValues(entry)[fieldName]?.let { values.add(it) }
				}
				values.toList()
			}.asSequence()
	}

	override suspend fun insertAll(entries: Sequence<T>) {
		lock.write {
			for (entry in entries) {
				insertSingleLocked(entry)
			}
		}
	}

	override suspend fun insert(entry: T) = lock.write { insertSingleLocked(entry) }

	override suspend fun removeBySource(sourceId: String) =
		lock.write {
			removeBySourceLocked(sourceId)
		}

	/**
	 * Remove every entry belonging to any of [sourceIds].
	 *
	 * Acquires the write lock once and removes each source under it, so the whole
	 * batch is atomic with respect to concurrent readers and writers - there is no
	 * intermediate state in which only some of the sources have been removed.
	 */
	override suspend fun removeBySources(sourceIds: Collection<String>) =
		lock.write {
			for (sourceId in sourceIds) {
				removeBySourceLocked(sourceId)
			}
		}

	/**
	 * Remove all entries for [sourceId] from the primary, source, and secondary
	 * indexes. Caller MUST already hold the write lock; this method does not lock.
	 */
	private fun removeBySourceLocked(sourceId: String) {
		val sourceRows = sourceMap.remove(sourceId) ?: return
		for (row in sourceRows) {
			val entry = rows.remove(row) ?: continue
			keyMap[row.key]?.let { keyRows ->
				keyRows.remove(row)
				if (keyRows.isEmpty()) keyMap.remove(row.key)
			}
			removeFromSecondaryIndexes(row, entry)
		}
	}

	override suspend fun clear() =
		lock.write {
			rows.clear()
			sourceMap.clear()
			keyMap.clear()
			fieldMaps.values.forEach { it.clear() }
			prefixBuckets.values.forEach { it.clear() }
		}

	val size: Int get() = rows.size
	val sourceCount: Int get() = sourceMap.size

	/**
	 * Resolves the rows matching the query by intersecting the results of each predicate.
	 *
	 * Every predicate is ANDed, the key included, as SQLite does. A predicate naming a value no row
	 * has matches nothing rather than being ignored. The result is materialized under the read lock
	 * so callers never iterate a set a concurrent writer is mutating.
	 */
	private fun resolveMatchingRows(query: IndexQuery): List<RowId> =
		lock.read {
			var candidates: Set<RowId>? = null

			if (query.key != null) {
				candidates = intersect(candidates, keyMap[query.key].orEmpty())
			}

			if (query.sourceId != null) {
				candidates = intersect(candidates, sourceMap[query.sourceId].orEmpty())
			}

			val sourceIds = query.sourceIds
			if (sourceIds != null) {
				// An empty scope matches nothing, as distinct from an absent scope matching anything.
				val scoped = sourceIds.flatMapTo(mutableSetOf()) { sourceMap[it].orEmpty() }
				candidates = intersect(candidates, scoped)
			}

			for ((field, value) in query.exactMatch) {
				val fieldMap = fieldMaps[field] ?: return@read emptyList()
				candidates = intersect(candidates, fieldMap[value].orEmpty())
			}

			for ((field, values) in query.anyOf) {
				val fieldMap = fieldMaps[field] ?: return@read emptyList()
				val matching = values.flatMapTo(mutableSetOf()) { fieldMap[it].orEmpty() }
				candidates = intersect(candidates, matching)
			}

			for ((field, prefix) in query.prefixMatch) {
				val matching = rowsWithPrefix(field, prefix) ?: return@read emptyList()
				candidates = intersect(candidates, matching)
			}

			for ((field, mustExist) in query.presence) {
				val fieldMap = fieldMaps[field] ?: return@read emptyList()
				val rowsWithField = fieldMap.values.flatMapTo(mutableSetOf()) { it }
				candidates =
					if (mustExist) {
						intersect(candidates, rowsWithField)
					} else {
						intersect(candidates, rows.keys - rowsWithField)
					}
			}

			(candidates ?: rows.keys).toList()
		}

	/**
	 * Rows whose [field] starts with [prefix], or `null` when [field] is not declared.
	 *
	 * Prefix-searchable fields match case-insensitively via the lowercased buckets, mirroring
	 * SQLite's lowercased column; any other field falls back to a case-sensitive scan of its values.
	 * An empty prefix means "field present", which excludes rows where the field is null.
	 */
	private fun rowsWithPrefix(
		field: String,
		prefix: String,
	): Set<RowId>? {
		val buckets = prefixBuckets[field]
		if (buckets == null) {
			val fieldMap = fieldMaps[field] ?: return null
			return fieldMap.entries
				.asSequence()
				.filter { (value, _) -> value.startsWith(prefix) }
				.flatMap { (_, valueRows) -> valueRows.asSequence() }
				.toSet()
		}

		val lowerPrefix = prefix.lowercase()
		val firstChar = lowerPrefix.firstOrNull()
		if (firstChar == null) {
			return buckets.values.flatMapTo(mutableSetOf()) { entries -> entries.map { it.row } }
		}
		return buckets[firstChar]
			.orEmpty()
			.asSequence()
			.filter { it.lowerValue.startsWith(lowerPrefix) }
			.map { it.row }
			.toSet()
	}

	private fun intersect(
		current: Set<RowId>?,
		other: Set<RowId>,
	): Set<RowId> {
		if (current == null) return other
		return current.intersect(other)
	}

	private fun insertSingleLocked(entry: T) {
		val row = RowId(entry.sourceId, entry.key)
		val existing = rows[row]
		if (existing != null) {
			removeFromSecondaryIndexes(row, existing)
		}

		rows[row] = entry
		sourceMap.getOrPut(entry.sourceId) { mutableSetOf() }.add(row)
		keyMap.getOrPut(entry.key) { mutableSetOf() }.add(row)

		val fields = descriptor.fieldValues(entry)
		for ((fieldName, value) in fields) {
			if (value == null) continue

			fieldMaps[fieldName]
				?.getOrPut(value) { mutableSetOf() }
				?.add(row)

			val buckets = prefixBuckets[fieldName]
			if (buckets != null) {
				val lower = value.lowercase()
				val firstChar = lower.firstOrNull() ?: continue
				buckets
					.getOrPut(firstChar) { mutableListOf() }
					.add(PrefixEntry(lower, row))
			}
		}
	}

	/** Drops [row] from the field and prefix maps; [sourceMap] and [keyMap] are the caller's job. */
	private fun removeFromSecondaryIndexes(
		row: RowId,
		entry: T,
	) {
		val fields = descriptor.fieldValues(entry)
		for ((fieldName, value) in fields) {
			if (value == null) continue

			fieldMaps[fieldName]?.get(value)?.remove(row)

			val buckets = prefixBuckets[fieldName]
			if (buckets != null) {
				val lower = value.lowercase()
				val firstChar = lower.firstOrNull() ?: continue
				buckets[firstChar]?.removeAll { it.row == row }
			}
		}
	}
}
