package org.appdevforall.codeonthego.indexing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.Indexable
import kotlin.collections.iterator

/**
 * A persistent [Index] backed by SQLite via AndroidX.
 *
 * Creates a table dynamically based on the [IndexDescriptor]:
 * ```
 * CREATE TABLE IF NOT EXISTS {name} (
 *     _key TEXT PRIMARY KEY,
 *     _source_id TEXT NOT NULL,
 *     f_{field1} TEXT,
 *     f_{field1}_lower TEXT,  -- if prefix-searchable
 *     f_{field2} TEXT,
 *     ...
 *     _payload BLOB NOT NULL
 * );
 * ```
 *
 * SQL indexes are created on:
 * - `_source_id` (for bulk removal)
 * - Each `f_{field}` (for equality filter)
 * - Each `f_{field}_lower` (for prefix search via `LIKE 'prefix%'`)
 *
 * Uses WAL journal mode for concurrent read/write performance.
 * Inserts are batched inside transactions for throughput.
 *
 * @param T The indexed entry type.
 * @param descriptor Defines fields and serialization.
 * @param context Android context (for database file location).
 * @param dbName Database file name. Different index types can share
 *               a database (each gets its own table) or use separate files.
 * @param batchSize Number of rows per INSERT transaction.
 */
class PersistentIndex<T : Indexable>(
    override val descriptor: IndexDescriptor<T>,
    context: Context,
    dbName: String,
    override val name: String = "persistent:${descriptor.name}",
    private val batchSize: Int = 500,
) : Index<T> {

    private val tableName = descriptor.name.replace(Regex("[^a-zA-Z0-9_]"), "_")

    // Field column names: "f_{fieldName}"
    private val fieldColumns = descriptor.fields.associate { field ->
        field.name to "f_${field.name}"
    }

    // Prefix-searchable fields also get a "_lower" column
    private val prefixColumns = descriptor.fields
        .filter { it.prefixSearchable }
        .associate { it.name to "f_${it.name}_lower" }

    private val db: SupportSQLiteDatabase

    init {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createTable(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int,
                ) {
					// TODO: Add migration support
                    db.execSQL("DROP TABLE IF EXISTS $tableName")
                    createTable(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                }
            })
            .build()

        db = FrameworkSQLiteOpenHelperFactory()
            .create(config)
            .writableDatabase

        // Ensure table exists (for shared databases)
        createTable(db)
    }

    override fun query(query: IndexQuery): Flow<T> = flow {
        val (sql, args) = buildSelectQuery(query)
        val cursor = db.query(sql, args.toTypedArray())

        cursor.use {
            val payloadIdx = it.getColumnIndexOrThrow("_payload")
            while (it.moveToNext()) {
                val bytes = it.getBlob(payloadIdx)
                emit(descriptor.deserialize(bytes))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun get(key: String): T? = withContext(Dispatchers.IO) {
        val cursor = db.query(
            "SELECT _payload FROM $tableName WHERE _key = ? LIMIT 1",
            arrayOf(key),
        )
        cursor.use {
            if (it.moveToFirst()) {
                descriptor.deserialize(it.getBlob(0))
            } else null
        }
    }

    override suspend fun containsSource(sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            val cursor = db.query(
                "SELECT 1 FROM $tableName WHERE _source_id = ? LIMIT 1",
                arrayOf(sourceId),
            )
            cursor.use { it.moveToFirst() }
        }

    override fun distinctValues(fieldName: String): Flow<String> = flow {
        val col = fieldColumns[fieldName]
            ?: throw IllegalArgumentException("Unknown field: $fieldName")

        val cursor = db.query("SELECT DISTINCT $col FROM $tableName WHERE $col IS NOT NULL")
        cursor.use {
            val idx = 0
            while (it.moveToNext()) {
                emit(it.getString(idx))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming insert from a [Flow].
     *
     * Collects entries from the flow and inserts them in batched
     * transactions. Each batch is a single SQLite transaction -
     * this is orders of magnitude faster than one transaction per row.
     *
     * The flow is collected on [Dispatchers.IO].
     */
    override suspend fun insert(entries: Flow<T>) = withContext(Dispatchers.IO) {
        val batch = mutableListOf<T>()
        entries.collect { entry ->
            batch.add(entry)
            if (batch.size >= batchSize) {
                insertBatch(batch)
                batch.clear()
            }
        }
        // Flush remaining
        if (batch.isNotEmpty()) {
            insertBatch(batch)
        }
    }

    override suspend fun insertAll(entries: Sequence<T>) = withContext(Dispatchers.IO) {
        val batch = mutableListOf<T>()
        for (entry in entries) {
            batch.add(entry)
            if (batch.size >= batchSize) {
                insertBatch(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            insertBatch(batch)
        }
    }

    override suspend fun insert(entry: T) = withContext(Dispatchers.IO) {
        insertBatch(listOf(entry))
    }

    override suspend fun removeBySource(sourceId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM $tableName WHERE _source_id = ?", arrayOf(sourceId))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM $tableName")
    }

    override fun close() {
        db.close()
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        val cursor = db.query("SELECT COUNT(*) FROM $tableName")
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun createTable(db: SupportSQLiteDatabase) {
        val columns = buildString {
            append("_key TEXT PRIMARY KEY, ")
            append("_source_id TEXT NOT NULL, ")

            for (field in descriptor.fields) {
                val col = fieldColumns[field.name]!!
                append("$col TEXT, ")

                if (field.prefixSearchable) {
                    val lowerCol = prefixColumns[field.name]!!
                    append("$lowerCol TEXT, ")
                }
            }

            append("_payload BLOB NOT NULL")
        }

        db.execSQL("CREATE TABLE IF NOT EXISTS $tableName ($columns)")

        // Indexes
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_${tableName}_source ON $tableName(_source_id)"
        )

        for (field in descriptor.fields) {
            val col = fieldColumns[field.name]!!
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_${tableName}_$col ON $tableName($col)"
            )

            if (field.prefixSearchable) {
                val lowerCol = prefixColumns[field.name]!!
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_${tableName}_$lowerCol ON $tableName($lowerCol)"
                )
            }
        }
    }

    private fun insertBatch(entries: List<T>) {
        db.beginTransaction()
        try {
            for (entry in entries) {
                val cv = ContentValues().apply {
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

    private data class SqlQuery(val sql: String, val args: List<String>)

    private fun buildSelectQuery(query: IndexQuery): SqlQuery {
        val where = StringBuilder()
        val args = mutableListOf<String>()

        fun and(clause: String, vararg values: String) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append(clause)
            args.addAll(values)
        }

        query.key?.let { and("_key = ?", it) }
        query.sourceId?.let { and("_source_id = ?", it) }

        for ((field, value) in query.exactMatch) {
            val col = fieldColumns[field] ?: continue
            and("$col = ?", value)
        }

        for ((field, prefix) in query.prefixMatch) {
            val lowerCol = prefixColumns[field]
            if (lowerCol != null) {
                // Use the pre-lowercased column for index-friendly LIKE
                and("$lowerCol LIKE ?", "${prefix.lowercase()}%")
            } else {
                // Fallback: case-sensitive prefix on the regular column
                val col = fieldColumns[field] ?: continue
                and("$col LIKE ?", "$prefix%")
            }
        }

        for ((field, mustExist) in query.presence) {
            val col = fieldColumns[field] ?: continue
            if (mustExist) {
                and("$col IS NOT NULL")
            } else {
                and("$col IS NULL")
            }
        }

        val sql = buildString {
            append("SELECT _payload FROM $tableName")
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where)
            }
            if (query.limit > 0) {
                append(" LIMIT ${query.limit}")
            }
        }

        return SqlQuery(sql, args)
    }
}
