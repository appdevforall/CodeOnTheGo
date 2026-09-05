package org.appdevforall.cotg.quickbuild.service.telemetry

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@PublishedApi
internal val metricsLog: Logger = LoggerFactory.getLogger("QB-Metrics")

/**
 * Runs a metrics call and swallows any failure into a logged warning, so metrics can
 * never break a build.
 *
 * Every metrics call in this package goes through here rather than relying on each class
 * to remember its own try/catch.
 *
 * Two kinds of throwable are NOT swallowed - see the catch clauses for why.
 *
 * @param block the metrics call; must be side-effect-free beyond reporting, since a
 *   partial run is swallowed and never retried
 */
internal inline fun report(block: () -> Unit) {
	try {
		block()
	} catch (e: CancellationException) {
		// This helper is inline, so a suspending call written inside the lambda compiles.
		// Swallowing its cancellation would run the caller's coroutine on past its own
		// cancellation, and nothing warns the author - there is no compile error to hit.
		throw e
	} catch (e: VirtualMachineError) {
		// The VM is out of a resource the rest of the build also needs. Logging it formats a
		// message and walks a stack trace, and the build then carries on reporting Success -
		// hiding the failure on exactly the low-spec devices this product exists for.
		throw e
	} catch (e: Throwable) {
		metricsLog.warn("Quick Build metrics sink failed", e)
	}
}
