package org.appdevforall.codeonthego.indexing

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
) : ReadableIndex<T>,
	Closeable {
	/**
	 * The set of source IDs whose entries are visible.
	 * Uses a concurrent set for thread-safe reads during queries.
	 */
	private val activeSources = ConcurrentHashMap.newKeySet<String>()

	/**
	 * Make a source's entries visible in query results.
	 */
	open fun activateSource(sourceId: String) {
		activeSources.add(sourceId)
	}

	/**
	 * Hide a source's entries from query results.
	 * The data remains in the backing index.
	 */
	open fun deactivateSource(sourceId: String) {
		activeSources.remove(sourceId)
	}

	/**
	 * Replace the entire active set. Sources not in [sourceIds]
	 * become invisible; sources in [sourceIds] become visible.
	 *
	 * This is the typical call on project sync: pass in all
	 * current classpath JAR paths.
	 */
	open fun setActiveSources(sourceIds: Set<String>) {
		activeSources.clear()
		activeSources.addAll(sourceIds)
	}

	/**
	 * Returns the current set of active source IDs.
	 */
	open fun activeSources(): Set<String> = activeSources.toSet()

	/**
	 * The source IDs whose entries are visible, or `null` if every source is.
	 *
	 * This, rather than [isActive], is the point to override to change what the filter admits.
	 * Scoping is pushed into the query, so the filter has to be able to *describe* its scope and not
	 * merely test one ID against it -- an override of [isActive] alone could not be honoured, and
	 * would silently hide every entry instead.
	 */
	protected open fun visibleSourceIds(): Collection<String>? = activeSources

	/**
	 * Returns true if the source is currently active (visible).
	 */
	fun isActive(sourceId: String): Boolean = visibleSourceIds()?.contains(sourceId) ?: true

	/**
	 * Returns true if the source exists in the backing index,
	 * regardless of whether it's active.
	 *
	 * Use this to check if a JAR needs indexing at all.
	 */
	open suspend fun isCached(sourceId: String): Boolean = backing.containsSource(sourceId)

	override fun query(query: IndexQuery): Sequence<T> = backing.query(scopedToActive(query))

	/**
	 * Narrows [query] to the active sources by rewriting its scope, rather than by filtering the
	 * rows it returns.
	 *
	 * Filtering afterwards is wrong whenever the query is limited: the backing index applies the
	 * limit first, so a page full of inactive rows yields nothing even though matches exist. Pushing
	 * the active set into the query makes the limit count only rows the caller can actually see.
	 */
	private fun scopedToActive(query: IndexQuery): IndexQuery {
		val visible = visibleSourceIds()?.toSet() ?: return query

		if (query.sourceId != null) {
			// Already as narrow as a scope gets: it either survives the active set or matches nothing.
			return if (query.sourceId in visible) query else query.copy(sourceIds = emptyList())
		}

		val requested = query.sourceIds
		val scoped = requested?.filter { it in visible } ?: visible
		return query.copy(sourceIds = scoped)
	}

	override suspend fun get(key: String): T? {
		val entry = backing.get(key) ?: return null
		return if (isActive(entry.sourceId)) entry else null
	}

	override suspend fun containsSource(sourceId: String): Boolean = isActive(sourceId) && backing.containsSource(sourceId)

	override fun distinctValues(
		fieldName: String,
		query: IndexQuery,
	): Sequence<String> = backing.distinctValues(fieldName, scopedToActive(query))

	override fun close() {
		activeSources.clear()
		if (backing is Closeable) backing.close()
	}
}
