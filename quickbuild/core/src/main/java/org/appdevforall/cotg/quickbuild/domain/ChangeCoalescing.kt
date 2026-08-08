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
 * One raw watcher observation before coalescing: a path was written or created ([Modified]),
 * or deleted ([Removed]).
 *
 * The two are distinguished at the source because a standalone deletion (`git pull`,
 * branch-switch, `rm`) fires no create-or-modify event, so it would otherwise never reach the
 * build pipeline.
 */
sealed interface WatchEvent {
	/** The path the observation is about, as the watcher reported it (absolute on device). */
	val file: File

	/**
	 * The path was written or created, so its current bytes are on disk for the build to read.
	 *
	 * @property file the written path; a create and a rewrite are not distinguished, since both
	 *   feed the compiler the same way.
	 */
	data class Modified(
		override val file: File,
	) : WatchEvent

	/**
	 * The path was deleted.
	 *
	 * @property file the deleted path; nothing is left on disk, so it can only be classified by
	 *   the shape of the path itself.
	 */
	data class Removed(
		override val file: File,
	) : WatchEvent
}

/**
 * Coalesces a stream of file-change events into batches, so a burst of writes (save-all, git
 * pull, codegen) becomes one quick build instead of many. Pure JVM on a coroutine clock -
 * unit-tested with virtual time.
 *
 * Each batch carries modified and removed paths together, and the last event per path wins:
 * create-then-delete collapses to a removal, delete-then-recreate to a modification.
 *
 * @param quietMillis emit this long after the LAST event; every new event resets the timer.
 * @param maxMillis hard cap measured from the FIRST event of the batch, so a long continuous
 *   write stream still fires promptly. Stragglers land in the orchestrator's follow-up build.
 * @return one [ChangedFiles.Known] per quiet-period or cap expiry, never an empty batch; the
 *   upstream's completion flushes whatever is still accumulating.
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
			// flush() usually runs inside one of the timer jobs, and must never cancel the job
			// executing it: the send() below would then throw CancellationException as soon as
			// it had to suspend on a busy consumer, silently dropping the batch.
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
	/** Quiet period after the last event; short enough that a save still feels immediate. */
	const val QUIET_MILLIS = 150L

	/** Cap from the batch's first event, so a continuous write stream cannot defer a build. */
	const val MAX_MILLIS = 1_000L

	/** Channel capacity for the raw pre-coalesce event stream; a burst buffers, never blocks. */
	const val RAW_EVENT_BUFFER = Channel.UNLIMITED
}
