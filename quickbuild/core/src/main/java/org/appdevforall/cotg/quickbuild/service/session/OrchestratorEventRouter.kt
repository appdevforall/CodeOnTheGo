package org.appdevforall.cotg.quickbuild.service.session

import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.service.telemetry.report
import org.slf4j.LoggerFactory

/**
 * Translates orchestrator events into session events, and reports each one to metrics.
 *
 * The inbound half of the session shell, mirroring the manager's `runEffect` on the way
 * out. It decides only: [route] mutates no session state and dispatches nothing, and the
 * manager applies the returned [Routing].
 *
 * @property metrics reported to for every event, through [report], so a failing sink can
 *   never change what the router decides
 */
internal class OrchestratorEventRouter(
	private val metrics: QuickBuildMetricsSink,
) {
	/**
	 * What one orchestrator event translates to. The manager applies the fields in
	 * declaration order: advance the tally, dispatch the events, then notify.
	 */
	data class Routing(
		/** Dispatched in order, after the tally advances; empty means the event is silent. */
		val sessionEvents: List<SessionEvent> = emptyList(),
		/**
		 * Value the session's deploy tally must advance to before the events dispatch, already
		 * maxed against the current tally; null leaves the tally alone.
		 */
		val newLastDeployedGeneration: Long? = null,
		/**
		 * Generation to tell the proxy app it is still running while a newer build compiles,
		 * preferring the session's own deploy tally over the connected target's self-reported
		 * generation - which is fresh only at connect time - and null when there is nothing
		 * truthful to say.
		 */
		val notifyBuildingAt: Long? = null,
	)

	/**
	 * Decides what one orchestrator event means for the session, and reports it to
	 * metrics.
	 *
	 * @param event the orchestrator fact to translate
	 * @param lastDeployedGeneration the session's own deploy tally, seeded with the
	 *   provisioned baseline's stamped generation before the first deploy; -1 when no
	 *   session is live
	 * @param connectedGeneration the bound proxy app's self-reported generation, or null
	 *   when none is connected
	 * @return what the manager must apply, in field-declaration order
	 */
	fun route(
		event: OrchestratorEvent,
		lastDeployedGeneration: Long,
		connectedGeneration: Long?,
	): Routing =
		when (event) {
			is OrchestratorEvent.BuildStarted -> {
				report { metrics.onBuildStarted(event.buildId, event.route, event.changes) }
				if (event.route is BuildRoute.WarmCompile) {
					// A warm compile recompiles what the proxy app already runs and
					// deploys nothing, so neither surface should say "building". This
					// event keeps the IDE status on "up to date".
					Routing(sessionEvents = listOf(SessionEvent.WarmCompileStarted))
				} else {
					Routing(
						sessionEvents = listOf(SessionEvent.BuildStarted),
						notifyBuildingAt =
							lastDeployedGeneration.takeIf { it >= 0 } ?: connectedGeneration,
					)
				}
			}

			is OrchestratorEvent.BuildSucceeded -> {
				report { metrics.onBuildFinished(event.buildId, event.result) }
				if (event.route is BuildRoute.WarmCompile) {
					// Nothing deployed, generation unmoved: no Deployed state, no
					// lastDeployedGeneration bump.
					Routing(sessionEvents = listOf(SessionEvent.WarmCompileFinished))
				} else {
					Routing(
						sessionEvents =
							listOf(
								SessionEvent.BuildSucceeded(
									event.result.generation,
									event.result.durationMillis,
									event.result.restarted,
									userInitiated = event.userInitiated,
								),
							),
						newLastDeployedGeneration =
							maxOf(lastDeployedGeneration, event.result.generation),
					)
				}
			}

			is OrchestratorEvent.BuildFailed -> {
				report { metrics.onBuildFinished(event.buildId, event.outcome) }
				val outcome = event.outcome
				if (outcome is BuildOutcome.RequiresProxyAppRebuild) {
					// The build was fine but the baseline cannot take the deploy. The
					// orchestrator already returned the changed set to pending, so the
					// proxy app rebuild absorbs it.
					log.info("Quick build routed to a proxy app rebuild: {}", outcome.detail)
					report { metrics.onInvalidation(outcome.reason) }
					Routing(sessionEvents = listOf(SessionEvent.InvalidationDetected(outcome.reason)))
				} else if (outcome is BuildOutcome.InfrastructureFailure && outcome.daemonDied) {
					// Includes a daemon death mid-warm-compile: the normal respawn recovery
					// re-seeds with ChangedFiles.Unknown, so no warm-compile-specific path.
					Routing(sessionEvents = listOf(SessionEvent.DaemonDied))
				} else if (event.route is BuildRoute.WarmCompile) {
					// A failed warm compile stays invisible: the proxy app build just
					// compiled these sources green, and the next real save compiles the
					// full source set anyway.
					log.warn("Background warm compile failed (not surfaced): {}", outcome)
					Routing(sessionEvents = listOf(SessionEvent.WarmCompileFinished))
				} else {
					Routing(sessionEvents = listOf(SessionEvent.BuildFailed(outcome.toSessionFailure())))
				}
			}

			is OrchestratorEvent.InvalidationRequired -> {
				// The event carries only the reason, not the paths that proved it - those
				// live on the orchestrator's pending set, which this router never sees.
				log.info("Quick build invalidated: {}", event.reason)
				report { metrics.onInvalidation(event.reason) }
				Routing(sessionEvents = listOf(SessionEvent.InvalidationDetected(event.reason)))
			}
		}

	/**
	 * Narrows a build outcome to the failure shape the session state carries.
	 *
	 * @return the user-facing failure; kept total over every outcome, so the two cases
	 *   that cannot reach here still map rather than throw
	 */
	private fun BuildOutcome.toSessionFailure(): SessionFailure =
		when (this) {
			is BuildOutcome.CompileError -> SessionFailure.CompileError(diagnostics)

			is BuildOutcome.DeployFailure -> SessionFailure.DeployError(message)

			is BuildOutcome.InfrastructureFailure -> SessionFailure.DeployError(message)

			// Handled as an invalidation before this mapping; keep it total anyway.
			is BuildOutcome.RequiresProxyAppRebuild -> SessionFailure.DeployError(detail)

			// Success never reaches BuildFailed; keep the mapping total anyway.
			is BuildOutcome.Success -> SessionFailure.DeployError("unexpected success in failure path")
		}

	private companion object {
		private val log = LoggerFactory.getLogger("QB-EventRouter")
	}
}
