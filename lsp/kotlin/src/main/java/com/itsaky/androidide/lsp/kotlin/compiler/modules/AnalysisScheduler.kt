package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.progress.ICancelChecker
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Priority of an Analysis API request. Higher [ordinal] wins: a request can preempt any strictly
 * lower-priority analysis that is currently running, and is served before any lower-priority request
 * that is merely waiting.
 *
 * Order: [INDEXING] < [DIAGNOSTICS] < [INTERACTIVE] — interactive requests (completion, signature
 * help) beat background diagnostics, which beats bulk indexing.
 *
 * [supersedesSamePriority] additionally lets a *newer* request preempt an in-flight one of the
 * **same** priority. On for [INTERACTIVE] only: rapid typing makes the in-flight request stale, so
 * the newer one cancels it and the superseded work is *discarded* (nothing reschedules it). Off for
 * [DIAGNOSTICS]/[INDEXING], whose preempted work is re-queued — there same-priority preemption would
 * livelock, two contenders endlessly re-queuing and re-preempting each other.
 */
internal enum class AnalysisPriority(val supersedesSamePriority: Boolean) {
	INDEXING(supersedesSamePriority = false),
	DIAGNOSTICS(supersedesSamePriority = false),
	INTERACTIVE(supersedesSamePriority = true),
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

	private val onCancelListeners = CopyOnWriteArrayList<() -> Unit>()

	/** Marks this analysis as preempted; the next [abortIfCancelled] will throw. */
	fun preempt() {
		preempted = true
		// Preemption is a cancellation too: fire invokeOnCancel listeners now, don't wait for a poll.
		onCancelListeners.forEach { it() }
		onCancelListeners.clear()
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

	override fun invokeOnCancel(listener: () -> Unit) {
		// Fire on either scheduler preemption (stored locally, run by preempt()) or the delegate's own
		// cancellation (forwarded so the editor's CompletionCancelChecker.cancel() pushes it). The
		// listener body is idempotent, so firing via both paths is harmless.
		onCancelListeners.add(listener)
		delegate.invokeOnCancel(listener)
		// Guard the race where preempt() ran between add and now.
		if (preempted && onCancelListeners.remove(listener)) {
			listener()
		}
	}

	override fun removeOnCancel(listener: () -> Unit) {
		// Mirror invokeOnCancel: drop from both the local (preemption) list and the delegate.
		onCancelListeners.remove(listener)
		delegate.removeOnCancel(listener)
	}
}

/**
 * A process-global, priority-aware, preemptive lock that serializes all Kotlin Analysis API access.
 *
 * It replaces the plain FIFO lock that previously guarded `analyze` / `analyzeCopy`. Semantics:
 * - only one analysis runs at a time (the Analysis API is not safe to drive concurrently);
 * - a higher-priority requester **preempts** a strictly lower-priority holder by invoking its
 *   `onPreempt` callback once (cooperative — the holder bails at its next `abortIfCancelled()`);
 * - a newer requester of the **same** priority likewise preempts the holder when that priority is
 *   [AnalysisPriority.supersedesSamePriority] (completion only — its superseded work is discarded, not
 *   rescheduled);
 * - when the lock frees, the highest-priority waiter acquires it next;
 * - it is **reentrant**: a nested analysis on the same thread re-enters without deadlocking.
 *
 * Access it through [withAnalysisLock] / [analyzeMaybeDangling] rather than directly.
 */
internal object AnalysisScheduler {

	/** Upper bound on how long a queued requester waits before re-checking its cancellation. */
	private const val WAIT_POLL_MILLIS = 25L

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
	 * preemptable analysis is in progress — strictly lower priority, or the same priority when that
	 * priority is [AnalysisPriority.supersedesSamePriority] — [onPreempt] of *that* holder is invoked so
	 * it yields; [onPreempt] passed here is stored and used if this acquisition is later preempted.
	 *
	 * [cancelChecker] is *this* requester's checker: a queued requester re-checks it on a short timer and
	 * [acquire] throws (rather than park until the lock frees) once cancelled — e.g. the editor superseded
	 * this completion. This stops superseded completions from piling up holding heavy state (KtFile copies,
	 * symbol lists), which on-device saturated the heap and triggered multi-second GC stalls.
	 */
	fun acquire(priority: AnalysisPriority, cancelChecker: ICancelChecker, onPreempt: () -> Unit) {
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
					// Bail out (rather than keep waiting) if this requester was cancelled while queued.
					cancelChecker.abortIfCancelled()

					val hp = holderPriority
					if (holderThread != null && hp != null && !holderPreempted &&
						(hp.ordinal < priority.ordinal ||
							(hp == priority && priority.supersedesSamePriority))
					) {
						// Signal the holder to bail (once): either it is strictly lower priority, or a
						// newer same-priority request supersedes it (completion only).
						holderPreempted = true
						holderPreempt?.invoke()
					}

					if (holderThread == null && !higherPriorityWaiting(priority)) {
						break
					}
					// Timed wait so a cancellation that arrives without a lock-state change (no signal) is
					// still observed within WAIT_POLL_MILLIS, bounding how long a superseded waiter parks.
					available.await(WAIT_POLL_MILLIS, TimeUnit.MILLISECONDS)
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

	/**
	 * Run [action] while holding the analysis lock at the given [priority], guaranteeing the hold taken
	 * by [acquire] is always released — even if [action] throws. Mirrors [kotlin.concurrent.withLock]:
	 * prefer this over calling [acquire]/[release] directly, so a lock can never leak and permanently
	 * deadlock all subsequent analysis. See [acquire] for the meaning of [cancelChecker] and [onPreempt].
	 */
	inline fun <R> withLock(
		priority: AnalysisPriority,
		cancelChecker: ICancelChecker,
		noinline onPreempt: () -> Unit,
		action: () -> R,
	): R {
		acquire(priority, cancelChecker, onPreempt)
		try {
			return action()
		} finally {
			release()
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
