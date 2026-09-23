package org.appdevforall.codeonthego.indexing.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.api.Index
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Callback for tracking indexing progress.
 * Implementations must be thread-safe.
 */
fun interface IndexingProgressListener {
	/**
	 * Called with progress updates during indexing.
	 *
	 * @param sourceId The source being indexed.
	 * @param event    What happened.
	 */
	fun onProgress(
		sourceId: String,
		event: IndexingEvent,
	)
}

sealed class IndexingEvent {
	data object Started : IndexingEvent()

	data class Progress(
		val processed: Int,
	) : IndexingEvent()

	data class Completed(
		val totalIndexed: Int,
	) : IndexingEvent()

	data class Failed(
		val error: Throwable,
	) : IndexingEvent()

	data object Skipped : IndexingEvent()
}

/**
 * Runs indexing operations in the background.
 */
class BackgroundIndexer<T : Indexable>(
	private val index: Index<T>,
	parentScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : Closeable {
	private val job = SupervisorJob(parentScope.coroutineContext[Job])
	private val scope = CoroutineScope(parentScope.coroutineContext + job)

	companion object {
		private val log = LoggerFactory.getLogger(BackgroundIndexer::class.java)
	}

	var progressListener: IndexingProgressListener? = null

	private val activeJobs = ConcurrentHashMap<String, Job>()

	/**
	 * Index a single source. The [provider] returns a [Sequence] that
	 * lazily produces entries - it is consumed on [Dispatchers.IO] by
	 * [Index.insertAll], or [Index.insertSource] when a [fingerprint] is given.
	 *
	 * If [skipIfExists] is true and the source is already indexed, this is a
	 * no-op. With a [fingerprint], "already indexed" means the index holds
	 * that same fingerprint for the source, so a source whose content changed
	 * at the same id is re-indexed; without one, it means the index holds any
	 * entry from the source.
	 *
	 * @param sourceId     Identifies the source.
	 * @param skipIfExists Skip if already indexed.
	 * @param fingerprint  Identifies the source's current content, or `null` to not track it.
	 * @param provider     Lambda returning a [Sequence] of entries.
	 * @return The launched job.
	 */
	fun indexSource(
		sourceId: String,
		skipIfExists: Boolean = true,
		fingerprint: String? = null,
		provider: (sourceId: String) -> Sequence<T>,
	): Job {
		// Cancel any in-flight job for this source
		activeJobs[sourceId]?.cancel()

		val job =
			scope.launch {
				try {
					if (skipIfExists && isIndexed(sourceId, fingerprint)) {
						log.debug("Skipping already-indexed: {}", sourceId)
						progressListener?.onProgress(sourceId, IndexingEvent.Skipped)
						return@launch
					}

					log.info("Indexing: {}", sourceId)

					// Remove stale entries first
					index.removeBySource(sourceId)

					if (!isActive) return@launch

					progressListener?.onProgress(sourceId, IndexingEvent.Started)

					var count = 0
					val tracked =
						provider(sourceId).map { entry ->
							count++
							if (count % 1000 == 0) {
								progressListener?.onProgress(sourceId, IndexingEvent.Progress(count))
							}
							entry
						}

					if (fingerprint != null) {
						index.insertSource(sourceId, fingerprint, tracked)
					} else {
						index.insertAll(tracked)
					}

					progressListener?.onProgress(sourceId, IndexingEvent.Completed(count))
					log.info("Indexed {} entries from {}", count, sourceId)
				} catch (e: CancellationException) {
					log.debug("Indexing cancelled: {}", sourceId)
					throw e
				} catch (e: Exception) {
					log.error("Indexing failed: {}", sourceId, e)
					progressListener?.onProgress(sourceId, IndexingEvent.Failed(e))
				} finally {
					activeJobs.remove(sourceId)
				}
			}

		activeJobs[sourceId] = job
		return job
	}

	private suspend fun isIndexed(
		sourceId: String,
		fingerprint: String?,
	): Boolean =
		if (fingerprint != null) {
			index.sourceFingerprint(sourceId) == fingerprint
		} else {
			index.containsSource(sourceId)
		}

	/**
	 * Index multiple sources sequentially in the background.
	 *
	 * Each source gets its own coroutine. The [SupervisorJob] ensures
	 * that one failure doesn't cancel the others.
	 *
	 * @param sources The sources to index (e.g. a list of JAR paths).
	 * @param mapper  Maps each source to a (sourceId, Sequence) pair.
	 */
	fun <S> indexSources(
		sources: Collection<S>,
		skipIfExists: Boolean = true,
		mapper: (S) -> Pair<String, Sequence<T>>,
	): List<Job> =
		sources.map { source ->
			val (sourceId, seq) = mapper(source)
			indexSource(sourceId, skipIfExists) { seq }
		}

	/**
	 * Cancel all in-flight indexing and wait for completion.
	 */
	suspend fun cancelAll() {
		activeJobs.values.toList().forEach { it.cancelAndJoin() }
	}

	/**
	 * Wait for all in-flight indexing to complete.
	 */
	suspend fun awaitAll() {
		activeJobs.values.toList().joinAll()
	}

	/**
	 * Returns the number of currently active indexing jobs.
	 */
	val activeJobCount: Int get() = activeJobs.size

	override fun close() {
		val activeCount = activeJobCount
		if (activeCount > 0) {
			log.warn(
				"Closing indexer with {} active job(s); cancellation is cooperative and close will wait for completion",
				activeCount,
			)
		}
		runBlocking {
			job.cancelAndJoin()
		}
		activeJobs.clear()
	}
}
