package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import org.appdevforall.codeonthego.indexing.FilteredIndex
import org.appdevforall.codeonthego.indexing.PersistentIndex
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_CONTAINING_CLASS
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_NAME
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_PACKAGE
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_RECEIVER_TYPE
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import java.io.Closeable

/**
 * An index of symbols from external Java libraries (JARs).
 */
class JvmLibrarySymbolIndex private constructor(
	/** Persistent cache — stores every JAR ever indexed. */
	val libraryCache: PersistentIndex<JvmSymbol>,

	/** Filtered view — only shows JARs on the current classpath. */
	val libraryView: FilteredIndex<JvmSymbol>,

	/** Background indexer writing to the cache. */
	val libraryIndexer: BackgroundIndexer<JvmSymbol>,
) : Closeable {

	companion object {

		const val DB_NAME_DEFAULT = "jvm_symbol_index.db"
		const val INDEX_NAME_LIBRARY = "jvm-library-cache"

		fun create(
			context: Context,
			dbName: String = DB_NAME_DEFAULT,
		): JvmLibrarySymbolIndex {
			val cache = PersistentIndex(
				descriptor = JvmSymbolDescriptor,
				context = context,
				dbName = dbName,
				name = INDEX_NAME_LIBRARY,
			)

			val view = FilteredIndex(cache)

			val indexer = BackgroundIndexer(cache)
			return JvmLibrarySymbolIndex(
				libraryCache = cache,
				libraryView = view,
				libraryIndexer = indexer
			)
		}
	}

	/**
	 * Make a library visible in query results.
	 *
	 * If the library is already cached (indexed previously),
	 * this is instant. If not, call [indexLibrary] first.
	 */
	fun activateLibrary(sourceId: String) {
		libraryView.activateSource(sourceId)
	}

	/**
	 * Hide a library from query results.
	 * The cached index data is retained for future reuse.
	 */
	fun deactivateLibrary(sourceId: String) {
		libraryView.deactivateSource(sourceId)
	}

	/**
	 * Replace the entire active library set.
	 *
	 * Typical call after project sync: pass all current classpath
	 * JAR paths. Libraries not in the set become invisible.
	 * Libraries in the set that are already cached become
	 * instantly visible.
	 */
	fun setActiveLibraries(sourceIds: Set<String>) {
		libraryView.setActiveSources(sourceIds)
	}

	/**
	 * Check if a library is already cached (regardless of whether
	 * it's currently active).
	 */
	suspend fun isLibraryCached(sourceId: String): Boolean =
		libraryView.isCached(sourceId)

	/**
	 * Index a library JAR/AAR into the persistent cache.
	 *
	 * This does NOT make the library visible in queries —
	 * call [activateLibrary] after indexing completes.
	 *
	 * Skips if already cached. Call [reindexLibrary] to force.
	 */
	fun indexLibrary(
		sourceId: String,
		provider: (sourceId: String) -> Flow<JvmSymbol>,
	) = libraryIndexer.indexSource(sourceId, skipIfExists = true, provider)

	fun reindexLibrary(
		sourceId: String,
		provider: (sourceId: String) -> Flow<JvmSymbol>,
	) = libraryIndexer.indexSource(sourceId, skipIfExists = false, provider)

	fun findByPrefix(prefix: String, limit: Int = 200): Flow<JvmSymbol> =
		libraryView.query(indexQuery { prefix(KEY_NAME, prefix); this.limit = limit })

	fun findByPrefix(
		prefix: String, kinds: Set<JvmSymbolKind>, limit: Int = 200,
	): Flow<JvmSymbol> =
		libraryView.query(indexQuery { prefix(KEY_NAME, prefix); this.limit = 0 })
			.filter { it.kind in kinds }
			.take(limit)

	fun findExtensionsFor(
		receiverTypeFqName: String, namePrefix: String = "", limit: Int = 200,
	): Flow<JvmSymbol> = libraryView.query(indexQuery {
		eq(KEY_RECEIVER_TYPE, receiverTypeFqName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = limit
	})

	fun findTopLevelCallablesInPackage(
		packageName: String, namePrefix: String = "", limit: Int = 200,
	): Flow<JvmSymbol> = libraryView.query(indexQuery {
		eq(KEY_PACKAGE, packageName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = 0
	}).filter { it.kind.isCallable && it.isTopLevel }.take(limit)

	fun findClassifiersInPackage(
		packageName: String, namePrefix: String = "", limit: Int = 200,
	): Flow<JvmSymbol> = libraryView.query(indexQuery {
		eq(KEY_PACKAGE, packageName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = 0
	}).filter { it.kind.isClassifier }.take(limit)

	fun findMembersOf(
		classFqName: String, namePrefix: String = "", limit: Int = 200,
	): Flow<JvmSymbol> = libraryView.query(indexQuery {
		eq(KEY_CONTAINING_CLASS, classFqName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = limit
	})

	suspend fun findByFqName(fqName: String): JvmSymbol? = libraryView.get(fqName)

	fun allPackages(): Flow<String> = libraryView.distinctValues(KEY_PACKAGE)

	suspend fun awaitLibraryIndexing() = libraryIndexer.awaitAll()

	override fun close() {
		libraryCache.close()
		libraryIndexer.close()
		libraryView.close()
	}
}