package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.SessionFailure
import org.slf4j.LoggerFactory

/**
 * The INBOUND adapter of the session shell: orchestrator facts in, session events
 * out - the mirror of the manager's `runEffect` (reducer effects out to real work).
 * Carries the translation branching that is otherwise buried in the manager: the
 * warm-compile special cases, RequiresProxyAppRebuild -> invalidation, daemon death
 * -> DaemonDied, and the building-notification honesty rule. Also the metrics
 * fan-out, guarded so a throwing sink can never affect a build.
 *
 * Pure decision + metrics: [route] mutates no session state and dispatches nothing -
 * the manager applies the returned [Routing] (tally advance first, then dispatch,
 * then the building notification).
 */
internal class OrchestratorEventRouter(
	private val metrics: QuickBuildMetricsSink,
) {
	/** What one orchestrator event translates to; the manager applies it in order. */
	data class Routing(
		val sessionEvents: List<SessionEvent> = emptyList(),
		/**
		 * Non-null: the session's deploy tally must advance to this value (already
		 * maxed against the current tally) before the events are dispatched.
		 */
		val newLastDeployedGeneration: Long? = null,
		/**
		 * Non-null: tell the proxy app it is one generation behind, running this one,
		 * while the new build compiles - the honesty line, so a slow build never
		 * reads as silence. Prefers [route]'s `lastDeployedGeneration` - the session's
		 * own tally, kept current across every hot-swap deploy - over the connected
		 * target's self-reported generation, which is only fresh at connect time and
		 * goes stale the moment a hot swap lands without a rebind. Falls back to the
		 * connection's value before this session has deployed anything (a fresh
		 * proxy-app install has no tally yet, but it did tell us its baseline
		 * generation at connect). Null when there is nothing truthful to say.
		 */
		val notifyBuildingAt: Long? = null,
	)

	/**
	 * @param lastDeployedGeneration the session's own deploy tally (-1 before the
	 *   first deploy, or when no session is live).
	 * @param connectedGeneration the bound proxy app's self-reported generation, or
	 *   null when none is connected.
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
					// A warm compile compiles the sources the proxy app ALREADY runs and
					// deploys nothing; telling either surface "one generation
					// behind, building" would be a lie. WarmCompileStarted keeps the IDE
					// status on "up to date" (Building(warmingCompiler = true)).
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
					// The build was fine but the baseline cannot take the deploy (a
					// restart-requiring change on a pre-restart baseline). Route into
					// the existing proxy-app-rebuild fallback; the orchestrator already put
					// the changed set back into pending, so the proxy app rebuild absorbs it.
					log.info("Quick build routed to a proxy app rebuild: {}", outcome.detail)
					report { metrics.onInvalidation(outcome.reason) }
					Routing(sessionEvents = listOf(SessionEvent.InvalidationDetected(outcome.reason)))
				} else if (outcome is BuildOutcome.InfrastructureFailure && outcome.daemonDied) {
					// Includes a daemon death mid-warm-compile: the normal respawn recovery
					// re-seeds with ChangedFiles.Unknown, so no warm-compile-specific path.
					Routing(sessionEvents = listOf(SessionEvent.DaemonDied))
				} else if (event.route is BuildRoute.WarmCompile) {
					// A failed warm compile is invisible by design: the proxy app build just
					// compiled these sources green, and the next real save compiles
					// the full source set anyway. Log for diagnosis, surface nothing.
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

	/** Metrics can never affect a build: a throwing sink degrades to a logged warning. */
	private inline fun report(block: () -> Unit) {
		try {
			block()
		} catch (e: Throwable) {
			log.warn("Quick Build metrics sink failed", e)
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(OrchestratorEventRouter::class.java)
	}
}
