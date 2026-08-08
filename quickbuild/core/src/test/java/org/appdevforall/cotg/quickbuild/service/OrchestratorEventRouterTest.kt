package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SessionEvent
import org.junit.jupiter.api.Test

/**
 * Seam tests for the orchestrator-fact -> session-event translation, directly
 * against [OrchestratorEventRouter] (the manager's tests drive the same paths
 * end-to-end; these pin the router's own branching).
 */
class OrchestratorEventRouterTest {
	private fun router(metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop) = OrchestratorEventRouter(metrics)

	private fun route(
		event: OrchestratorEvent,
		lastDeployedGeneration: Long = -1L,
		connectedGeneration: Long? = null,
		metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
	) = router(metrics).route(event, lastDeployedGeneration, connectedGeneration)

	private fun success(generation: Long = 7L) = BuildOutcome.Success(generation = generation, durationMillis = 120L)

	@Test
	fun `a warm-compile success emits WarmCompileFinished and does not advance the tally`() {
		val routing =
			route(
				OrchestratorEvent.BuildSucceeded(
					buildId = 1,
					result = success(),
					route = BuildRoute.WarmCompile,
				),
				lastDeployedGeneration = 3L,
			)
		assertThat(routing.sessionEvents).containsExactly(SessionEvent.WarmCompileFinished)
		assertThat(routing.newLastDeployedGeneration).isNull()
	}

	@Test
	fun `a real success advances the tally to the maxed generation`() {
		val routing =
			route(
				OrchestratorEvent.BuildSucceeded(
					buildId = 1,
					result = success(generation = 7L),
					route = BuildRoute.CodeOnly,
					userInitiated = true,
				),
				lastDeployedGeneration = 3L,
			)
		assertThat(routing.newLastDeployedGeneration).isEqualTo(7L)
		assertThat(routing.sessionEvents)
			.containsExactly(
				SessionEvent.BuildSucceeded(7L, 120L, restarted = false, userInitiated = true),
			)
	}

	@Test
	fun `a warm-compile failure emits WarmCompileFinished and no BuildFailed`() {
		val routing =
			route(
				OrchestratorEvent.BuildFailed(
					buildId = 1,
					outcome = BuildOutcome.InfrastructureFailure("compiler broke"),
					route = BuildRoute.WarmCompile,
				),
			)
		assertThat(routing.sessionEvents).containsExactly(SessionEvent.WarmCompileFinished)
	}

	@Test
	fun `a warm-compile failure with a dead daemon emits DaemonDied, not WarmCompileFinished`() {
		val routing =
			route(
				OrchestratorEvent.BuildFailed(
					buildId = 1,
					outcome = BuildOutcome.InfrastructureFailure("daemon gone", daemonDied = true),
					route = BuildRoute.WarmCompile,
				),
			)
		assertThat(routing.sessionEvents).containsExactly(SessionEvent.DaemonDied)
	}

	@Test
	fun `RequiresProxyAppRebuild routes to an invalidation and books the invalidation metric`() {
		var invalidations = 0
		val metrics =
			object : QuickBuildMetricsSink by QuickBuildMetricsSink.Noop {
				override fun onInvalidation(reason: InvalidationReason) {
					invalidations++
				}
			}
		val routing =
			route(
				OrchestratorEvent.BuildFailed(
					buildId = 1,
					outcome =
						BuildOutcome.RequiresProxyAppRebuild(
							InvalidationReason.MANIFEST_CHANGED,
							"manifest edit",
						),
					route = BuildRoute.CodeOnly,
				),
				metrics = metrics,
			)
		assertThat(routing.sessionEvents)
			.containsExactly(SessionEvent.InvalidationDetected(InvalidationReason.MANIFEST_CHANGED))
		assertThat(invalidations).isEqualTo(1)
	}

	@Test
	fun `notifyBuildingAt prefers the session tally over the connected target's self-report`() {
		val routing =
			route(
				OrchestratorEvent.BuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Unknown),
				lastDeployedGeneration = 9L,
				connectedGeneration = 4L,
			)
		assertThat(routing.notifyBuildingAt).isEqualTo(9L)
	}

	@Test
	fun `notifyBuildingAt falls back to the connected target only before the first deploy`() {
		val routing =
			route(
				OrchestratorEvent.BuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Unknown),
				lastDeployedGeneration = -1L,
				connectedGeneration = 4L,
			)
		assertThat(routing.notifyBuildingAt).isEqualTo(4L)
	}

	@Test
	fun `notifyBuildingAt is null when there is no tally and no connection`() {
		val routing =
			route(
				OrchestratorEvent.BuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Unknown),
				lastDeployedGeneration = -1L,
				connectedGeneration = null,
			)
		assertThat(routing.notifyBuildingAt).isNull()
	}

	@Test
	fun `a warm-compile start emits WarmCompileStarted and notifies nobody`() {
		val routing =
			route(
				OrchestratorEvent.BuildStarted(1, BuildRoute.WarmCompile, ChangedFiles.Unknown),
				lastDeployedGeneration = 9L,
				connectedGeneration = 4L,
			)
		assertThat(routing.sessionEvents).containsExactly(SessionEvent.WarmCompileStarted)
		assertThat(routing.notifyBuildingAt).isNull()
	}

	@Test
	fun `a throwing metrics sink does not stop the routing`() {
		val metrics =
			object : QuickBuildMetricsSink by QuickBuildMetricsSink.Noop {
				override fun onBuildFinished(
					buildId: Long,
					outcome: BuildOutcome,
				): Unit = throw IllegalStateException("sink broke")
			}
		val routing =
			route(
				OrchestratorEvent.BuildSucceeded(
					buildId = 1,
					result = success(),
					route = BuildRoute.CodeOnly,
				),
				metrics = metrics,
			)
		assertThat(routing.sessionEvents).hasSize(1)
		assertThat(routing.newLastDeployedGeneration).isEqualTo(7L)
	}
}
