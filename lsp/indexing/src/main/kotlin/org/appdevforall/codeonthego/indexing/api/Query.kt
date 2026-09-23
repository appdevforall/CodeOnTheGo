package org.appdevforall.codeonthego.indexing.api

/**
 * A query against an index.
 *
 * All predicates are ANDed together. The query is intentionally
 * field-based (not type-specific) so the same query engine works
 * for Kotlin symbols, Android resources, Python declarations, etc.
 */
data class IndexQuery(
	/** Exact match predicates: field name -> expected value. */
	val exactMatch: Map<String, String> = emptyMap(),
	/** Prefix match predicates: field name -> prefix (case-insensitive). */
	val prefixMatch: Map<String, String> = emptyMap(),
	/**
	 * Set-membership predicates: field name to the values that field may take.
	 *
	 * An empty collection matches nothing, on the same reasoning as [sourceIds]. This exists so a
	 * caller wanting several values of one field -- every classifier kind, say -- can say so in the
	 * query instead of fetching every value and discarding most of them.
	 */
	val anyOf: Map<String, Collection<String>> = emptyMap(),
	/**
	 * Presence predicates: field name -> whether the field must be
	 * non-null (true) or null (false).
	 */
	val presence: Map<String, Boolean> = emptyMap(),
	/** Filter by source ID. */
	val sourceId: String? = null,
	/**
	 * Filter by a set of source IDs. `null` imposes no restriction; an empty collection matches
	 * nothing, which is what makes "scoped to a set that happens to be empty" distinguishable from
	 * "unscoped".
	 *
	 * Implementations must apply this before any [limit], so a scoped query cannot lose matches to
	 * rows that were only going to be discarded.
	 */
	val sourceIds: Collection<String>? = null,
	/**
	 * Filter by key (exact).
	 *
	 * Several sources can hold the same key. A key query returns its matches in ascending source id
	 * order, so with a limit of 1 it yields the one with the smallest source id among the sources in
	 * scope.
	 */
	val key: String? = null,
	/** Maximum number of results. 0 = unlimited (use with care). */
	val limit: Int = 200,
) {
	companion object {
		/** Match everything up to [limit]. */
		val ALL = IndexQuery()

		/** Exact key lookup: the entry with the smallest source id among those holding [key]. */
		fun byKey(key: String) = IndexQuery(key = key, limit = 1)

		/** All entries from a specific source. */
		fun bySource(sourceId: String) = IndexQuery(sourceId = sourceId, limit = 0)
	}
}

/**
 * DSL builder for [IndexQuery].
 */
class IndexQueryBuilder {
	private val exact = mutableMapOf<String, String>()
	private val prefix = mutableMapOf<String, String>()
	private val pres = mutableMapOf<String, Boolean>()
	private val anyOfValues = mutableMapOf<String, Collection<String>>()
	var sourceId: String? = null
	var sourceIds: Collection<String>? = null
	var key: String? = null
	var limit: Int = 200

	/** Exact match on a field. */
	fun eq(
		field: String,
		value: String,
	) {
		exact[field] = value
	}

	/** Prefix match on a field (case-insensitive). */
	fun prefix(
		field: String,
		value: String,
	) {
		prefix[field] = value
	}

	/** Field must hold one of [values]. */
	fun anyOf(
		field: String,
		values: Collection<String>,
	) {
		anyOfValues[field] = values
	}

	/** Field must be non-null. */
	fun exists(field: String) {
		pres[field] = true
	}

	/** Field must be null. */
	fun notExists(field: String) {
		pres[field] = false
	}

	fun build() =
		IndexQuery(
			exactMatch = exact.toMap(),
			prefixMatch = prefix.toMap(),
			anyOf = anyOfValues.toMap(),
			presence = pres.toMap(),
			sourceId = sourceId,
			sourceIds = sourceIds,
			key = key,
			limit = limit,
		)
}

inline fun indexQuery(block: IndexQueryBuilder.() -> Unit): IndexQuery = IndexQueryBuilder().apply(block).build()
