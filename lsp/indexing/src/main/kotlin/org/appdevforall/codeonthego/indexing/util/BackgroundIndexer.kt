package org.appdevforall.codeonthego.indexing.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
		private const val BATCH_SIZE = 500
	}

	var progressListener: IndexingProgressListener? = null

	private val activeJobs = ConcurrentHashMap<String, Job>()

	/**
	 * Index a single source. The [provider] returns a [Flow] that is
	 * collected on [Dispatchers.IO] and inserted in batches through
	 * [Index.insertAll].
	 *
	 * If [skipIfExists] is true and the source is already indexed,
	 * this is a no-op.
	 *
	 * @param sourceId     Identifies the source.
	 * @param skipIfExists Skip if already indexed.
	 * @param provider     Lambda returning a [Flow] of entries. Resources the flow opens
	 *                     are released when collection completes, fails or is cancelled.
	 * @return The launched job.
	 */
	fun indexSource(
		sourceId: String,
		skipIfExists: Boolean = true,
		provider: (sourceId: String) -> Flow<T>,
	): Job {
		// Cancel any in-flight job for this source
		activeJobs[sourceId]?.cancel()

		val job =
			// LAZY so the job is registered before it can run and remove itself.
			scope.launch(start = CoroutineStart.LAZY) {
				try {
					if (skipIfExists && index.containsSource(sourceId)) {
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
					withContext(Dispatchers.IO) {
						val batch = ArrayList<T>(BATCH_SIZE)
						var error: Exception? = null
						try {
							provider(sourceId).collect { entry ->
								batch.add(entry)
								count++
								if (count % 1000 == 0) {
									progressListener?.onProgress(sourceId, IndexingEvent.Progress(count))
								}
								if (batch.size == BATCH_SIZE) {
									try {
										index.insertAll(batch.asSequence())
									} finally {
										batch.clear()
									}
								}
							}
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							// The flow can fail after its last emit (e.g. EIO closing the JAR);
							// keep the entries already collected, then report the failure.
							error = e
						}
						if (batch.isNotEmpty()) index.insertAll(batch.asSequence())
						error?.let { throw it }
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
					// Remove only this job: a replacement for the same source may already be registered.
					activeJobs.remove(sourceId, coroutineContext.job)
				}
			}

		activeJobs[sourceId] = job
		job.start()
		return job
	}

	/**
	 * Index multiple sources sequentially in the background.
	 *
	 * Each source gets its own coroutine. The [SupervisorJob] ensures
	 * that one failure doesn't cancel the others.
	 *
	 * @param sources The sources to index (e.g. a list of JAR paths).
	 * @param mapper  Maps each source to a (sourceId, Flow) pair.
	 */
	fun <S> indexSources(
		sources: Collection<S>,
		skipIfExists: Boolean = true,
		mapper: (S) -> Pair<String, Flow<T>>,
	): List<Job> =
		sources.map { source ->
			val (sourceId, flow) = mapper(source)
			indexSource(sourceId, skipIfExists) { flow }
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
