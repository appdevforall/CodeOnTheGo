package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.projectStructure.isDangling
import kotlinx.coroutines.CancellationException
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
 * The Analysis API keeps per-thread `analyze` lifetime state and is unsafe to drive concurrently;
 * indexing, diagnostics and completion all analyze on `Dispatchers.Default` and often target the same
 * file, so overlapping `analyze` calls corrupted the session lifecycle
 * (`KaInaccessibleLifetimeOwnerAccessException: ... Called outside an \`analyze\` context.`).
 * [AnalysisScheduler] serializes access; it is priority-aware, preemptive (via [cancelChecker]) and
 * reentrant. **All** Analysis API access must go through this helper (or [analyzeMaybeDangling]); never
 * call `analyze` / `analyzeCopy` directly, or the serialization guarantee is lost.
 *
 * **Cancellation.** [action] runs with a [kotlinx.coroutines.Job] installed in the thread's IntelliJ
 * context; the compiler's dense `checkCanceled()` calls throw once that Job is cancelled, aborting
 * *mid*-`analyze` rather than only at the coarse [ScheduledCancelChecker.abortIfCancelled] checkpoints
 * (a [CancelCheckerProgressIndicator] is installed as a fallback). On [ProcessCanceledException] we
 * re-derive the typed exception callers expect ([AnalysisPreemptedException] when preempted, else the
 * delegate's `CancellationException`) so preempted work is rescheduled, not silently dropped.
 *
 * **Footgun:** analysis holds the *read* side of the non-upgradeable
 * [com.itsaky.androidide.lsp.kotlin.compiler.read] lock, so code inside an `analyze` block must never
 * call [com.itsaky.androidide.lsp.kotlin.compiler.write] — read → write on the same thread deadlocks.
 */
internal inline fun <R> withAnalysisLock(
	priority: AnalysisPriority,
	cancelChecker: ScheduledCancelChecker,
	crossinline action: () -> R,
): R {
	val indicator = CancelCheckerProgressIndicator(cancelChecker)

	// Cancelling [job] is what aborts analysis *mid*-`analyze`: the embeddable `checkCanceled()` throws
	// unconditionally the moment this thread's installed Job is cancelled. [indicator] is only a fallback
	// for environments that run the ~10ms indicator poll — this (Android, embeddable) one does not.
	val job = Job()

	return AnalysisScheduler.withLock(
		priority,
		cancelChecker,
		onPreempt = {
			// Flipping the checker only signals preempt; the invokeOnCancel listener below does the abort.
			cancelChecker.preempt()
		},
	) {
		// Single push path for both preemption and editor cancellation: fires immediately, no polling
		// and so unaffected by GC pauses that would stall a poll thread.
		cancelChecker.invokeOnCancel {
			indicator.cancel()
			job.cancel()
		}
		val holder = arrayOfNulls<Any?>(1)
		try {
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
		holder[0] as R
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

/**
 * True when [this] signals an analysis was cancelled or preempted rather than genuinely failing.
 *
 * A cancelled analysis surfaces as different types depending on where it was observed: a
 * [CancellationException] (which also covers [AnalysisPreemptedException], thrown at a
 * [ScheduledCancelChecker.abortIfCancelled] checkpoint), a [ProcessCanceledException] raised
 * mid-`analyze`, or an [InterruptedException] on an interrupted worker thread. All mean
 * "superseded/cancelled"; callers treat them uniformly so none is logged as a spurious error.
 */
internal fun Throwable.isAnalysisCancellation(): Boolean =
	this is CancellationException ||
		this is ProcessCanceledException ||
		this is InterruptedException
