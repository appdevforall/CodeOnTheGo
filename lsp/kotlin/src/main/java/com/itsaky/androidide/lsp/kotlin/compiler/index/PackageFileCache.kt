package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.github.benmanes.caffeine.cache.Caffeine
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataWrite
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataWriteListener
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Caches the files of each package, dropping entries as index writes make them stale.
 *
 * The cache is bounded by the total number of files it lists, so retained memory stays small.
 */
internal class PackageFileCache<F : Any>(
	maxCachedFiles: Long,
	private val pathOf: (F) -> String,
	private val query: (packageFqName: String) -> List<F>,
	executor: Executor = ForkJoinPool.commonPool(),
) : KtFileMetadataWriteListener {
	private val lock = Any()

	/** Bumped by every write, under [lock]. A lookup only caches its result if no write ran meanwhile. */
	private var generation = 0L

	/** A package's files, with their paths converted once so writes only do set lookups. */
	private class Entry<F>(
		val files: List<F>,
		val paths: Set<String>,
	)

	private val cache =
		Caffeine
			.newBuilder()
			.executor(executor)
			.maximumWeight(maxCachedFiles)
			.weigher<String, Entry<F>> { _, entry -> entry.files.size + 1 }
			.build<String, Entry<F>>()

	/** Returns the files declared in [packageFqName], querying only when the answer is not cached. */
	fun filesFor(packageFqName: String): List<F> {
		cache.getIfPresent(packageFqName)?.let { return it.files }
		val observed = synchronized(lock) { generation }
		val files = query(packageFqName)
		val entry = Entry(files, files.mapTo(HashSet(), pathOf))
		synchronized(lock) {
			if (generation == observed) cache.put(packageFqName, entry)
		}
		return files
	}

	override fun onWrite(write: KtFileMetadataWrite) =
		synchronized(lock) {
			generation++
			when (write) {
				is KtFileMetadataWrite.Upserted -> {
					// A live edit re-upserts a file its package already lists; that list is still right.
					invalidateUnlessListed(write.packageFqName, write.filePath)
					invalidateContaining(setOf(write.filePath), except = write.packageFqName)
				}

				is KtFileMetadataWrite.Removed -> {
					invalidateContaining(write.filePaths.toSet())
				}

				KtFileMetadataWrite.Cleared -> {
					cache.invalidateAll()
				}
			}
		}

	private fun invalidateUnlessListed(
		packageFqName: String,
		path: String,
	) {
		val entry = cache.getIfPresent(packageFqName) ?: return
		if (path !in entry.paths) cache.invalidate(packageFqName)
	}

	private fun invalidateContaining(
		paths: Set<String>,
		except: String? = null,
	) {
		val stale =
			cache
				.asMap()
				.filter { (packageFqName, entry) -> packageFqName != except && paths.any { it in entry.paths } }
				.keys
		cache.invalidateAll(stale)
	}

	companion object {
		const val DEFAULT_MAX_CACHED_FILES = 2_000L
	}
}
