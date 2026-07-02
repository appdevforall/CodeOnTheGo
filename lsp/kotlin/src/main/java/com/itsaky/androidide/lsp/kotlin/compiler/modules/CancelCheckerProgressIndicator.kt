package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.AbstractProgressIndicatorBase

/**
 * A [com.intellij.openapi.progress.ProgressIndicator] whose cancellation state is driven by an
 * [ICancelChecker]. Installing it as the analysis thread's indicator (via
 * [withAnalysisLock]) is what makes the Kotlin Analysis API actually interruptible *mid*-`analyze`.
 *
 * The embeddable analysis API ships the full IntelliJ `CoreProgressManager`, whose
 * `ProgressManager.checkCanceled()` (called densely throughout FIR resolution) re-fetches the
 * current thread's indicator and rethrows its [checkCanceled]. By bridging that to [checker], a
 * preemption or ordinary cancellation aborts the running analysis at the compiler's next internal
 * checkpoint instead of only at the coarse LSP-level [ICancelChecker.abortIfCancelled] checks.
 *
 * This extends [AbstractProgressIndicatorBase] rather than `EmptyProgressIndicator` on purpose:
 * the base is a *non-standard* indicator, so `CoreProgressManager` runs a background task that
 * polls [checkCanceled] every ~10ms. That poll flips the manager's internal "should check
 * cancelled" flag to active once [checker] reports cancellation, which is what arms the in-`analyze`
 * checks. [cancel] (invoked synchronously when this analysis is preempted) flips the same flag
 * immediately, so preemption does not have to wait for the poll.
 */
internal class CancelCheckerProgressIndicator(
	private val checker: ICancelChecker,
) : AbstractProgressIndicatorBase() {

	override fun isCanceled(): Boolean = super.isCanceled() || checker.isCancelled()

	override fun checkCanceled() {
		if (checker.isCancelled()) {
			throw ProcessCanceledException()
		}
		super.checkCanceled()
	}
}
