package org.appdevforall.codeonthego.indexing.api

/**
 * A query against an index.
 *
 * All predicates are ANDed together. The query is intentionally
 * field-based (not type-specific) so the same query engine works
 * for Kotlin symbols, Android resources, Python declarations, etc.
 */
data class IndexQuery(
    /** Exact match predicates: field name → expected value. */
    val exactMatch: Map<String, String> = emptyMap(),

    /** Prefix match predicates: field name → prefix (case-insensitive). */
    val prefixMatch: Map<String, String> = emptyMap(),

    /**
     * Presence predicates: field name → whether the field must be
     * non-null (true) or null (false).
     */
    val presence: Map<String, Boolean> = emptyMap(),

    /** Filter by source ID. */
    val sourceId: String? = null,

    /** Filter by key (exact). */
    val key: String? = null,

    /** Maximum number of results. 0 = unlimited (use with care). */
    val limit: Int = 200,
) {
    companion object {
        /** Match everything up to [limit]. */
        val ALL = IndexQuery()

        /** Exact key lookup. */
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
    var sourceId: String? = null
    var key: String? = null
    var limit: Int = 200

    /** Exact match on a field. */
    fun eq(field: String, value: String) { exact[field] = value }

    /** Prefix match on a field (case-insensitive). */
    fun prefix(field: String, value: String) { prefix[field] = value }

    /** Field must be non-null. */
    fun exists(field: String) { pres[field] = true }

    /** Field must be null. */
    fun notExists(field: String) { pres[field] = false }

    fun build() = IndexQuery(
        exactMatch = exact.toMap(),
        prefixMatch = prefix.toMap(),
        presence = pres.toMap(),
        sourceId = sourceId,
        key = key,
        limit = limit,
    )
}

inline fun indexQuery(block: IndexQueryBuilder.() -> Unit): IndexQuery =
    IndexQueryBuilder().apply(block).build()
