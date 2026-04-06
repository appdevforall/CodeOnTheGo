package org.appdevforall.codeonthego.indexing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.appdevforall.codeonthego.indexing.api.ReadableIndex
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * A read-only view over an index that only exposes entries
 * from a set of active sources.
 *
 * The underlying index retains ALL data (it's a persistent cache).
 * This view controls which subset is visible based on which
 * sources (JAR paths, etc.) are currently "active."
 *
 * @param T The indexed type.
 * @param backing The underlying index that holds all data.
 */
open class FilteredIndex<T : Indexable>(
	private val backing: ReadableIndex<T>,
) : ReadableIndex<T>, Closeable {

	/**
	 * The set of source IDs whose entries are visible.
	 * Uses a concurrent set for thread-safe reads during queries.
	 */
	private val activeSources = ConcurrentHashMap.newKeySet<String>()

	/**
	 * Make a source's entries visible in query results.
	 */
	fun activateSource(sourceId: String) {
		activeSources.add(sourceId)
	}

	/**
	 * Hide a source's entries from query results.
	 * The data remains in the backing index.
	 */
	fun deactivateSource(sourceId: String) {
		activeSources.remove(sourceId)
	}

	/**
	 * Replace the entire active set. Sources not in [sourceIds]
	 * become invisible; sources in [sourceIds] become visible.
	 *
	 * This is the typical call on project sync: pass in all
	 * current classpath JAR paths.
	 */
	fun setActiveSources(sourceIds: Set<String>) {
		activeSources.clear()
		activeSources.addAll(sourceIds)
	}

	/**
	 * Returns the current set of active source IDs.
	 */
	fun activeSources(): Set<String> =
		activeSources.toSet()

	/**
	 * Returns true if the source is currently active (visible).
	 */
	fun isActive(sourceId: String): Boolean =
		sourceId in activeSources

	/**
	 * Returns true if the source exists in the backing index,
	 * regardless of whether it's active.
	 *
	 * Use this to check if a JAR needs indexing at all.
	 */
	suspend fun isCached(sourceId: String): Boolean =
		backing.containsSource(sourceId)

	override fun query(query: IndexQuery): Flow<T> {
		// If the query already specifies a sourceId, check if it's active
		if (query.sourceId != null && query.sourceId !in activeSources) {
			return kotlinx.coroutines.flow.emptyFlow()
		}

		return backing.query(query).filter { it.sourceId in activeSources }
	}

	override suspend fun get(key: String): T? {
		val entry = backing.get(key) ?: return null
		return if (entry.sourceId in activeSources) entry else null
	}

	override suspend fun containsSource(sourceId: String): Boolean {
		return sourceId in activeSources && backing.containsSource(sourceId)
	}

	override fun distinctValues(fieldName: String): Flow<String> {
		// This is imprecise — the backing index may return values
		// from inactive sources. For exact results, we'd need to
		// query all entries and filter. For package enumeration
		// (the main use case), this approximation is acceptable
		// since packages from inactive JARs are harmless — they
		// just produce empty results when queried further.
		return backing.distinctValues(fieldName)
	}

	override fun close() {
		activeSources.clear()
		if (backing is Closeable) backing.close()
	}
}