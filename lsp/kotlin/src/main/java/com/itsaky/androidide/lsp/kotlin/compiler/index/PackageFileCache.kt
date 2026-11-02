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

	// Bumped by every write, under [lock]. A lookup only caches its result if no write ran meanwhile.
	private var generation = 0L

	private val cache =
		Caffeine
			.newBuilder()
			.executor(executor)
			.maximumWeight(maxCachedFiles)
			.weigher<String, List<F>> { _, files -> files.size + 1 }
			.build<String, List<F>>()

	/** Returns the files declared in [packageFqName], querying only when the answer is not cached. */
	fun filesFor(packageFqName: String): List<F> {
		cache.getIfPresent(packageFqName)?.let { return it }
		val observed = synchronized(lock) { generation }
		val files = query(packageFqName)
		synchronized(lock) {
			if (generation == observed) cache.put(packageFqName, files)
		}
		return files
	}

	override fun onWrite(write: KtFileMetadataWrite) =
		synchronized(lock) {
			generation++
			when (write) {
				is KtFileMetadataWrite.Upserted -> {
					cache.invalidate(write.packageFqName)
					invalidateContaining(setOf(write.filePath))
				}

				is KtFileMetadataWrite.Removed -> {
					invalidateContaining(write.filePaths.toSet())
				}

				KtFileMetadataWrite.Cleared -> {
					cache.invalidateAll()
				}
			}
		}

	private fun invalidateContaining(paths: Set<String>) {
		val stale =
			cache
				.asMap()
				.filterValues { files -> files.any { pathOf(it) in paths } }
				.keys
		cache.invalidateAll(stale)
	}

	companion object {
		const val DEFAULT_MAX_CACHED_FILES = 2_000L
	}
}
