package com.itsaky.androidide.utils

import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.tasks.JobCancelChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class KeyedDebouncingAction<T: Any>(
	private val scope: CoroutineScope,
	private val debounceDuration: Duration = DEBOUNCE_DURATION_DEFAULT,
	private val actionContext: CoroutineContext = Dispatchers.Default,
	private val action: suspend (T, ICancelChecker) -> Unit,
) {

	private data class ActionEntry<T>(
		val channel: Channel<T>,
		val job: Job,
	) {
		fun cancel() {
			channel.close()
			job.cancel()
		}
	}

	private val pending = ConcurrentHashMap<T, ActionEntry<T>>()

	companion object {
		val DEBOUNCE_DURATION_DEFAULT = 400.milliseconds
	}

	fun cancelPending(key: T) {
		pending.remove(key)?.cancel()
	}

	fun schedule(key: T) {
		val entry = pending.computeIfAbsent(key) { createEntry() }
		entry.channel.trySend(key)
	}

	private fun createEntry(): ActionEntry<T> {
		val channel = Channel<T>(Channel.CONFLATED)
		val job = scope.launch(actionContext) {
			for (latestKey in channel) {
				delay(debounceDuration)
				ensureActive()

				val cancelChecker = JobCancelChecker(currentCoroutineContext()[Job])
				action(latestKey, cancelChecker)
			}
		}

		return ActionEntry(channel, job)
	}

	fun cancelAll() {
		pending.values.forEach { it.cancel() }
		pending.clear()
	}
}