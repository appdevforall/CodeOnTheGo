package org.appdevforall.codeonthego.indexing.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
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
	/** Named apart from any per-source [Job] a call to [indexSource] launches, so it can't be shadowed. */
	private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
	private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)

	companion object {
		private val log = LoggerFactory.getLogger(BackgroundIndexer::class.java)
	}

	var progressListener: IndexingProgressListener? = null

	/** The job currently indexing a source, and the fingerprint it was submitted with. */
	private data class ActiveJob(
		val job: Job,
		val fingerprint: String?,
	)

	private val activeJobs = ConcurrentHashMap<String, ActiveJob>()

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
	 * A source already being indexed by a still-running job submitted with the same
	 * [fingerprint] is left alone rather than restarted: that job will produce the same
	 * result, so cancelling and resubmitting it would only throw away its progress. A
	 * source being indexed under a different fingerprint (its content changed again before
	 * the previous pass finished) is handled by cancelling and joining that job before this
	 * one deletes its rows, so the two passes' writes can never interleave.
	 *
	 * The check for an existing job and the recording of this call's own job in [activeJobs] are
	 * not atomic with each other, so two overlapping calls for the same [sourceId] could each
	 * decide independently to proceed. That's safe only because every caller of [indexSource] for
	 * a given index submits through its own single-submitter mutex (see the indexing services'
	 * `indexingMutex`), never concurrently with itself.
	 *
	 * @param sourceId     Identifies the source.
	 * @param skipIfExists Skip if already indexed.
	 * @param fingerprint  Identifies the source's current content, or `null` to not track it.
	 * @param provider     Lambda returning a [Sequence] of entries.
	 * @return The launched job, or the already-running one this call was folded into.
	 */
	fun indexSource(
		sourceId: String,
		skipIfExists: Boolean = true,
		fingerprint: String? = null,
		provider: (sourceId: String) -> Sequence<T>,
	): Job {
		val existing = activeJobs[sourceId]
		if (existing != null && fingerprint != null && fingerprint == existing.fingerprint && existing.job.isActive) {
			log.debug("Skipping resubmit of already-running: {}", sourceId)
			progressListener?.onProgress(sourceId, IndexingEvent.Skipped)
			return existing.job
		}

		val previousJob = existing?.job
		// Signalled as soon as this call supersedes it, rather than only once this job's own
		// coroutine gets scheduled, so a superseded pass starts winding down immediately.
		previousJob?.cancel()

		val job =
			scope.launch(start = CoroutineStart.LAZY) {
				try {
					// Let a superseded pass for this source finish (or actually stop) before
					// this one touches its rows, so the two passes' writes never interleave.
					previousJob?.join()

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
					/*
					 * Only if this job is still the one on record: a job that lost the race to a
					 * newer submission must not clear that newer job's entry. Reads the running
					 * coroutine's own Job rather than the enclosing `job` local, which is out of
					 * scope here (this lambda is still part of that local's own initializer).
					 */
					activeJobs.remove(sourceId, ActiveJob(coroutineContext.job, fingerprint))
				}
			}

		// Recorded before the job starts, so a concurrent call for the same source always sees
		// this job rather than racing to observe it mid-launch.
		activeJobs[sourceId] = ActiveJob(job, fingerprint)
		job.start()
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
		activeJobs.values.toList().forEach { it.job.cancelAndJoin() }
	}

	/**
	 * Wait for all in-flight indexing to complete.
	 */
	suspend fun awaitAll() {
		activeJobs.values.map { it.job }.joinAll()
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
			supervisor.cancelAndJoin()
		}
		activeJobs.clear()
	}
}
