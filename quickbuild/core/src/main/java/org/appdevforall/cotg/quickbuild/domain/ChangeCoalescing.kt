package org.appdevforall.cotg.quickbuild.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * One raw watcher observation before coalescing: a path was written/created ([Modified]) or
 * deleted ([Removed]). Distinguishing them at the source is what lets a standalone deletion
 * (a `git pull`/branch-switch/`rm`, which produces no create-or-modify event) reach the
 * build pipeline at all - the ADFA-4128 gap Bug 12 closes.
 */
sealed interface WatchEvent {
	val file: File

	data class Modified(
		override val file: File,
	) : WatchEvent

	data class Removed(
		override val file: File,
	) : WatchEvent
}

/**
 * Coalesces a stream of individual file-change events into batches, so a burst of writes
 * (save-all, git pull, plugin/Termux codegen) becomes ONE quick build instead of many.
 * Pure JVM logic on a coroutine clock - unit-tested with virtual time, no Android.
 *
 * Each batch carries both the modified/created paths and the removed ones (a
 * [ChangedFiles.Known] with its `files` and `removed` sets), so a burst that both edits and
 * deletes files is still one build. The LAST event per path within a burst wins: a
 * create-then-delete collapses to a removal, a delete-then-recreate to a modification.
 *
 * Trailing debounce with a hard cap:
 * - the batch is emitted [quietMillis] after the LAST event (each new event resets that
 *   timer) - so a streaming write coalesces into a single batch and a lone save waits
 *   exactly one quiet window;
 * - but never later than [maxMillis] after the FIRST event in the batch - so a very long
 *   continuous write stream (large pull) still fires promptly, with any stragglers picked
 *   up by the orchestrator's follow-up build.
 *
 * Missing an occasional event is tolerable (the orchestrator reconciles and the poll
 * safety net in the watcher backstops it); fragmenting every burst into N builds is not,
 * which is why the debounce lives here rather than relying on the orchestrator alone.
 */
fun Flow<WatchEvent>.coalesceChanges(
	quietMillis: Long,
	maxMillis: Long,
): Flow<ChangedFiles.Known> =
	channelFlow {
		// Keyed by path so the last event for a path wins (create-then-delete -> removed).
		val batch = LinkedHashMap<File, WatchEvent>()
		val lock = Mutex()
		var quietTimer: Job? = null
		var capTimer: Job? = null

		suspend fun flush() {
			// flush() usually runs INSIDE one of the timer jobs. Never cancel the job that
			// is executing this flush: a self-cancel makes the send() below throw
			// CancellationException as soon as it has to suspend (busy consumer), silently
			// dropping the batch - a stale app, the exact invariant this pipeline protects.
			val self = currentCoroutineContext()[Job]
			val snapshot =
				lock.withLock {
					if (quietTimer !== self) quietTimer?.cancel()
					quietTimer = null
					if (capTimer !== self) capTimer?.cancel()
					capTimer = null
					if (batch.isEmpty()) null else LinkedHashMap(batch).also { batch.clear() }
				}
			// Send outside the lock so a slow consumer never stalls the collector's timers.
			if (snapshot != null) {
				send(snapshot.toChangedFiles())
			}
		}

		collect { event ->
			val startedBatch =
				lock.withLock {
					val first = batch.isEmpty()
					batch[event.file] = event
					quietTimer?.cancel()
					quietTimer =
						launch {
							delay(quietMillis)
							flush()
						}
					first
				}
			if (startedBatch) {
				// Cap timer is armed once per batch on the first event and never reset.
				lock.withLock {
					capTimer?.cancel()
					capTimer =
						launch {
							delay(maxMillis)
							flush()
						}
				}
			}
		}

		// Upstream completed: emit whatever is still pending so nothing is dropped.
		flush()
	}

private fun Map<File, WatchEvent>.toChangedFiles(): ChangedFiles.Known {
	val modified = LinkedHashSet<File>()
	val removed = LinkedHashSet<File>()
	for ((file, event) in this) {
		when (event) {
			is WatchEvent.Modified -> modified.add(file)
			is WatchEvent.Removed -> removed.add(file)
		}
	}
	return ChangedFiles.Known(modified, removed)
}

/** Default debounce for the on-device project watcher (see design-watcher-and-testing.md). */
object ChangeCoalescingDefaults {
	const val QUIET_MILLIS = 150L
	const val MAX_MILLIS = 1_000L

	/** Channel capacity for the raw pre-coalesce event stream; a burst buffers, never blocks. */
	const val RAW_EVENT_BUFFER = Channel.UNLIMITED
}
