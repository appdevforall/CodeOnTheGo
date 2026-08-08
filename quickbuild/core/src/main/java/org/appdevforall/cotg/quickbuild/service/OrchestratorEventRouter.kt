package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.SessionFailure
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
		 * Value the session's deploy tally must advance to before the events dispatch,
		 * already maxed against the current tally. Null leaves the tally alone.
		 */
		val newLastDeployedGeneration: Long? = null,
		/**
		 * Generation to tell the proxy app it is still running while a newer build
		 * compiles, so a slow build does not read as silence. Null when there is nothing
		 * truthful to say.
		 *
		 * Prefers the session's own deploy tally over the connected target's
		 * self-reported generation, which is only fresh at connect time and goes stale as
		 * soon as a hot swap lands without a rebind. Falls back to the connection's value
		 * before this session has deployed anything.
		 */
		val notifyBuildingAt: Long? = null,
	)

	/**
	 * Decides what one orchestrator event means for the session, and reports it to
	 * metrics.
	 *
	 * @param event the orchestrator fact to translate
	 * @param lastDeployedGeneration the session's own deploy tally; -1 before the first
	 *   deploy or when no session is live
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
