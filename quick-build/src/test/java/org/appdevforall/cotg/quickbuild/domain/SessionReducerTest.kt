package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SessionReducerTest {
	private val reducer = SessionReducer()

	@Test
	fun `idle plus QuickBuildTapped starts provisioning`() {
		val transition = reducer.reduce(QuickBuildSessionState.Idle, SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
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
	fun `provisioning succeeded becomes ready and starts the background seed`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.ProvisioningSucceeded(1))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1, lastFailure = null))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartBackgroundSeed))
	}

	@Test
	fun `seed finished returns building to ready at the unchanged generation`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(deployedGeneration = 4, seeding = true),
				SessionEvent.SeedFinished,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(4, lastFailure = null))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `seed started moves ready into a seeding building state`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(4), SessionEvent.SeedStarted)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Building(4, seeding = true))
		assertThat(transition.effects).isEmpty()
	}

	// Review finding (2026-07-26 #3): a forced-redeploy tap during a seed must not
	// vanish - a seed deploys nothing, so nothing else will satisfy it.
	@Test
	fun `a tap during the seed triggers a build instead of being dropped`() {
		val seeding = QuickBuildSessionState.Building(4, seeding = true)
		val transition = reducer.reduce(seeding, SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(seeding)
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.TriggerQuickBuild(userInitiated = true)))
	}

	// Review finding (2026-07-26 #1): a crash of the RUNNING generation during the seed
	// window must surface like it does outside it - the seed's silent-outcome contract
	// covers seed results, not crashes.
	@Test
	fun `a test-app crash during the seed is carried and surfaced when the seed finishes`() {
		val seeding = QuickBuildSessionState.Building(4, seeding = true)
		val crashed = reducer.reduce(seeding, SessionEvent.TestAppCrashed("NPE in onCreate"))

		assertThat(crashed.state)
			.isEqualTo(
				QuickBuildSessionState.Building(
					4,
					seeding = true,
					pendingCrash = SessionFailure.TestAppCrash("NPE in onCreate"),
				),
			)
		assertThat(crashed.effects).isEmpty()

		val finished = reducer.reduce(crashed.state, SessionEvent.SeedFinished)

		assertThat(finished.state)
			.isEqualTo(
				QuickBuildSessionState.Ready(
					4,
					lastFailure = SessionFailure.TestAppCrash("NPE in onCreate"),
				),
			)
		assertThat(finished.effects).isEmpty()
	}

	@Test
	fun `seed finished is a no-op outside building`() {
		val ready = QuickBuildSessionState.Ready(2)
		val transition = reducer.reduce(ready, SessionEvent.SeedFinished)

		assertThat(transition.state).isEqualTo(ready)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `provisioning failed returns to idle and surfaces the error`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.ProvisioningFailed("boom"))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.SurfaceProvisioningError("boom")))
	}

	@Test
	fun `provisioning ignores a QuickBuildTapped event`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus QuickBuildTapped stays ready and triggers a build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(1), SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.TriggerQuickBuild(userInitiated = true)))
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

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `an unconfirmed rebaseline install parks in invalidated awaiting retry - not idle`() {
		// The stranded-session fix: the rebaseline built fine, only the reinstall
		// confirmation timed out. No effect fires (an automatic retry would re-prompt
		// forever); the session waits for the user's tap instead of dying to Idle.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(),
				SessionEvent.RebaselineInstallNotConfirmed(deployedGeneration = 2),
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Invalidated(
					InvalidationReason.INSTALL_NOT_CONFIRMED,
					2,
					awaitingRetry = true,
				),
			)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus QuickBuildTapped retries the rebaseline once`() {
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.QuickBuildTapped)

		// awaitingRetry drops with the effect, so a second tap before RebaselineStarted
		// cannot double-run the Gradle build.
		assertThat(transition.state).isEqualTo(parked.copy(awaitingRetry = false))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunFullGradleRebaseline))
	}

	@Test
	fun `invalidated awaiting retry plus HostForegrounded retries the rebaseline once`() {
		// The backgrounded-CoGo case: the reinstall timed out with no dialog ever shown
		// (PENDING_USER_ACTION is not delivered to a backgrounded app), so the user's
		// return to CoGo must re-prompt without requiring a tap they don't know to make.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.HostForegrounded)

		assertThat(transition.state).isEqualTo(parked.copy(awaitingRetry = false))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunFullGradleRebaseline))
	}

	@Test
	fun `invalidated with a rebaseline in flight ignores HostForegrounded`() {
		// After the retry fires (awaitingRetry dropped), a second onResume - e.g. the
		// user dismissing the re-prompted install dialog - must not double-run Gradle.
		val inFlight =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = false,
			)

		val transition = reducer.reduce(inFlight, SessionEvent.HostForegrounded)

		assertThat(transition.state).isEqualTo(inFlight)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `HostForegrounded is a no-op in non-parked states`() {
		for (state in listOf(
			QuickBuildSessionState.Idle,
			QuickBuildSessionState.Provisioning(),
			QuickBuildSessionState.Ready(1),
			QuickBuildSessionState.Building(1),
			QuickBuildSessionState.Deployed(1, buildDurationMillis = 100),
		)) {
			val transition = reducer.reduce(state, SessionEvent.HostForegrounded)
			assertThat(transition.state).isEqualTo(state)
			assertThat(transition.effects).isEmpty()
		}
	}

	@Test
	fun `invalidated with a rebaseline in flight ignores QuickBuildTapped`() {
		val invalidated = QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1)

		val transition = reducer.reduce(invalidated, SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(invalidated)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `retried rebaseline start moves the parked session to provisioning`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Invalidated(InvalidationReason.INSTALL_NOT_CONFIRMED, 2),
				SessionEvent.RebaselineStarted,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus SessionRestartRequested still tears down`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Invalidated(
					InvalidationReason.INSTALL_NOT_CONFIRMED,
					2,
					awaitingRetry = true,
				),
				SessionEvent.SessionRestartRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
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

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
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

	// Bryan's button spec (2026-07-29). The reducer owns two of the five decisions: WHO the
	// test app is brought forward for (behaviours 2/3), and what a stop does per state
	// (behaviour 5). The other three are shape/timing and live in the shell and the action.

	@Test
	fun `a user-initiated provision brings the test app forward when the session goes live`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(userInitiated = true),
				SessionEvent.ProvisioningSucceeded(1),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.StartBackgroundSeed, SessionEffect.SwitchToTestApp))
	}

	@Test
	fun `a rebaseline going live leaves the user in the editor`() {
		// Provisioning is also the rebaseline's state, and a plain save can trigger one:
		// finishing a minute-long Gradle build is not an answer to anything the user asked.
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.ProvisioningSucceeded(1))

		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartBackgroundSeed))
	}

	@Test
	fun `a deploy the user asked for switches to the test app`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(1),
				SessionEvent.BuildSucceeded(2, 800, userInitiated = true),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(2, 800))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.SwitchToTestApp))
	}

	@Test
	fun `a deploy a file write triggered leaves the user in the editor`() {
		// Behaviour 3: the same successful deploy, with nobody having asked for it.
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.BuildSucceeded(2, 800))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(2, 800))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a tap during a real build records the ask without forcing a second build`() {
		// It used to be dropped outright ("the in-flight build deploys anyway"), which was
		// true of the DEPLOY but lost the switch: a tap landing on a save-triggered build
		// silently did nothing the user could see. Marking, not triggering: a second forced
		// build behind one that already deploys is a full recompile for nothing.
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(3), SessionEvent.QuickBuildTapped)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(3))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.MarkBuildUserInitiated))
	}

	@Test
	fun `stopping a real build returns to ready with no failure recorded`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(4), SessionEvent.CancelRequested)

		// Ready at the generation the test app still runs, lastFailure null: a cancellation
		// the user chose must not render as the ATTENTION icon a broken build gets.
		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(4, lastFailure = null))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.CancelQuickBuild))
	}

	@Test
	fun `stopping does nothing during the background seed`() {
		val seeding = QuickBuildSessionState.Building(4, seeding = true)

		val transition = reducer.reduce(seeding, SessionEvent.CancelRequested)

		assertThat(transition.state).isEqualTo(seeding)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `stopping during provisioning cancels the Gradle setup build and tears down`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(userInitiated = true),
				SessionEvent.CancelRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		// Order matters: the Gradle build has to be cancelled BEFORE the teardown cancels the
		// coroutine that is awaiting it, or nothing would ever reach the cancellation token.
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.CancelSetupBuild, SessionEffect.TeardownSession))
	}

	@Test
	fun `stopping a queued tap during prewarm drops the tap and cancels the setup build`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Prewarming(tapQueued = true),
				SessionEvent.CancelRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.CancelSetupBuild))
	}

	@Test
	fun `stopping is a no-op in every state that does not own a build the user asked for`() {
		// The button only shows the stop affordance in the states above, but the shell
		// dispatches without checking - so every other state has to absorb it silently
		// rather than, say, tearing a live session down.
		for (state in listOf(
			QuickBuildSessionState.Idle,
			QuickBuildSessionState.Prewarming(tapQueued = false),
			QuickBuildSessionState.Ready(1),
			QuickBuildSessionState.Deployed(1, buildDurationMillis = 100),
			QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1),
			QuickBuildSessionState.Degraded(1),
		)) {
			val transition = reducer.reduce(state, SessionEvent.CancelRequested)
			assertThat(transition.state).isEqualTo(state)
			assertThat(transition.effects).isEmpty()
		}
	}
}
