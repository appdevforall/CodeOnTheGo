package org.appdevforall.codeonthego.indexing.api

/**
 * Any object that can be stored in an index.
 *
 * The only requirements are a unique key (for deduplication and
 * point lookups) and a source identifier (for bulk operations
 * when the source changes).
 *
 * What constitutes a "key" and "source" depends entirely on
 * the consumer:
 * - For Kotlin symbols: key = FQN, source = JAR path or file path
 * - For Android resources: key = resource ID, source = AAR path
 * - For Python symbols: key = qualified name, source = .py file path
 */
interface Indexable {

    /** Unique identifier within the index. */
    val key: String

    /**
     * Identifies the origin of this entry.
     * All entries sharing a [sourceId] can be removed atomically
     * via [WritableIndex.removeBySource].
     */
    val sourceId: String
}

/**
 * Describes how to index, serialize, and query a specific type.
 *
 * Acts as the bridge between domain objects and the storage layer.
 * A single index instance is parameterized by one descriptor -
 * different data types get different index instances.
 *
 * @param T The domain type being indexed.
 */
interface IndexDescriptor<T : Indexable> {

    /**
     * A unique name for this index type. Used as the table name
     * in persistent storage and the namespace in composite indexes.
     */
    val name: String

    /**
     * The fields that should be queryable.
     * Defines the "schema" for this index type.
     *
     * The persistent layer will create SQL columns and indexes
     * for each declared field.
     */
    val fields: List<IndexField>

    /**
     * Extract the queryable field values from an entry.
     *
     * The returned map's keys must be a subset of [fields]'s names.
     * Null values mean the field is not applicable for this entry
     * (e.g. receiverType is null for a non-extension function).
     */
    fun fieldValues(entry: T): Map<String, String?>

    /**
     * Serialize an entry to bytes for persistent storage.
     *
     * Use whatever format is appropriate - protobuf, JSON,
     * custom binary. Called once on insert; the bytes are
     * stored opaquely.
     */
    fun serialize(entry: T): ByteArray

    /**
     * Deserialize bytes back into an entry.
     * Must be the inverse of [serialize].
     */
    fun deserialize(bytes: ByteArray): T
}

/**
 * Declares a queryable field on an [IndexDescriptor].
 *
 * @param name              The field name (used in queries and as the column name).
 * @param prefixSearchable  Whether this field supports prefix queries
 *                          (e.g. name prefix for completions). Affects how
 *                          the persistent layer creates SQL indexes.
 */
data class IndexField(
    val name: String,
    val prefixSearchable: Boolean = false,
)
