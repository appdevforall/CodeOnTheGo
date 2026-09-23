package org.appdevforall.codeonthego.indexing.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/**
 * Aggregates the indexing passes of several services into one [IndexingState].
 *
 * A service calls [openPass] when a pass starts, [Pass.track] for each source job it submits, and
 * closes the pass in a `finally`, so a pass that throws or is cancelled cannot hold the state out of
 * [IndexingState.Idle]. A source id already pending is counted once; a job that supersedes it takes
 * its place without adding to the total. Sources are keyed by id alone, across every pass and
 * service, so a JAR path two services submit is counted once, and the replaced job's completion is
 * ignored. Each tracked job adds to `done` when it completes, whatever
 * its outcome. The state is [IndexingState.Indexing] while any tracked job is pending or any open
 * pass has tracked a job, and returns to [IndexingState.Idle], with both counts reset, otherwise.
 *
 * Thread-safe.
 */
class IndexingProgressTracker {
	private val lock = Any()
	private val mutableState = MutableStateFlow<IndexingState>(IndexingState.Idle)
	private val openPasses = mutableSetOf<Pass>()
	private val pending = mutableMapOf<String, Job>()
	private var done = 0
	private var total = 0

	/** The current indexing state; see [IndexingProgressTracker]. */
	val state: StateFlow<IndexingState> = mutableState.asStateFlow()

	/** Opens a pass; the caller must close it in a `finally`. */
	fun openPass(): Pass = Pass().also { pass -> synchronized(lock) { openPasses += pass } }

	/** Forgets every open pass and pending job and returns the state to [IndexingState.Idle]. */
	fun reset() {
		synchronized(lock) {
			openPasses.clear()
			pending.clear()
			publish()
		}
	}

	/** One service's indexing pass, reporting its submitted jobs until it is closed. */
	inner class Pass internal constructor() : Closeable {
		internal var hasTracked = false
			private set

		/** Counts [job], which indexes [sourceId], toward the state until it completes. */
		fun track(
			sourceId: String,
			job: Job,
		) {
			synchronized(lock) {
				if (this !in openPasses) return
				hasTracked = true
				if (pending.put(sourceId, job) == null) {
					total++
				}
				publish()
			}
			job.invokeOnCompletion { onCompleted(sourceId, job) }
		}

		override fun close() {
			synchronized(lock) {
				if (openPasses.remove(this)) {
					publish()
				}
			}
		}
	}

	private fun onCompleted(
		sourceId: String,
		job: Job,
	) {
		synchronized(lock) {
			if (!pending.remove(sourceId, job)) return
			done++
			publish()
		}
	}

	private fun publish() {
		if (pending.isNotEmpty() || openPasses.any { it.hasTracked }) {
			mutableState.value = IndexingState.Indexing(done, total)
		} else {
			done = 0
			total = 0
			mutableState.value = IndexingState.Idle
		}
	}
}
