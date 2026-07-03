package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.projectStructure.isDangling
import kotlinx.coroutines.Job
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.UserDataProperty
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val KT_LSP_COMPLETION_BACKING_FILE = Key<Path>("KT_LSP_COMPLETION_BACKING_FILE")
var KtFile.backingFilePath by UserDataProperty(KT_LSP_COMPLETION_BACKING_FILE)

private val logger = LoggerFactory.getLogger("KtFileExts")

/**
 * Runs [action] while holding the global analysis lock at the given [priority].
 *
 * The Analysis API tracks its `analyze` lifetime context in a per-thread stack and is not safe to
 * drive concurrently from multiple background threads without the platform read-action coordination
 * that this LSP replaces with a custom [com.itsaky.androidide.lsp.kotlin.compiler.read] lock.
 * Indexing, diagnostics and completion all run analysis on `Dispatchers.Default` and frequently
 * target the same edited file, so overlapping `analyze` calls corrupted the lifetime/session
 * lifecycle and surfaced as
 * `KaInaccessibleLifetimeOwnerAccessException: ... Called outside an \`analyze\` context.`
 *
 * Serialization is handled by [AnalysisScheduler], which is priority-aware and preemptive: a
 * higher-priority request preempts a strictly lower-priority one (cooperatively, via [cancelChecker]).
 * The scheduler is reentrant, so an (indirect) nested analysis on the same thread cannot deadlock.
 *
 * **All** Analysis API access must go through this helper (or [analyzeMaybeDangling], which already
 * does); never call `analyze` / `analyzeCopy` directly, or the serialization guarantee is lost.
 *
 * **Cancellation.** The [action] runs with a [kotlinx.coroutines.Job] installed in the thread's
 * IntelliJ coroutine context. The compiler's own `ProgressManager.checkCanceled()` calls (dense
 * throughout FIR resolution) route through `Cancellation.checkCancelled()`, which throws as soon as
 * that Job is cancelled — aborting the analysis *mid*-`analyze`, not just at the coarse LSP
 * [ScheduledCancelChecker.abortIfCancelled] checkpoints between work units. (A
 * [CancelCheckerProgressIndicator] is also installed as a fallback, but its poll-driven arming does
 * not run in this embeddable environment; see the body.) The compiler signals cancellation by
 * throwing [ProcessCanceledException]; we translate it back into the typed exception the callers
 * already handle ([AnalysisPreemptedException] when preempted, else the delegate's
 * `CancellationException`) so preempted work is rescheduled rather than silently dropped.
 *
 * **Footgun:** analysis runs under the *read* (shared) side of the global
 * [com.itsaky.androidide.lsp.kotlin.compiler.read] lock, and that `ReentrantReadWriteLock` is
 * non-upgradeable. Code running inside [withAnalysisLock] / an `analyze` block must therefore never
 * call [com.itsaky.androidide.lsp.kotlin.compiler.write] — upgrading read → write on the same thread
 * deadlocks.
 */
internal inline fun <R> withAnalysisLock(
	priority: AnalysisPriority,
	cancelChecker: ScheduledCancelChecker,
	crossinline action: () -> R,
): R {
	val indicator = CancelCheckerProgressIndicator(cancelChecker)

	// Cancelling [job] is what actually aborts the running analysis *mid*-`analyze`: the embeddable
	// `CoreProgressManager.checkCanceled()` (called densely throughout FIR resolution) invokes
	// `Cancellation.checkCancelled()` *unconditionally* — before any indicator/`CheckCanceledBehavior`
	// gating — and that throws a `CeProcessCanceledException` (a `ProcessCanceledException`) the moment
	// the [Job] installed in this thread's context is cancelled.
	//
	// The [indicator] is kept only as a fallback for environments whose `CoreProgressManager` runs its
	// ~10ms non-standard-indicator poll; that poll does not run in this (Android, embeddable) one.
	val job = Job()

	AnalysisScheduler.acquire(priority, cancelChecker) {
		// Preemption is signalled by flipping the checker; the invokeOnCancel listener below turns that
		// (and ordinary editor cancellation) into the actual mid-`analyze` abort.
		cancelChecker.preempt()
	}
	// Single push path for *both* preemption and ordinary editor cancellation: [ScheduledCancelChecker]
	// fires this on preempt() and on its delegate's cancel(), so the running analyze aborts immediately
	// — no polling, and unaffected by GC pauses that would stall a poll thread.
	cancelChecker.invokeOnCancel {
		indicator.cancel()
		job.cancel()
	}
	try {
		val holder = arrayOfNulls<Any?>(1)
		try {
			// Install the Job for the duration of the analysis and restore the previous context after.
			AnalysisThreadContext.installJob(job).use {
				ProgressManager.getInstance()
					.executeProcessUnderProgress({ holder[0] = action() }, indicator)
			}
		} catch (e: ProcessCanceledException) {
			logger.debug("process cancelled: prio={}", priority)
			// Re-derive the semantically-correct exception callers expect (re-throws
			// AnalysisPreemptedException when preempted, or the delegate's CancellationException).
			cancelChecker.abortIfCancelled()
			throw e
		}
		@Suppress("UNCHECKED_CAST")
		return holder[0] as R
	} finally {
		AnalysisScheduler.release()
	}
}

internal inline fun <R> analyzeMaybeDangling(
	useSiteElement: KtElement,
	priority: AnalysisPriority,
	cancelChecker: ScheduledCancelChecker,
	crossinline action: KaSession.() -> R,
): R =
	withAnalysisLock(priority, cancelChecker) {
		if (useSiteElement is KtFile && useSiteElement.isDangling && useSiteElement.copyOrigin != null) {
			analyzeCopy(useSiteElement, KaDanglingFileResolutionMode.PREFER_SELF, action)
		} else {
			analyze(useSiteElement, action)
		}
	}
