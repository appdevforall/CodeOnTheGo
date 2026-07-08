package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.itsaky.androidide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.AbstractProgressIndicatorBase

/**
 * A progress indicator whose cancellation is driven by an [ICancelChecker]. Installing it as the
 * analysis thread's indicator (via [withAnalysisLock]) makes the Kotlin Analysis API interruptible
 * *mid*-`analyze`: the embeddable `CoreProgressManager.checkCanceled()` (called densely throughout
 * FIR resolution) rethrows this indicator's [checkCanceled], so a preemption or cancellation aborts
 * at the compiler's next internal checkpoint, not only at the coarse [ICancelChecker.abortIfCancelled].
 *
 * Extends [AbstractProgressIndicatorBase] (a *non-standard* indicator) on purpose: `CoreProgressManager`
 * then polls [checkCanceled] every ~10ms, arming its internal "check cancelled" flag once [checker]
 * reports cancellation. `cancel()` flips that flag immediately so preemption need not wait for the poll.
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
