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
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_KIND
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
open class JvmSymbolIndex(
	private val backing: Index<JvmSymbol>,
	private val indexer: BackgroundIndexer<JvmSymbol>,
) : FilteredIndex<JvmSymbol>(backing),
	WritableIndex<JvmSymbol> by backing,
	Closeable {
	companion object {
		/** Kind names as the descriptor stores them, for set-membership predicates. */
		private val CLASSIFIER_KIND_NAMES = JvmSymbolKind.CLASSIFIER_KINDS.map { it.name }
		private val CALLABLE_KIND_NAMES = JvmSymbolKind.CALLABLE_KINDS.map { it.name }

		const val DB_NAME_DEFAULT = "jvm_symbol_index.db"
		const val INDEX_NAME_LIBRARY = "jvm-library-cache"

		/**
		 * Storage format version of the SQLite-backed JVM symbol and Kotlin file metadata indexes.
		 *
		 * Bump it whenever the index schema or any scanner's output changes: an install holding a
		 * different version drops its rows and re-indexes every source, which is the only way rows
		 * produced by an older scanner get replaced. [KtFileMetadataIndex] shares it because a
		 * source file's symbols are re-indexed only when its metadata row says so; dropping the
		 * symbols alone would leave files recorded as indexed with no symbols.
		 */
		const val FORMAT_VERSION = 2

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
			val cache =
				SQLiteIndex(
					descriptor = JvmSymbolDescriptor,
					context = context,
					dbName = dbName,
					formatVersion = FORMAT_VERSION,
					name = indexName,
				)

			val indexer = BackgroundIndexer(cache)
			return JvmSymbolIndex(cache, indexer)
		}
	}

	/**
	 * Index a single source. The [provider] returns a [Sequence] that
	 * lazily produces entries - it is consumed on [Dispatchers.IO] by
	 * [Index.insertAll], or [Index.insertSource] when a [fingerprint] is given.
	 *
	 * If [skipIfExists] is true and the source is already indexed (with the
	 * same [fingerprint], when one is given), this is a no-op.
	 *
	 * @param sourceId     Identifies the source.
	 * @param skipIfExists Skip if already indexed.
	 * @param fingerprint  Identifies the source's current content, or `null` to not track it.
	 * @param provider     Lambda returning a [Sequence] of entries.
	 * @return The launched job, or the already-running job for the same fingerprint if one exists.
	 * @see BackgroundIndexer.indexSource
	 */
	fun indexSource(
		sourceId: String,
		skipIfExists: Boolean = true,
		fingerprint: String? = null,
		provider: (sourceId: String) -> Sequence<JvmSymbol>,
	): Job = indexer.indexSource(sourceId, skipIfExists, fingerprint, provider)

	/** Returns the fingerprint [sourceId] was last indexed with, or `null` if it has none. */
	suspend fun sourceFingerprint(sourceId: String): String? = backing.sourceFingerprint(sourceId)

	/**
	 * Find symbols matching the given prefix.
	 *
	 * @param prefix The prefix to search for.
	 * @param limit The result limit.
	 * @see query
	 */
	fun findByPrefix(
		prefix: String,
		limit: Int = 200,
	): Sequence<JvmSymbol> =
		query(
			indexQuery {
				prefix(KEY_NAME, prefix)
				this.limit = limit
			},
		)

	/**
	 * Find symbols having the given [receiver type][receiverTypeFqName].
	 */
	fun findExtensionsFor(
		receiverTypeFqName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> =
		query(
			indexQuery {
				eq(KEY_RECEIVER_TYPE, receiverTypeFqName)
				if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
				this.limit = limit
			},
		)

	/**
	 * Top-level callables declared in [packageName].
	 *
	 * The kind and top-level conditions are part of the query rather than a filter over its result:
	 * a package holds far more members than top-level callables, so filtering afterwards meant
	 * fetching every symbol in the package to keep a handful. Top-level is expressible because the
	 * descriptor stores a null containing class for it.
	 */
	fun findTopLevelCallablesInPackage(
		packageName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> =
		query(
			indexQuery {
				eq(KEY_PACKAGE, packageName)
				anyOf(KEY_KIND, CALLABLE_KIND_NAMES)
				notExists(KEY_CONTAINING_CLASS)
				if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
				this.limit = limit
			},
		)

	/** Classifiers declared in [packageName], filtered by kind in the query for the same reason. */
	fun findClassifiersInPackage(
		packageName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> =
		query(
			indexQuery {
				eq(KEY_PACKAGE, packageName)
				anyOf(KEY_KIND, CLASSIFIER_KIND_NAMES)
				if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
				this.limit = limit
			},
		)

	fun findMembersOf(
		classFqName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Sequence<JvmSymbol> =
		query(
			indexQuery {
				eq(KEY_CONTAINING_CLASS, classFqName)
				if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
				this.limit = limit
			},
		)

	/**
	 * Symbols whose simple name is exactly [name], optionally restricted to [kinds].
	 *
	 * Restricting by kind matters more than it looks: without it an exact-name lookup still returns
	 * every method and field sharing that name, and a caller wanting only classifiers pays for all
	 * of them.
	 */
	fun findBySimpleName(
		name: String,
		limit: Int = 200,
		kinds: Set<JvmSymbolKind>? = null,
	) = query(
		indexQuery {
			eq(KEY_NAME, name)
			if (kinds != null) anyOf(KEY_KIND, kinds.map { it.name })
			this.limit = limit
		},
	)

	suspend fun findByKey(key: String): JvmSymbol? = get(key)

	fun allPackages(): Sequence<String> = distinctValues(KEY_PACKAGE)

	suspend fun awaitIndexing() = indexer.awaitAll()

	override fun close() {
		indexer.close()
		super.close()
	}
}
