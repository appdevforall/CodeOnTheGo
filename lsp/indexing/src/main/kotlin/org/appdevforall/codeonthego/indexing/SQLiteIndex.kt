package org.appdevforall.codeonthego.indexing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.slf4j.LoggerFactory
import kotlin.collections.iterator

/**
 * An [Index] backed by SQLite via AndroidX.
 *
 * Creates a table dynamically based on the [IndexDescriptor]:
 * ```
 * CREATE TABLE IF NOT EXISTS {name} (
 *     _key TEXT NOT NULL,
 *     _source_id TEXT NOT NULL,
 *     f_{field1} TEXT,
 *     f_{field1}_lower TEXT,  -- if prefix-searchable
 *     f_{field2} TEXT,
 *     ...
 *     _payload BLOB NOT NULL,
 *     PRIMARY KEY (_source_id, _key)
 * );
 * ```
 *
 * A row is identified by its source and key together, so the same key (a class present in two
 * JARs, say) has one row per source. The primary key leads with `_source_id`, which is what serves
 * source-scoped reads and bulk removal.
 *
 * SQL indexes are created on:
 * - `(_key, _source_id)` (for key lookups, already ordered by source)
 * - Each `f_{field}` (for equality filter)
 * - Each `f_{field}_lower` (for prefix search)
 *
 * When a query has a selective predicate (a key, a prefix, or a match on a
 * [selective][org.appdevforall.codeonthego.indexing.api.IndexField.selective] field), its source
 * scope and its other terms only filter rows and never choose the index. [optimize] collects the
 * planner statistics for everything else.
 *
 * File-backed databases use WAL journal mode: a commit appends to the write-ahead log instead
 * of writing a rollback journal and the main file. In-memory databases have no journal to set.
 * Inserts are batched inside transactions for throughput.
 *
 * [query] and [distinctValues] eagerly collect results and return a
 * [Sequence] backed by a list. The cursor is always closed before
 * returning; callers are responsible for running on an appropriate
 * thread (typically [Dispatchers.IO] via the suspend insert paths).
 *
 * @param T The indexed entry type.
 * @param descriptor Defines fields and serialization.
 * @param context Android context (for database file location).
 * @param dbName Database file name. Pass `null` to create an in-memory database
 *               that is discarded when closed. Different index types can share
 *               a database (each gets its own table) or use separate files.
 * @param formatVersion Version of the stored format: the schema and whatever produced the rows.
 *               It belongs to the database file, so opening a file stored at any other version
 *               discards every table in it; each index sharing the file recreates its own table
 *               when it opens. Indexes sharing a file must therefore pass the same version.
 * @param batchSize Number of rows per INSERT transaction.
 */
