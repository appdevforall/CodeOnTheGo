package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.ThreadReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

internal typealias Evaluator<R> = suspend () -> R

private sealed interface EvaluationMessage {
    data class EvaluateRequest<R>(
        val thread: ThreadReference,
        val evaluator: Evaluator<R>,
        val deferred: CompletableDeferred<R?>
    ) : EvaluationMessage

    data object Shutdown : EvaluationMessage
}

class EvaluationContext : AutoCloseable {
    companion object {
        private val logger = LoggerFactory.getLogger(EvaluationContext::class.java)
    }

    override fun close() {
        evaluationActor.trySend(EvaluationMessage.Shutdown)
        evaluationActor.close()
    }

    private val sequentialDispatcher = Dispatchers.IO.limitedParallelism(1)


    @OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val evaluationActor = CoroutineScope(sequentialDispatcher).actor<EvaluationMessage>(
        capacity = Channel.UNLIMITED
    ) {
        val threadBatches =
            mutableMapOf<ThreadReference, MutableList<EvaluationMessage.EvaluateRequest<*>>>()

        for (message in channel) {
            when (message) {
                is EvaluationMessage.EvaluateRequest<*> -> {
                    threadBatches.getOrPut(message.thread) { mutableListOf() }.add(message)
                    delay(10) // Small delay to allow batching

                    if (channel.isEmpty) {
                        processBatches(threadBatches)
                    }
                }

                is EvaluationMessage.Shutdown -> {
                    cancelPendingRequests(threadBatches)
                    break
                }
            }
        }
    }

    suspend fun <R> evaluate(
        thread: ThreadReference,
        evaluator: Evaluator<R>
    ): R? {
        val deferred = CompletableDeferred<R?>()
        val request = EvaluationMessage.EvaluateRequest(thread, evaluator, deferred)

        evaluationActor.send(request)
        return deferred.await()
    }

    private suspend fun processBatches(threadBatches: MutableMap<ThreadReference, MutableList<EvaluationMessage.EvaluateRequest<*>>>) {
        threadBatches.forEach { (thread, requests) ->
            if (requests.isNotEmpty()) {
                processBatchForThread(thread, requests)
                requests.clear()
            }
        }
    }

    private suspend fun processBatchForThread(
        thread: ThreadReference,
        requests: List<EvaluationMessage.EvaluateRequest<*>>
    ) {
        logger.debug(
            "Processing batch of {} requests for thread {}",
            requests.size,
            thread.name()
        )

        // Ensure thread is still suspended
        if (!thread.isSuspended) {
            logger.warn("Thread ${thread.name()} is not suspended, failing batch for thread {}", thread.name())
            val exception = IllegalStateException("Thread ${thread.name()} is not suspended")
            requests.forEach { request ->
                request.deferred.completeExceptionally(exception)
            }
            return
        }

        // Process each request sequentially
        requests.forEach { request ->
            try {
                @Suppress("UNCHECKED_CAST")
                val typedRequest = request as EvaluationMessage.EvaluateRequest<Any?>
                val result = typedRequest.evaluator()
                typedRequest.deferred.complete(result)
            } catch (e: Exception) {
                logger.error("Failed to execute evaluation for thread {}", thread.name(), e)
                request.deferred.completeExceptionally(e)
            }
        }
    }

    private fun cancelPendingRequests(
        threadBatches: MutableMap<ThreadReference, MutableList<EvaluationMessage.EvaluateRequest<*>>>
    ) {
        logger.debug("Cancelling pending evaluation requests")
        val cancellationException = CancellationException("EvaluationContext is shutting down")
        threadBatches.values.forEach { requests ->
            requests.forEach { request ->
                request.deferred.cancel(cancellationException)
            }
        }

        val totalCancelled = threadBatches.values.sumOf { it.size }
        if (totalCancelled > 0) {
            logger.debug(
                "Cancelled {} pending evaluation requests due to shutdown",
                totalCancelled
            )
        }

        threadBatches.clear()
    }
}