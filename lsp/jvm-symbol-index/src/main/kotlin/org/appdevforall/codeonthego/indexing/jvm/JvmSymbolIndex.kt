package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.appdevforall.codeonthego.indexing.FilteredIndex
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.WritableIndex
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_CONTAINING_CLASS
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_NAME
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_PACKAGE
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_RECEIVER_TYPE
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex.Companion.DB_NAME_DEFAULT
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex.Companion.INDEX_NAME_LIBRARY
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import java.io.Closeable

/**
 * An index of symbols from JVM source and binary files.
 */
class JvmSymbolIndex(
	private val backing: Index<JvmSymbol>,
	private val indexer: BackgroundIndexer<JvmSymbol>,
) : FilteredIndex<JvmSymbol>(backing), WritableIndex<JvmSymbol> by backing, Closeable {

	companion object {

		const val DB_NAME_DEFAULT = "jvm_symbol_index.db"
		const val INDEX_NAME_LIBRARY = "jvm-library-cache"

		/**
		 * Create (or get) a JVM symbol index backed by SQLite.
		 *
		 * @param context The context to use for accessing the SQLite database.
		 * @param dbName The name of the database. Defaults to [DB_NAME_DEFAULT].
		 * @param indexName The name of the index. Defaults to [INDEX_NAME_LIBRARY].
		 */
		fun createSqliteIndex(
			context: Context,
			dbName: String,
			indexName: String,
		): JvmSymbolIndex {
			val cache = SQLiteIndex(
				descriptor = JvmSymbolDescriptor,
				context = context,
				dbName = dbName,
				name = indexName,
			)

			val indexer = BackgroundIndexer(cache)
			return JvmSymbolIndex(cache, indexer)
		}
	}

	/**
	 * Index a single source. The [provider] returns a [Sequence] that
	 * lazily produces entries — it is consumed on [Dispatchers.IO] by
	 * [Index.insertAll].
	 *
	 * If [skipIfExists] is true and the source is already indexed,
	 * this is a no-op.
	 *
	 * @param sourceId     Identifies the source.
	 * @param skipIfExists Skip if already indexed.
	 * @param provider     Lambda returning a [Sequence] of entries.
	 * @return The launched job.
	 */
	fun indexSource(
		sourceId: String,
		skipIfExists: Boolean = true,
		provider: (sourceId: String) -> Sequence<JvmSymbol>,
	): Job = indexer.indexSource(sourceId, skipIfExists, provider)

	/**
	 * Find symbols matching the given prefix.
	 *
	 * @param prefix The prefix to search for.
	 * @param limit The result limit.
	 * @see query
	 */
	fun findByPrefix(prefix: String, limit: Int = 200): Sequence<JvmSymbol> =
		query(indexQuery { prefix(KEY_NAME, prefix); this.limit = limit })

	/**
	 * Find symbols having the given [receiver type][receiverTypeFqName].
	 */
	fun findExtensionsFor(
		receiverTypeFqName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> = query(indexQuery {
		eq(KEY_RECEIVER_TYPE, receiverTypeFqName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = limit
	})

	fun findTopLevelCallablesInPackage(
		packageName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> = query(indexQuery {
		eq(KEY_PACKAGE, packageName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = 0
	}).filter { it.kind.isCallable && it.isTopLevel }.take(limit)

	fun findClassifiersInPackage(
		packageName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> = query(indexQuery {
		eq(KEY_PACKAGE, packageName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = 0
	}).filter { it.kind.isClassifier }.take(limit)

	fun findMembersOf(
		classFqName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> = query(indexQuery {
		eq(KEY_CONTAINING_CLASS, classFqName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = limit
	})

	fun findBySimpleName(name: String, limit: Int = 200) =
		query(indexQuery {
			eq(KEY_NAME, name)
			this.limit = limit
		})

	suspend fun findByKey(key: String): JvmSymbol? = get(key)

	fun allPackages(): Sequence<String> = distinctValues(KEY_PACKAGE)

	suspend fun awaitIndexing() = indexer.awaitAll()

	override fun close() {
		super.close()
		if (backing is AutoCloseable) {
			backing.close()
		}

		indexer.close()
	}
}
