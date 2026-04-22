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
    fun onProgress(sourceId: String, event: IndexingEvent)
}

sealed class IndexingEvent {
    data object Started : IndexingEvent()
    data class Progress(val processed: Int) : IndexingEvent()
    data class Completed(val totalIndexed: Int) : IndexingEvent()
    data class Failed(val error: Throwable) : IndexingEvent()
    data object Skipped : IndexingEvent()
}

/**
 * Runs indexing operations in the background.
 */
class BackgroundIndexer<T : Indexable>(
    private val index: Index<T>,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    ),
) : Closeable {

    companion object {
        private val log = LoggerFactory.getLogger(BackgroundIndexer::class.java)
    }

    var progressListener: IndexingProgressListener? = null

    private val activeJobs = ConcurrentHashMap<String, Job>()

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
        provider: (sourceId: String) -> Sequence<T>,
    ): Job {
        // Cancel any in-flight job for this source
        activeJobs[sourceId]?.cancel()

        val job = scope.launch {
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
                val tracked = provider(sourceId).map { entry ->
                    count++
                    if (count % 1000 == 0) {
                        progressListener?.onProgress(sourceId, IndexingEvent.Progress(count))
                    }
                    entry
                }

                index.insertAll(tracked)

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
    ): List<Job> {
        return sources.map { source ->
            val (sourceId, seq) = mapper(source)
            indexSource(sourceId, skipIfExists) { seq }
        }
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
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }
}