class SQLiteIndex<T : Indexable>(
	override val descriptor: IndexDescriptor<T>,
	context: Context,
	dbName: String?,
	formatVersion: Int,
	override val name: String = "sqlite:${descriptor.name}",
	private val batchSize: Int = 500,
) : Index<T> {
	companion object {
		private val log = LoggerFactory.getLogger(SQLiteIndex::class.java)

		/**
		 * Max number of `_source_id` placeholders per batched DELETE.
		 * Kept well under SQLite's default 999 bound-parameter limit.
		 */
		private const val DELETE_CHUNK_SIZE = 900

		/**
		 * Max number of `_source_id` placeholders per source-scoped SELECT, under the same
		 * bound-parameter limit as [DELETE_CHUNK_SIZE]. A scoped query runs one statement per chunk;
		 * chunks are disjoint on `_source_id`, so no row can be returned by two of them.
		 */
		private const val SOURCE_ID_CHUNK_SIZE = 900

		/** Rows [optimize] samples per SQL index, which bounds its cost on a large table. */
		private const val ANALYSIS_LIMIT = 1000

		/** The first SQLite version with `PRAGMA analysis_limit`. */
		private val ANALYSIS_LIMIT_SINCE = listOf(3, 32, 0)

		/**
		 * Whether SQLite [version] (as `sqlite_version()` reports it, e.g. `3.32.2`) supports
		 * `PRAGMA analysis_limit`. Compares the numeric components, so `3.9` is older than `3.32`;
		 * a version that does not parse counts as unsupported.
		 */
		@VisibleForTesting
		internal fun supportsAnalysisLimit(version: String): Boolean {
			val parts = version.split('.').map { it.toIntOrNull() ?: return false }
			for (i in ANALYSIS_LIMIT_SINCE.indices) {
				val part = parts.getOrElse(i) { 0 }
				if (part != ANALYSIS_LIMIT_SINCE[i]) {
					return part > ANALYSIS_LIMIT_SINCE[i]
				}
			}
			return true
		}

		/** Every table in a database except SQLite's and Android's own bookkeeping tables. */
		private const val USER_TABLES_QUERY =
			"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'"
	}

	private val tableName = descriptor.name.replace(Regex("[^a-zA-Z0-9_]"), "_")

	/** One row per source that was indexed with a fingerprint, see [insertSource]. */
	private val sourcesTableName = "${tableName}_sources"

	/** Field column names: `f_{fieldName}`. */
	private val fieldColumns =
		descriptor.fields.associate { field ->
			field.name to "f_${field.name}"
		}

	/** Prefix-searchable fields also get a lowercased `f_{fieldName}_lower` column. */
	private val prefixColumns =
		descriptor.fields
			.filter { it.prefixSearchable }
			.associate { it.name to "f_${it.name}_lower" }

	/** Fields whose value matches may choose the SQL index a query is served by. */
	private val selectiveFields =
		descriptor.fields
			.filter { it.selective }
			.mapTo(HashSet()) { it.name }

	private val mutex = Mutex()

	@Volatile private var closed = false
	private val db: SupportSQLiteDatabase

	init {
		val config =
			SupportSQLiteOpenHelper.Configuration
				.builder(context)
				.name(dbName)
				.callback(
					object : SupportSQLiteOpenHelper.Callback(formatVersion) {
						override fun onCreate(db: SupportSQLiteDatabase) {
							createTable(db)
						}

						override fun onUpgrade(
							db: SupportSQLiteDatabase,
							oldVersion: Int,
							newVersion: Int,
						) {
							rebuild(db)
						}

						override fun onDowngrade(
							db: SupportSQLiteDatabase,
							oldVersion: Int,
							newVersion: Int,
						) {
							rebuild(db)
						}
					},
				).build()

		val helper = FrameworkSQLiteOpenHelperFactory().create(config)
		// androidx disables WAL unless asked, and it only takes effect if set before the first open.
		helper.setWriteAheadLoggingEnabled(true)
		db = helper.writableDatabase

		// Ensure table exists (for shared databases)
		createTable(db)
	}

	override fun query(query: IndexQuery): Sequence<T> =
		runBlocking {
			ifOpen(emptySequence()) {
				val limit = effectiveLimit(query)
				val results = mutableListOf<T>()
				for (chunk in sourceIdChunks(query)) {
					if (results.size >= limit) {
						break
					}

					val (sql, args) = buildSelectQuery(query, chunk, limit - results.size)
					val cursor = db.query(sql, args.toTypedArray())
					cursor.use {
						val payloadIdx = it.getColumnIndexOrThrow("_payload")
						while (it.moveToNext()) {
							results.add(descriptor.deserialize(it.getBlob(payloadIdx)))
						}
					}
				}
				results.asSequence()
			}
		}

	override suspend fun get(key: String): T? =
		withContext(Dispatchers.IO) {
			ifOpen(null) {
				val cursor =
					db.query(
						"SELECT _payload FROM $tableName WHERE _key = ? ORDER BY _source_id LIMIT 1",
						arrayOf(key),
					)
				cursor.use {
					if (it.moveToFirst()) {
						descriptor.deserialize(it.getBlob(0))
					} else {
						null
					}
				}
			}
		}

	override suspend fun containsSource(sourceId: String): Boolean =
		withContext(Dispatchers.IO) {
			ifOpen(false) {
				val cursor =
					db.query(
						"SELECT 1 FROM $tableName WHERE _source_id = ? LIMIT 1",
						arrayOf(sourceId),
					)
				cursor.use { it.moveToFirst() }
			}
		}

	override fun distinctValues(
		fieldName: String,
		query: IndexQuery,
	): Sequence<String> =
		runBlocking {
			ifOpen(emptySequence()) {
				val col =
					fieldColumns[fieldName]
						?: throw IllegalArgumentException("Unknown field: $fieldName")
				val limit = effectiveLimit(query)

				/*
				 * Deduplicated here as well as in SQL: DISTINCT only applies within one statement, and
				 * chunked source ids mean one statement per chunk. The same package name legitimately
				 * appears in many JARs, so without this the caller would see it once per chunk.
				 */
				val values = LinkedHashSet<String>()
				for (chunk in sourceIdChunks(query)) {
					if (values.size >= limit) {
						break
					}

					val (sql, args) = buildDistinctQuery(col, query, chunk, limit - values.size)
					db.query(sql, args.toTypedArray()).use {
						while (it.moveToNext()) {
							values.add(it.getString(0))
						}
					}
				}
				values.asSequence()
			}
		}

	override suspend fun insertAll(entries: Sequence<T>) = insertBatched(entries, fingerprint = null)

	override suspend fun insertSource(
		sourceId: String,
		fingerprint: String,
		entries: Sequence<T>,
	) = insertBatched(entries, SourceFingerprint(sourceId, fingerprint))

	override suspend fun sourceFingerprint(sourceId: String): String? =
		withContext(Dispatchers.IO) {
			ifOpen(null) {
				db
					.query(
						"SELECT _fingerprint FROM $sourcesTableName WHERE _source_id = ?",
						arrayOf(sourceId),
					).use { if (it.moveToFirst()) it.getString(0) else null }
			}
		}

	/**
	 * Inserts [entries] in transactions of [batchSize] rows, taking the lock per batch so reads can
	 * interleave with a long insert. A [fingerprint] goes into the last transaction, which runs
	 * even when there are no entries, so an empty source is still recorded as indexed.
	 */
	private suspend fun insertBatched(
		entries: Sequence<T>,
		fingerprint: SourceFingerprint?,
	) = withContext(Dispatchers.IO) {
		val batch = mutableListOf<T>()
		for (entry in entries) {
			batch.add(entry)
			if (batch.size >= batchSize) {
				ifOpen { insertBatchLocked(batch, fingerprint = null) }
				batch.clear()
			}
		}
		if (batch.isNotEmpty() || fingerprint != null) {
			ifOpen { insertBatchLocked(batch, fingerprint) }
		}
	}

	override suspend fun insert(entry: T) =
		withContext(Dispatchers.IO) {
			ifOpen { insertBatchLocked(listOf(entry), fingerprint = null) }
		}

	override suspend fun removeBySource(sourceId: String) = removeBySources(listOf(sourceId))

	/**
	 * Remove every row whose `_source_id` is in [sourceIds], with those sources' fingerprints,
	 * using a single SQLite transaction. The ids are split into chunks of at most
	 * [DELETE_CHUNK_SIZE] so each `DELETE ... IN (?, ?, ...)` stays within SQLite's
	 * bound-parameter limit; all chunks run inside the one transaction, so the batch commits
	 * atomically (an empty [sourceIds] is a no-op and opens no transaction).
	 *
	 * @param sourceIds Source ids whose rows should be deleted.
	 */
	override suspend fun removeBySources(sourceIds: Collection<String>) =
		withContext(Dispatchers.IO) {
			if (sourceIds.isEmpty()) return@withContext
			ifOpen {
				db.beginTransaction()
				try {
					for (chunk in sourceIds.chunked(DELETE_CHUNK_SIZE)) {
						val placeholders = chunk.joinToString(",") { "?" }
						db.execSQL(
							"DELETE FROM $tableName WHERE _source_id IN ($placeholders)",
							chunk.toTypedArray(),
						)
						db.execSQL(
							"DELETE FROM $sourcesTableName WHERE _source_id IN ($placeholders)",
							chunk.toTypedArray(),
						)
					}
					db.setTransactionSuccessful()
				} finally {
					db.endTransaction()
				}
			}
		}

	override suspend fun clear() =
		withContext(Dispatchers.IO) {
			ifOpen {
				db.beginTransaction()
				try {
					db.execSQL("DELETE FROM $tableName")
					db.execSQL("DELETE FROM $sourcesTableName")
					db.setTransactionSuccessful()
				} finally {
					db.endTransaction()
				}
			}
		}

	/**
	 * Collects the statistics SQLite's query planner uses to choose between this table's indexes.
	 *
	 * This runs `ANALYZE` rather than `PRAGMA optimize`, which decides per table whether to analyze
	 * and, in older SQLite versions, only considers tables queried on the same connection: reads run
	 * on the pool's other connections, so right after indexing it could analyze nothing. The
	 * analysis limit samples each index instead of reading all of it. Both statements share one
	 * transaction so they run on the same connection.
	 *
	 * On SQLite older than 3.32 (API 30 and below) this does nothing. Those versions ignore the
	 * unknown analysis limit and read every index in full, which on a large table holds the lock
	 * that queries wait on for too long. Queries there rely on their selective predicates alone.
	 */
	override suspend fun optimize() =
		withContext(Dispatchers.IO) {
			ifOpen {
				val version = db.query("SELECT sqlite_version()").use { if (it.moveToFirst()) it.getString(0) else "" }
				if (!supportsAnalysisLimit(version)) {
					log.debug("Not analyzing {}: SQLite {} has no analysis limit", tableName, version)
					return@ifOpen
				}
				db.beginTransaction()
				try {
					db.query("PRAGMA analysis_limit = $ANALYSIS_LIMIT").close()
					db.execSQL("ANALYZE $tableName")
					db.setTransactionSuccessful()
				} finally {
					db.endTransaction()
				}
			}
		}

	override fun close() {
		if (Looper.getMainLooper() == Looper.myLooper()) {
			log.warn(
				"SQLiteIndex.close() called on the main thread; waiting on mutex and closing db may block and cause ANR",
			)
		}
		runBlocking {
			mutex.withLock {
				if (closed) return@withLock
				closed = true
				db.close()
			}
		}
	}

	private suspend inline fun <R> ifOpen(
		default: R,
		crossinline block: () -> R,
	): R = mutex.withLock { if (closed) default else block() }

	private suspend inline fun ifOpen(crossinline block: () -> Unit) = mutex.withLock { if (!closed) block() }

	suspend fun size(): Int =
		withContext(Dispatchers.IO) {
			ifOpen(0) {
				val cursor = db.query("SELECT COUNT(*) FROM $tableName")
				cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
			}
		}

	/**
	 * Discards every table in [db] and recreates this index's own.
	 *
	 * The index is a cache of what the scanners produce, so a format change is handled by
	 * rebuilding rather than migrating: rows written by an older scanner are wrong, not just
	 * differently shaped.
	 */
	private fun rebuild(db: SupportSQLiteDatabase) {
		val tables =
			db.query(USER_TABLES_QUERY).use {
				buildList { while (it.moveToNext()) add(it.getString(0)) }
			}
		for (table in tables) {
			db.execSQL("DROP TABLE IF EXISTS \"$table\"")
		}
		createTable(db)
	}

	private fun createTable(db: SupportSQLiteDatabase) {
		val columns =
			buildString {
				append("_key TEXT NOT NULL, ")
				append("_source_id TEXT NOT NULL, ")

				for (field in descriptor.fields) {
					val col = fieldColumns[field.name]!!
					append("$col TEXT, ")

					if (field.prefixSearchable) {
						val lowerCol = prefixColumns[field.name]!!
						append("$lowerCol TEXT, ")
					}
				}

				append("_payload BLOB NOT NULL, ")
				append("PRIMARY KEY (_source_id, _key)")
			}

		db.execSQL("CREATE TABLE IF NOT EXISTS $tableName ($columns)")
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS $sourcesTableName (_source_id TEXT NOT NULL PRIMARY KEY, _fingerprint TEXT NOT NULL)",
		)

		db.execSQL(
			"CREATE INDEX IF NOT EXISTS idx_${tableName}_key ON $tableName(_key, _source_id)",
		)

		for (field in descriptor.fields) {
			val col = fieldColumns[field.name]!!
			db.execSQL(
				"CREATE INDEX IF NOT EXISTS idx_${tableName}_$col ON $tableName($col)",
			)

			if (field.prefixSearchable) {
				val lowerCol = prefixColumns[field.name]!!
				db.execSQL(
					"CREATE INDEX IF NOT EXISTS idx_${tableName}_$lowerCol ON $tableName($lowerCol)",
				)
			}
		}
	}

	private fun insertBatchLocked(
		entries: List<T>,
		fingerprint: SourceFingerprint?,
	) {
		db.beginTransaction()
		try {
			for (entry in entries) {
				val cv =
					ContentValues().apply {
						put("_key", entry.key)
						put("_source_id", entry.sourceId)

						val fields = descriptor.fieldValues(entry)
						for ((fieldName, value) in fields) {
							val col = fieldColumns[fieldName] ?: continue
							put(col, value)

							val lowerCol = prefixColumns[fieldName]
							if (lowerCol != null) {
								put(lowerCol, value?.lowercase())
							}
						}

						put("_payload", descriptor.serialize(entry))
					}

				db.insert(
					tableName,
					SQLiteDatabase.CONFLICT_REPLACE,
					cv,
				)
			}
			if (fingerprint != null) {
				val cv =
					ContentValues().apply {
						put("_source_id", fingerprint.sourceId)
						put("_fingerprint", fingerprint.value)
					}
				db.insert(sourcesTableName, SQLiteDatabase.CONFLICT_REPLACE, cv)
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
	}

	private data class SourceFingerprint(
		val sourceId: String,
		val value: String,
	)

	private data class SqlQuery(
		val sql: String,
		val args: List<String>,
	)

	private fun effectiveLimit(query: IndexQuery) = if (query.limit > 0) query.limit else Int.MAX_VALUE

	/**
	 * The smallest string greater than every string starting with [prefix], or `null` when no such
	 * bound exists because [prefix] ends in the highest representable characters.
	 */
	private fun exclusiveUpperBound(prefix: String): String? {
		for (i in prefix.length - 1 downTo 0) {
			val c = prefix[i]
			if (c != Char.MAX_VALUE) {
				return prefix.substring(0, i) + (c + 1)
			}
		}
		return null
	}

	/** Escapes the LIKE metacharacters in [literal] so it matches only itself. */
	private fun escapeLikeLiteral(literal: String) =
		literal
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_")

	/**
	 * Splits [IndexQuery.sourceIds] into groups small enough for one `IN (...)` clause.
	 *
	 * Returns a single `null` chunk when the query is unscoped, and no chunks at all when it is
	 * scoped to an empty set -- the caller then runs no statement and yields nothing, which is the
	 * difference between "any source" and "none of them".
	 *
	 * Chunks come in ascending source id order. Together with the per-statement `ORDER BY` on key
	 * queries, that is what makes a limited key lookup return the smallest source id overall rather
	 * than the smallest within whichever chunk happened to run first.
	 */
	private fun sourceIdChunks(query: IndexQuery): List<List<String>?> {
		val sourceIds = query.sourceIds ?: return listOf(null)
		if (sourceIds.isEmpty()) {
			return emptyList()
		}
		return sourceIds.toSortedSet().chunked(SOURCE_ID_CHUNK_SIZE)
	}

	private fun buildSelectQuery(
		query: IndexQuery,
		sourceIdChunk: List<String>?,
		limit: Int,
	): SqlQuery {
		val (where, args) = buildWhereClause(query, sourceIdChunk)
		val sql =
			buildString {
				append("SELECT _payload FROM $tableName")
				if (where.isNotEmpty()) {
					append(" WHERE ")
					append(where)
				}
				if (query.key != null) {
					append(" ORDER BY _source_id")
				}
				if (limit != Int.MAX_VALUE) {
					append(" LIMIT $limit")
				}
			}

		return SqlQuery(sql, args)
	}

	private fun buildDistinctQuery(
		column: String,
		query: IndexQuery,
		sourceIdChunk: List<String>?,
		limit: Int,
	): SqlQuery {
		val (where, args) = buildWhereClause(query, sourceIdChunk)
		val filter = filterOnlyMarker(query)
		val sql =
			buildString {
				append("SELECT DISTINCT $column FROM $tableName WHERE $filter$column IS NOT NULL")
				if (where.isNotEmpty()) {
					append(" AND ")
					append(where)
				}
				if (limit != Int.MAX_VALUE) {
					append(" LIMIT $limit")
				}
			}
		return SqlQuery(sql, args)
	}

	/** Returns the plan of the first statement [query] runs, one plan row per line. */
	@VisibleForTesting
	internal fun explainQuery(query: IndexQuery): String {
		val select = buildSelectQuery(query, firstSourceIdChunk(query), effectiveLimit(query))
		return explain(select)
	}

	/** Returns the plan of the first statement [distinctValues] runs for [fieldName] and [query]. */
	@VisibleForTesting
	internal fun explainDistinctValues(
		fieldName: String,
		query: IndexQuery,
	): String {
		val col = fieldColumns[fieldName] ?: throw IllegalArgumentException("Unknown field: $fieldName")
		return explain(buildDistinctQuery(col, query, firstSourceIdChunk(query), effectiveLimit(query)))
	}

	private fun firstSourceIdChunk(query: IndexQuery): List<String>? {
		val chunks = sourceIdChunks(query)
		require(chunks.isNotEmpty()) { "A query scoped to no sources runs no statement" }
		return chunks.first()
	}

	private fun explain(query: SqlQuery): String =
		runBlocking {
			ifOpen("") {
				db.query("EXPLAIN QUERY PLAN ${query.sql}", query.args.toTypedArray()).use { cursor ->
					val detail = cursor.getColumnIndexOrThrow("detail")
					buildList { while (cursor.moveToNext()) add(cursor.getString(detail)) }.joinToString("\n")
				}
			}
		}

	/**
	 * Whether [query] has a predicate its own SQL index narrows to few rows: a key, a non-empty
	 * prefix, or a value match on a selective field.
	 */
	private fun hasSelectivePredicate(query: IndexQuery): Boolean =
		query.key != null ||
			query.exactMatch.keys.any { it in selectiveFields } ||
			query.anyOf.any { (field, values) -> field in selectiveFields && values.isNotEmpty() } ||
			query.prefixMatch.any { (field, prefix) -> field in fieldColumns && prefix.isNotEmpty() }

	/**
	 * The unary `+` that keeps a term out of SQL index selection when [query] has a selective
	 * predicate, and nothing otherwise. The term is still evaluated against every candidate row.
	 *
	 * Without statistics SQLite rates an `IN` list or an equality on any indexed column as good as a
	 * range, so a scope over hundreds of sources, or a kind, would otherwise choose the index and
	 * leave the selective predicate to be checked row by row. A query with no selective predicate
	 * keeps every term eligible, so its scope can still be served by the primary key.
	 *
	 * The trade-off: any non-empty prefix wins over the scope, even a one-character prefix under a
	 * single-source scope, where the scope would be narrower. Production scopes span hundreds of
	 * sources, so the prefix is the right choice for the queries that matter.
	 */
	private fun filterOnlyMarker(query: IndexQuery) = if (hasSelectivePredicate(query)) "+" else ""

	/**
	 * Builds the shared `WHERE` body for [query], restricted to [sourceIdChunk] when the query is
	 * source-scoped. Returns the clause without the `WHERE` keyword so both the row select and the
	 * distinct projection can splice it in.
	 */
	private fun buildWhereClause(
		query: IndexQuery,
		sourceIdChunk: List<String>?,
	): Pair<String, List<String>> {
		val where = StringBuilder()
		val args = mutableListOf<String>()

		fun and(
			clause: String,
			vararg values: String,
		) {
			if (where.isNotEmpty()) where.append(" AND ")
			where.append(clause)
			args.addAll(values)
		}

		val filter = filterOnlyMarker(query)

		fun filterUnlessSelective(field: String) = if (field in selectiveFields) "" else filter

		query.key?.let { and("_key = ?", it) }
		query.sourceId?.let { and("${filter}_source_id = ?", it) }

		if (sourceIdChunk != null) {
			val placeholders = sourceIdChunk.joinToString(",") { "?" }
			and("${filter}_source_id IN ($placeholders)", *sourceIdChunk.toTypedArray())
		}

		for ((field, value) in query.exactMatch) {
			val col = fieldColumns[field] ?: continue
			and("${filterUnlessSelective(field)}$col = ?", value)
		}

		for ((field, values) in query.anyOf) {
			val col = fieldColumns[field] ?: continue
			if (values.isEmpty()) {
				// Scoped to nothing, as opposed to unscoped: no row can satisfy it.
				and("0 = 1")
				continue
			}
			val distinct = values.distinct()
			val placeholders = distinct.joinToString(",") { "?" }
			and("${filterUnlessSelective(field)}$col IN ($placeholders)", *distinct.toTypedArray())
		}

		for ((field, prefix) in query.prefixMatch) {
			val lowerCol = prefixColumns[field]
			// Prefix-searchable fields match case-insensitively through their pre-lowercased column;
			// everything else matches the stored value as-is.
			val col = lowerCol ?: fieldColumns[field] ?: continue
			val value = if (lowerCol != null) prefix.lowercase() else prefix

			if (value.isEmpty()) {
				// An empty prefix means "has a value", which is what `LIKE '%'` used to express.
				and("$filter$col IS NOT NULL")
				continue
			}

			/*
			 * The range bounds are what make this use the column's index: SQLite only optimises LIKE
			 * into a range scan when case_sensitive_like is on or the column collates NOCASE, and
			 * neither holds here, so a bare LIKE scans the whole table. The escaped LIKE stays as the
			 * semantic guard -- it is what rejects a literal '_' or '%' in the prefix, which are valid
			 * identifier characters that an unescaped pattern would treat as wildcards.
			 */
			val upperBound = exclusiveUpperBound(value)
			if (upperBound != null) {
				and("$col >= ? AND $col < ?", value, upperBound)
			} else {
				and("$col >= ?", value)
			}
			and("$col LIKE ? ESCAPE '\\'", "${escapeLikeLiteral(value)}%")
		}

		for ((field, mustExist) in query.presence) {
			val col = fieldColumns[field] ?: continue
			if (mustExist) {
				and("$filter$col IS NOT NULL")
			} else {
				and("$filter$col IS NULL")
			}
		}

		return where.toString() to args
	}
}
