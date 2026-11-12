package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InvalidStackFrameException
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
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
import java.util.concurrent.atomic.AtomicLong

internal typealias Evaluator<R> = suspend () -> R

private sealed interface EvaluationMessage {
	data class EvaluateRequest<R>(
		val thread: ThreadReference,
		val evaluator: Evaluator<R>,
		val deferred: CompletableDeferred<R?>,
		val id: Long = Companion.id.getAndIncrement(),
	) : EvaluationMessage {
		companion object {
			private val id = AtomicLong(0)
		}
	}

	data class CancelPending(
		val thread: ThreadReference? = null,
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

	fun cancelPendingForThread(thread: ThreadReference) {
		evaluationActor.trySend(EvaluationMessage.CancelPending(thread))
	}

	fun cancelAllPending() {
		evaluationActor.trySend(EvaluationMessage.CancelPending(null))
	}

	private val sequentialDispatcher = Dispatchers.IO.limitedParallelism(1)

	@OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
	private val evaluationActor =
		CoroutineScope(sequentialDispatcher).actor<EvaluationMessage>(
			capacity = Channel.UNLIMITED,
		) {
			val threadBatches =
				mutableMapOf<ThreadReference, MutableList<EvaluationMessage.EvaluateRequest<*>>>()

			for (message in channel) {
				when (message) {
					is EvaluationMessage.EvaluateRequest<*> -> {
						threadBatches.getOrPut(message.thread) { mutableListOf() }.add(message)
						delay(10) // Small delay to allow batching

						if (channel.isEmpty) {
							try {
								processBatches(threadBatches)
							} catch (e: Throwable) {
								logger.error("Failed to process evaluator batches", e)
							}
						}
					}

					is EvaluationMessage.CancelPending -> {
						dropPendingRequests(threadBatches, message.thread)
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
		evaluator: Evaluator<R>,
	): R? {
		val deferred = CompletableDeferred<R?>()
		val request = EvaluationMessage.EvaluateRequest(thread, evaluator, deferred)

		val sendResult = evaluationActor.trySend(request)
		if (sendResult.isFailure) {
			return null
		}
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
		requests: List<EvaluationMessage.EvaluateRequest<*>>,
	) {
		val threadName = runCatching { thread.name() }.getOrDefault("<unknown>")
		logger.debug(
			"Processing batch of {} requests for thread {}",
			requests.size,
			threadName,
		)

		// Ensure thread is still suspended
		val isSuspended = runCatching { thread.isSuspended }.getOrDefault(false)
		if (!isSuspended) {
			logger.warn("Thread {} is not suspended, skipping batch of {} requests", threadName, requests.size)
			requests.forEach { request ->
				request.deferred.complete(null)
			}
			return
		}

		// Process each request sequentially
		requests.forEach { request ->
			if (!runCatching { thread.isSuspended }.getOrDefault(false)) {
				logger.warn("Thread {} resumed during batch, skipping remaining request", threadName)
				request.deferred.complete(null)
				return@forEach
			}

			try {
				@Suppress("UNCHECKED_CAST")
				val typedRequest = request as EvaluationMessage.EvaluateRequest<Any?>
				val result = typedRequest.evaluator()
				typedRequest.deferred.complete(result)
			} catch (e: CancellationException) {
				request.deferred.cancel(e)
				throw e
			} catch (e: Exception) {
				when (e) {
					is InvalidStackFrameException,
					is IncompatibleThreadStateException,
					is ObjectCollectedException,
					is VMDisconnectedException,
					-> {
						logger.warn("Skipping stale evaluation for thread {}: {}", threadName, e.message)
					}

					else -> {
						logger.error("Failed to execute evaluation for thread {}", threadName, e)
					}
				}
				request.deferred.complete(null)
			}
		}
	}

	private fun dropPendingRequests(
		threadBatches: MutableMap<ThreadReference, MutableList<EvaluationMessage.EvaluateRequest<*>>>,
		targetThread: ThreadReference?,
	) {
		if (targetThread != null) {
			val requests = threadBatches.remove(targetThread) ?: return
			if (requests.isNotEmpty()) {
				logger.debug("Dropping {} pending evaluation requests for resumed thread", requests.size)
				requests.forEach { it.deferred.complete(null) }
			}
		} else {
			val totalDropped = threadBatches.values.sumOf { it.size }
			if (totalDropped > 0) {
				logger.debug("Dropping {} pending evaluation requests across all threads", totalDropped)
			}
			threadBatches.values.forEach { requests ->
				requests.forEach { it.deferred.complete(null) }
			}
			threadBatches.clear()
		}
	}

	private fun cancelPendingRequests(threadBatches: MutableMap<ThreadReference, MutableList<EvaluationMessage.EvaluateRequest<*>>>) {
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
				totalCancelled,
			)
		}

		threadBatches.clear()
	}
}
