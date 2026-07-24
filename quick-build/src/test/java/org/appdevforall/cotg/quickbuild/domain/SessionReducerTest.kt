package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SessionReducerTest {
	private val reducer = SessionReducer()

	@Test
	fun `idle plus QuickBuildTapped starts provisioning`() {
		val transition = reducer.reduce(QuickBuildSessionState.Idle, SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartProvisioning))
	}

	@Test
	fun `idle ignores a late BuildSucceeded event`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Idle, SessionEvent.BuildSucceeded(3, 100))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `provisioning succeeded becomes ready with no failure`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning, SessionEvent.ProvisioningSucceeded(1))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1, lastFailure = null))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `provisioning failed returns to idle and surfaces the error`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning, SessionEvent.ProvisioningFailed("boom"))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.SurfaceProvisioningError("boom")))
	}

	@Test
	fun `provisioning ignores a QuickBuildTapped event`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning, SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus QuickBuildTapped stays ready and triggers a build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(1), SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TriggerQuickBuild))
	}

	@Test
	fun `ready plus BuildStarted moves to building`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(1), SessionEvent.BuildStarted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(1))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `deployed plus BuildStarted moves to building at the deployed generation`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Deployed(2, 500), SessionEvent.BuildStarted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(2))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus BuildSucceeded deploys the new generation`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.BuildSucceeded(2, 800))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(2, 800))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus a restarted BuildSucceeded carries restarted into Deployed`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(1),
				SessionEvent.BuildSucceeded(2, 800, restarted = true),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(2, 800, restarted = true))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus BuildFailed stays on the old generation with the failure recorded`() {
		val failure =
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "msg", "A.kt", 1, 1)),
			)

		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.BuildFailed(failure))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1, lastFailure = failure))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus InvalidationDetected requires a full gradle rebaseline`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(1),
				SessionEvent.InvalidationDetected(InvalidationReason.MANIFEST_CHANGED),
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunFullGradleRebaseline))
	}

	@Test
	fun `ready plus InvalidationDetected requires a full gradle rebaseline`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Ready(1),
				SessionEvent.InvalidationDetected(InvalidationReason.GRADLE_CONFIG_CHANGED),
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunFullGradleRebaseline))
	}

	@Test
	fun `invalidated plus RebaselineStarted moves to provisioning`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1),
				SessionEvent.RebaselineStarted,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus DaemonDied degrades and respawns`() {
		val transition = reducer.reduce(QuickBuildSessionState.Ready(1), SessionEvent.DaemonDied)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RespawnDaemon))
	}

	@Test
	fun `building plus DaemonDied degrades and respawns`() {
		val transition = reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.DaemonDied)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RespawnDaemon))
	}

	@Test
	fun `degraded plus DaemonRespawned returns to ready`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.DaemonRespawned)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `degraded plus DaemonDied stays degraded without a duplicate respawn effect`() {
		val transition = reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.DaemonDied)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(1))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `deployed plus TestAppCrashed falls back to ready with the crash recorded`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Deployed(2, 500),
				SessionEvent.TestAppCrashed("NPE in onCreate"),
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Ready(
					2,
					lastFailure = SessionFailure.TestAppCrash("NPE in onCreate"),
				),
			)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus TestAppCrashed stays building while the next build runs`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.TestAppCrashed("crash"))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(1))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `idle plus PrewarmRequested starts the eager setup build`() {
		val transition = reducer.reduce(QuickBuildSessionState.Idle, SessionEvent.PrewarmRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prewarming(tapQueued = false))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartPrewarm))
	}

	@Test
	fun `prewarming finished without a tap returns to idle - install is deferred`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Prewarming(), SessionEvent.PrewarmFinished)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `tap during prewarming queues instead of racing the warm build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Prewarming(), SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prewarming(tapQueued = true))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `prewarming finished with a queued tap starts provisioning`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Prewarming(tapQueued = true),
				SessionEvent.PrewarmFinished,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartProvisioning))
	}

	@Test
	fun `prewarm requested while a session is live is a no-op`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(2), SessionEvent.PrewarmRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(2))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `prewarm requested while prewarming does not start a second warm build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Prewarming(), SessionEvent.PrewarmRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prewarming())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus ExternalBuildCompleted stays ready and re-seeds the baseline`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(2), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(2))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.ReseedBaseline))
	}

	@Test
	fun `deployed plus ExternalBuildCompleted re-seeds the baseline`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Deployed(3, 700), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(3, 700))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.ReseedBaseline))
	}

	@Test
	fun `building plus ExternalBuildCompleted re-seeds into the follow-up build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.ReseedBaseline))
	}

	@Test
	fun `degraded plus ExternalBuildCompleted re-seeds the baseline`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.ReseedBaseline))
	}

	@Test
	fun `idle plus ExternalBuildCompleted does nothing - no session to re-seed`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Idle, SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated plus ExternalBuildCompleted does nothing - the rebaseline absorbs it`() {
		val invalidated = QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1)
		val transition = reducer.reduce(invalidated, SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(invalidated)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `degraded plus InvalidationDetected rebaselines instead of stranding the session`() {
		// Regression: the orchestrator reports an invalidation ONCE. Dropping it while
		// Degraded meant no rebaseline would ever run and no build could ever start again.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Degraded(1),
				SessionEvent.InvalidationDetected(InvalidationReason.GRADLE_CONFIG_CHANGED),
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunFullGradleRebaseline))
	}

	@Test
	fun `idle plus SessionRestartRequested is a no-op - nothing to tear down`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Idle, SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus SessionRestartRequested tears down and returns to idle`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(3), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	@Test
	fun `building plus SessionRestartRequested tears down mid-build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	@Test
	fun `degraded plus SessionRestartRequested tears down instead of waiting on a respawn`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	@Test
	fun `prewarming plus SessionRestartRequested tears down the warm-up`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Prewarming(tapQueued = true),
				SessionEvent.SessionRestartRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}
}
