package org.appdevforall.cotg.quickbuild.service.session

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.junit.jupiter.api.Test

/**
 * The failure-outcome -> [SessionFailure] mapping arms [OrchestratorEventRouterTest]
 * leaves untouched: a deploy failure and an infrastructure failure with the daemon
 * still alive must both surface as a BuildFailed with the outcome's own message.
 */
class OrchestratorEventRouterEdgeTest {
	private fun route(event: OrchestratorEvent) =
		OrchestratorEventRouter(QuickBuildMetricsSink.Noop).route(event, lastDeployedGeneration = -1L, connectedGeneration = null)

	@Test
	fun `a deploy failure surfaces as BuildFailed carrying the deploy message`() {
		val routing =
			route(
				OrchestratorEvent.BuildFailed(
					buildId = 1,
					outcome = BuildOutcome.DeployFailure("proxy app not connected"),
					route = BuildRoute.CodeOnly,
				),
			)

		assertThat(routing.sessionEvents)
			.containsExactly(
				SessionEvent.BuildFailed(SessionFailure.DeployError("proxy app not connected")),
			)
		assertThat(routing.newLastDeployedGeneration).isNull()
	}

	@Test
	fun `an infrastructure failure with a live daemon is a plain BuildFailed, not DaemonDied`() {
		val routing =
			route(
				OrchestratorEvent.BuildFailed(
					buildId = 1,
					outcome = BuildOutcome.InfrastructureFailure("aapt2 crashed", daemonDied = false),
					route = BuildRoute.ResourcesOnly,
				),
			)

		assertThat(routing.sessionEvents)
			.containsExactly(SessionEvent.BuildFailed(SessionFailure.DeployError("aapt2 crashed")))
	}
}
