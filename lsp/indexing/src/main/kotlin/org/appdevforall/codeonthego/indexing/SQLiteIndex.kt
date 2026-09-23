package org.appdevforall.codeonthego.indexing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
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
 * Uses WAL journal mode for concurrent read/write performance.
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

		/** Every table in a database except SQLite's and Android's own bookkeeping tables. */
		private const val USER_TABLES_QUERY =
			"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'"
	}

	private val tableName = descriptor.name.replace(Regex("[^a-zA-Z0-9_]"), "_")

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

		db =
			FrameworkSQLiteOpenHelperFactory()
				.create(config)
				.writableDatabase

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

					val (where, args) = buildWhereClause(query, chunk)
					val sql =
						buildString {
							append("SELECT DISTINCT $col FROM $tableName WHERE $col IS NOT NULL")
							if (where.isNotEmpty()) {
								append(" AND ")
								append(where)
							}
							if (limit != Int.MAX_VALUE) {
								append(" LIMIT ${limit - values.size}")
							}
						}

					db.query(sql, args.toTypedArray()).use {
						while (it.moveToNext()) {
							values.add(it.getString(0))
						}
					}
				}
				values.asSequence()
			}
		}

	override suspend fun insertAll(entries: Sequence<T>) =
		withContext(Dispatchers.IO) {
			val batch = mutableListOf<T>()
			for (entry in entries) {
				batch.add(entry)
				if (batch.size >= batchSize) {
					ifOpen { insertBatchLocked(batch) }
					batch.clear()
				}
			}
			if (batch.isNotEmpty()) {
				ifOpen { insertBatchLocked(batch) }
			}
		}

	override suspend fun insert(entry: T) =
		withContext(Dispatchers.IO) {
			ifOpen { insertBatchLocked(listOf(entry)) }
		}

	override suspend fun removeBySource(sourceId: String) =
		withContext(Dispatchers.IO) {
			ifOpen { db.execSQL("DELETE FROM $tableName WHERE _source_id = ?", arrayOf(sourceId)) }
		}

	/**
	 * Remove every row whose `_source_id` is in [sourceIds] using a single SQLite
	 * transaction. The ids are split into chunks of at most [DELETE_CHUNK_SIZE] so
	 * each `DELETE ... IN (?, ?, ...)` stays within SQLite's bound-parameter limit;
	 * all chunks run inside the one transaction, so the batch commits atomically
	 * (an empty [sourceIds] is a no-op and opens no transaction).
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
					}
					db.setTransactionSuccessful()
				} finally {
					db.endTransaction()
				}
			}
		}

	override suspend fun clear() =
		withContext(Dispatchers.IO) {
			ifOpen { db.execSQL("DELETE FROM $tableName") }
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

	private fun insertBatchLocked(entries: List<T>) {
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
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
	}

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

		query.key?.let { and("_key = ?", it) }
		query.sourceId?.let { and("_source_id = ?", it) }

		if (sourceIdChunk != null) {
			val placeholders = sourceIdChunk.joinToString(",") { "?" }
			and("_source_id IN ($placeholders)", *sourceIdChunk.toTypedArray())
		}

		for ((field, value) in query.exactMatch) {
			val col = fieldColumns[field] ?: continue
			and("$col = ?", value)
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
			and("$col IN ($placeholders)", *distinct.toTypedArray())
		}

		for ((field, prefix) in query.prefixMatch) {
			val lowerCol = prefixColumns[field]
			// Prefix-searchable fields match case-insensitively through their pre-lowercased column;
			// everything else matches the stored value as-is.
			val col = lowerCol ?: fieldColumns[field] ?: continue
			val value = if (lowerCol != null) prefix.lowercase() else prefix

			if (value.isEmpty()) {
				// An empty prefix means "has a value", which is what `LIKE '%'` used to express.
				and("$col IS NOT NULL")
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
				and("$col IS NOT NULL")
			} else {
				and("$col IS NULL")
			}
		}

		return where.toString() to args
	}
}
