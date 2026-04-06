package org.appdevforall.codeonthego.indexing.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
    /**
     * Buffer capacity between the producer flow and the index writer.
     * Higher values use more memory but tolerate more producer/consumer
     * speed mismatch.
     */
    private val bufferCapacity: Int = 64,
) : Closeable {

    companion object {
        private val log = LoggerFactory.getLogger(BackgroundIndexer::class.java)
    }

    var progressListener: IndexingProgressListener? = null

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Index a single source. The [provider] returns a [Flow] that
     * lazily produces entries so that it is NOT collected eagerly.
     *
     * If [skipIfExists] is true and the source is already indexed,
     * this is a no-op.
     *
     * @param sourceId     Identifies the source.
     * @param skipIfExists Skip if already indexed.
     * @param provider     Lambda returning a lazy [Flow] of entries.
     *                     Runs on [Dispatchers.IO].
     * @return The launched job, or null if skipped.
     */
    fun indexSource(
        sourceId: String,
        skipIfExists: Boolean = true,
        provider: (sourceId: String) -> Flow<T>,
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

                // Streaming pipeline:
                // producer (IO) → buffer → consumer (index.insert)
                //
                // The producer emits entries lazily on Dispatchers.IO.
                // The buffer decouples producer and consumer speeds.
                // The index.insert collects from the buffered flow
                // and batches into transactions internally.
                var count = 0

                val tracked = provider(sourceId)
                    .flowOn(Dispatchers.IO)
                    .buffer(bufferCapacity)
                    .onStart {
                        progressListener?.onProgress(
                            sourceId, IndexingEvent.Started
                        )
                    }
                    .onCompletion { error ->
                        if (error == null) {
                            progressListener?.onProgress(
                                sourceId, IndexingEvent.Completed(count)
                            )
                            log.info("Indexed {} entries from {}", count, sourceId)
                        }
                    }
                    .catch { error ->
                        log.error("Indexing failed for {}", sourceId, error)
                        progressListener?.onProgress(
                            sourceId, IndexingEvent.Failed(error)
                        )
                    }

                // Wrap in a counting flow that reports progress
                val counted = kotlinx.coroutines.flow.flow {
                    tracked.collect { entry ->
                        emit(entry)
                        count++
                        if (count % 1000 == 0) {
                            progressListener?.onProgress(
                                sourceId, IndexingEvent.Progress(count)
                            )
                        }
                    }
                }

                index.insert(counted)

            } catch (e: CancellationException) {
                log.debug("Indexing cancelled: {}", sourceId)
                throw e
            } catch (e: Exception) {
                log.error("Indexing failed: {}", sourceId, e)
                progressListener?.onProgress(
                    sourceId, IndexingEvent.Failed(e)
                )
            } finally {
                activeJobs.remove(sourceId)
            }
        }

        activeJobs[sourceId] = job
        return job
    }

    /**
     * Index multiple sources in parallel.
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
    ): List<Job> {
        return sources.map { source ->
            val (sourceId, flow) = mapper(source)
            indexSource(sourceId, skipIfExists) { flow }
        }.filterNotNull()
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
