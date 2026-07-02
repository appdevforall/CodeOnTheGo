package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.progress.ICancelChecker
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Priority of an Analysis API request. Higher [ordinal] wins: a request can preempt any strictly
 * lower-priority analysis that is currently running, and is served before any lower-priority request
 * that is merely waiting.
 *
 * Order: [INDEXING] < [DIAGNOSTICS] < [COMPLETION] — interactive completion beats background
 * diagnostics, which beats bulk indexing.
 */
internal enum class AnalysisPriority {
	INDEXING,
	DIAGNOSTICS,
	COMPLETION,
}

/**
 * Thrown at an `abortIfCancelled()` checkpoint when the running analysis has been preempted by a
 * higher-priority request (see [AnalysisScheduler]). It is a [CancellationException] so it unwinds
 * cleanly through the existing cancellation-aware `catch` blocks; callers that want the preempted work
 * to run later catch this specific type and re-schedule it.
 */
internal class AnalysisPreemptedException :
	CancellationException("analysis preempted by a higher-priority request")

/**
 * An [ICancelChecker] that adds a cooperative *preemption* signal on top of an existing [delegate]
 * checker. [AnalysisScheduler] flags preemption here; the running analysis observes it both at its
 * LSP-level [abortIfCancelled] checkpoints and, because [withAnalysisLock] installs a
 * [CancelCheckerProgressIndicator] bridging [isCancelled] to the compiler's `ProgressManager`,
 * mid-`analyze` at the compiler's own internal cancellation checks.
 *
 * Preemption is distinct from ordinary cancellation: [abortIfCancelled] throws
 * [AnalysisPreemptedException] (so the source can re-schedule the work) while still honouring the
 * delegate's own cancellation (e.g. a superseding edit or a closed file).
 */
internal class ScheduledCancelChecker(
	private val delegate: ICancelChecker,
) : ICancelChecker {

	@Volatile
	private var preempted = false

	/** Marks this analysis as preempted; the next [abortIfCancelled] will throw. */
	fun preempt() {
		preempted = true
	}

	override fun cancel() {
		delegate.cancel()
	}

	override fun isCancelled(): Boolean = preempted || delegate.isCancelled()

	override fun abortIfCancelled() {
		if (preempted) {
			throw AnalysisPreemptedException()
		}
		delegate.abortIfCancelled()
	}
}

/**
 * A process-global, priority-aware, preemptive lock that serializes all Kotlin Analysis API access.
 *
 * It replaces the plain FIFO lock that previously guarded `analyze` / `analyzeCopy`. Semantics:
 * - only one analysis runs at a time (the Analysis API is not safe to drive concurrently);
 * - a higher-priority requester **preempts** a strictly lower-priority holder by invoking its
 *   `onPreempt` callback once (cooperative — the holder bails at its next `abortIfCancelled()`);
 * - when the lock frees, the highest-priority waiter acquires it next;
 * - it is **reentrant**: a nested analysis on the same thread re-enters without deadlocking.
 *
 * Access it through [withAnalysisLock] / [analyzeMaybeDangling] rather than directly.
 */
internal object AnalysisScheduler {

	private val mutex = ReentrantLock()
	private val available = mutex.newCondition()

	private var holderThread: Thread? = null
	private var holderPriority: AnalysisPriority? = null
	private var holderReentry = 0
	private var holderPreempted = false
	private var holderPreempt: (() -> Unit)? = null

	/** Number of threads currently waiting to acquire, per priority. */
	private val waiting = IntArray(AnalysisPriority.entries.size)

	/**
	 * Acquire the analysis lock at the given [priority]. Blocks until the current thread may run. If a
	 * strictly lower-priority analysis is in progress, [onPreempt] of *that* holder is invoked so it
	 * yields; [onPreempt] passed here is stored and used if this acquisition is later preempted.
	 */
	fun acquire(priority: AnalysisPriority, onPreempt: () -> Unit) {
		mutex.withLock {
			val me = Thread.currentThread()
			if (holderThread === me) {
				// Reentrant: nested analysis on the same thread shares the outer hold.
				holderReentry++
				return
			}

			waiting[priority.ordinal]++
			try {
				while (true) {
					val hp = holderPriority
					if (holderThread != null && hp != null &&
						hp.ordinal < priority.ordinal && !holderPreempted
					) {
						// Signal the lower-priority holder to bail (once).
						holderPreempted = true
						holderPreempt?.invoke()
					}

					if (holderThread == null && !higherPriorityWaiting(priority)) {
						break
					}
					available.await()
				}
			} finally {
				waiting[priority.ordinal]--
			}

			holderThread = me
			holderPriority = priority
			holderPreempt = onPreempt
			holderPreempted = false
			holderReentry = 1
		}
	}

	/** Release a hold acquired via [acquire]. Wakes waiters when the outermost hold is released. */
	fun release() {
		mutex.withLock {
			if (holderThread !== Thread.currentThread()) {
				return
			}
			if (--holderReentry > 0) {
				return
			}
			holderThread = null
			holderPriority = null
			holderPreempt = null
			holderPreempted = false
			available.signalAll()
		}
	}

	private fun higherPriorityWaiting(priority: AnalysisPriority): Boolean {
		for (i in priority.ordinal + 1 until waiting.size) {
			if (waiting[i] > 0) return true
		}
		return false
	}
}
