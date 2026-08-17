package org.appdevforall.cotg.quickbuild.domain.session

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.junit.jupiter.api.Test

class SessionReducerTest {
	private val reducer = SessionReducer()

	@Test
	fun `idle plus QuickBuildTapped starts provisioning`() {
		val transition = reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartProvisioning))
	}

	@Test
	fun `idle ignores a late BuildSucceeded event`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.BuildSucceeded(3, 100))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `provisioning succeeded becomes ready and starts the background warm compile`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.ProvisioningSucceeded(1))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1, lastFailure = null))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartWarmCompile))
	}

	@Test
	fun `warm compile finished returns building to ready at the unchanged generation`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(deployedGeneration = 4, warmingCompiler = true),
				SessionEvent.WarmCompileFinished,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(4, lastFailure = null))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `warm compile started moves ready into a warm-compiling building state`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(4), SessionEvent.WarmCompileStarted)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Building(4, warmingCompiler = true))
		assertThat(transition.effects).isEmpty()
	}

	// A tap during a warm compile must not vanish - a warm compile deploys nothing, so nothing
	// else will satisfy it. The orchestrator decides whether it builds (dirty tap) or just
	// switches (clean tap); the reducer only routes it there.
	@Test
	fun `a tap during the warm compile triggers a build instead of being dropped`() {
		val warmCompiling = QuickBuildSessionState.Building(4, warmingCompiler = true)
		val transition = reducer.reduce(warmCompiling, SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(warmCompiling)
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.TriggerLiveReload(userInitiated = true)))
	}

	// A crash of the RUNNING generation during the warm-compile
	// window must surface like it does outside it - the warm compile's silent-outcome contract
	// covers warm-compile results, not crashes.
	@Test
	fun `a proxy-app crash during the warm compile is carried and surfaced when it finishes`() {
		val warmCompiling = QuickBuildSessionState.Building(4, warmingCompiler = true)
		val crashed = reducer.reduce(warmCompiling, SessionEvent.ProxyAppCrashed("NPE in onCreate"))

		assertThat(crashed.state)
			.isEqualTo(
				QuickBuildSessionState.Building(
					4,
					warmingCompiler = true,
					pendingCrash = SessionFailure.ProxyAppCrash("NPE in onCreate"),
				),
			)
		assertThat(crashed.effects).isEmpty()

		val finished = reducer.reduce(crashed.state, SessionEvent.WarmCompileFinished)

		assertThat(finished.state)
			.isEqualTo(
				QuickBuildSessionState.Ready(
					4,
					lastFailure = SessionFailure.ProxyAppCrash("NPE in onCreate"),
				),
			)
		assertThat(finished.effects).isEmpty()
	}

	@Test
	fun `warm compile finished is a no-op outside building`() {
		val ready = QuickBuildSessionState.Ready(2)
		val transition = reducer.reduce(ready, SessionEvent.WarmCompileFinished)

		assertThat(transition.state).isEqualTo(ready)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `provisioning failed returns to idle and surfaces the error`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.ProvisioningFailed(QuickBuildMessage.Literal("boom")))

		// lastStartFailed keeps the error tone on the bolt until the next tap or save (Q8).
		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.SurfaceProvisioningError(QuickBuildMessage.Literal("boom"))))
	}

	@Test
	fun `provisioning ignores a QuickBuildTapped event`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus QuickBuildTapped stays ready and triggers a build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(1), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.TriggerLiveReload(userInitiated = true)))
	}

	@Test
	fun `deployed plus QuickBuildTapped stays deployed and triggers a build`() {
		// A tap on an already-deployed generation is the forced-redeploy path, so it must
		// behave exactly like the Ready one. Ready and Deployed share a handler today; this
		// pins the Deployed half so splitting them cannot silently drop the tap.
		val transition =
			reducer.reduce(QuickBuildSessionState.Deployed(2, 500), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(2, 500))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.TriggerLiveReload(userInitiated = true)))
	}

	// The tap's one bit (whether its save-all wrote anything) must reach the orchestrator, or
	// a dirty-buffer tap would be treated as a do-nothing tap and switch before its build.
	@Test
	fun `a tap that wrote something carries the bit into the trigger effect - from every live state`() {
		val liveStates =
			listOf(
				QuickBuildSessionState.Ready(1),
				QuickBuildSessionState.Deployed(2, 500),
				QuickBuildSessionState.Building(4, warmingCompiler = true),
			)

		for (state in liveStates) {
			val transition = reducer.reduce(state, SessionEvent.QuickBuildTapped(wroteSomething = true))

			assertThat(transition.state).isEqualTo(state)
			assertThat(transition.effects)
				.isEqualTo(listOf(SessionEffect.TriggerLiveReload(userInitiated = true, expectChanges = true)))
		}
	}

	// States that do not trigger a live reload ignore the bit: the tap means the same thing
	// with or without a preceding write there, so the transitions must be identical.
	@Test
	fun `states that do not trigger a reload treat a clean and a dirty tap identically`() {
		val states =
			listOf(
				QuickBuildSessionState.Idle(),
				QuickBuildSessionState.Prebuilding(),
				QuickBuildSessionState.Provisioning(),
				QuickBuildSessionState.Building(3),
				QuickBuildSessionState.Invalidated(
					InvalidationReason.MANIFEST_CHANGED,
					1,
					awaitingRetry = true,
				),
				QuickBuildSessionState.Degraded(3),
			)

		for (state in states) {
			val clean = reducer.reduce(state, SessionEvent.QuickBuildTapped(wroteSomething = false))
			val dirty = reducer.reduce(state, SessionEvent.QuickBuildTapped(wroteSomething = true))

			assertThat(dirty.state).isEqualTo(clean.state)
			assertThat(dirty.effects).isEqualTo(clean.effects)
		}
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
	fun `building plus a deploy failure stays Ready - a failed relaunch retry must not tear the session down`() {
		// Defect #88 tail: when the launch-and-retry-once recovery also fails, the
		// outcome is a plain DeployFailure -> DeployError, and the session stays Ready
		// so the user can relaunch the app and simply save again.
		val failure = SessionFailure.DeployError("Proxy app is not connected. Relaunch your app to reconnect, then deploy again.")

		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.BuildFailed(failure))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1, lastFailure = failure))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus InvalidationDetected requires a full gradle proxy app rebuild`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(1),
				SessionEvent.InvalidationDetected(InvalidationReason.MANIFEST_CHANGED),
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunProxyAppRebuild))
	}

	@Test
	fun `ready plus InvalidationDetected requires a full gradle proxy app rebuild`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Ready(1),
				SessionEvent.InvalidationDetected(InvalidationReason.GRADLE_CONFIG_CHANGED),
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunProxyAppRebuild))
	}

	@Test
	fun `invalidated plus ProxyAppRebuildStarted moves to provisioning carrying the reason`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1),
				SessionEvent.ProxyAppRebuildStarted,
			)

		// The reason travels so the status surfaces can say "rebuilding your app" without
		// having observed the Invalidated hop - which, on a conflating StateFlow, they usually
		// have not.
		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Provisioning(rebaselineReason = InvalidationReason.MANIFEST_CHANGED),
			)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `an unconfirmed proxy app rebuild install parks in invalidated awaiting retry - not idle`() {
		// The stranded-session fix: the proxy app rebuild built fine, only the reinstall
		// confirmation timed out. No effect fires (an automatic retry would re-prompt
		// forever); the session waits for the user's tap instead of dying to Idle.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(),
				SessionEvent.ProxyAppRebuildInstallNotConfirmed(deployedGeneration = 2),
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
	fun `a deferred proxy app rebuild retry parks back and gives its auto-retry back`() {
		// The retry asks for the device's single Gradle slot; if CoGo's own project sync
		// holds it, no build runs and no install is prompted. Charging the budget for that
		// spends the one retry the park depends on and drops the session to Idle behind a
		// "Proxy app rebuild failed" banner.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(installAutoRetries = 1),
				SessionEvent.ProxyAppRebuildDeferred(deployedGeneration = 2),
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Invalidated(
					InvalidationReason.INSTALL_NOT_CONFIRMED,
					2,
					awaitingRetry = true,
					installAutoRetries = 0,
				),
			)
		// No effect: retrying immediately would just hit the same busy slot. The next
		// foreground return or tap runs it.
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a deferred proxy app rebuild retry never drives the auto-retry count below zero`() {
		// A TAP-initiated retry arrives with the budget already reset to 0, so the
		// give-back has nothing to give.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(installAutoRetries = 0),
				SessionEvent.ProxyAppRebuildDeferred(deployedGeneration = 7),
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Invalidated(
					InvalidationReason.INSTALL_NOT_CONFIRMED,
					7,
					awaitingRetry = true,
					installAutoRetries = 0,
				),
			)
	}

	@Test
	fun `deferrals do not lift the auto-retry cap - real attempts still bound it`() {
		// The give-back must not become an unbounded budget: a deferral costs nothing, but
		// the attempts that DO run a Gradle build still count up to the cap.
		var state: QuickBuildSessionState =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
			)

		// One deferred attempt: parked again, budget untouched.
		state = reducer.reduce(state, SessionEvent.HostForegrounded).state
		state = reducer.reduce(state, SessionEvent.ProxyAppRebuildStarted).state
		state = reducer.reduce(state, SessionEvent.ProxyAppRebuildDeferred(deployedGeneration = 2)).state
		assertThat((state as QuickBuildSessionState.Invalidated).installAutoRetries).isEqualTo(0)

		// Then MAX real attempts, each ending unconfirmed: the budget fills up.
		repeat(SessionReducer.MAX_INSTALL_AUTO_RETRIES) {
			state = reducer.reduce(state, SessionEvent.HostForegrounded).state
			state = reducer.reduce(state, SessionEvent.ProxyAppRebuildStarted).state
			state =
				reducer
					.reduce(state, SessionEvent.ProxyAppRebuildInstallNotConfirmed(deployedGeneration = 2))
					.state
		}
		assertThat((state as QuickBuildSessionState.Invalidated).installAutoRetries)
			.isEqualTo(SessionReducer.MAX_INSTALL_AUTO_RETRIES)

		// Capped: the next foreground return runs nothing and stays parked.
		val exhausted = reducer.reduce(state, SessionEvent.HostForegrounded)
		assertThat(exhausted.state).isEqualTo(state)
		assertThat(exhausted.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus QuickBuildTapped retries the proxy app rebuild once`() {
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.QuickBuildTapped())

		// awaitingRetry drops with the effect, so a second tap before ProxyAppRebuildStarted
		// cannot double-run the Gradle build. The tap also asks to see the app - recorded here,
		// held by the shell until the rebuild lands, and dropped if it does not. HostForegrounded
		// (below) does not ask: nobody pressed anything, so it must not move the user.
		assertThat(transition.state).isEqualTo(parked.copy(awaitingRetry = false))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.RunProxyAppRebuild, SessionEffect.SwitchToProxyApp))
	}

	@Test
	fun `invalidated awaiting retry plus HostForegrounded retries the proxy app rebuild once`() {
		// The backgrounded-CoGo case: the reinstall ran with no dialog ever shown
		// (Android defers PENDING_USER_ACTION until foreground, and the dialog-owning
		// subscriber is lifecycle-bound), so the user's return to CoGo must re-prompt
		// without requiring a tap they don't know to make. The retry spends one unit
		// of the bounded auto-retry budget.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.HostForegrounded)

		assertThat(transition.state)
			.isEqualTo(parked.copy(awaitingRetry = false, installAutoRetries = 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunProxyAppRebuild))
	}

	@Test
	fun `HostForegrounded stops auto-retrying once the budget is spent - stays parked`() {
		// A user who keeps declining must not pay a fresh Gradle build on every
		// resume, forever (defect #90). Past the cap the session just stays parked.
		val exhausted =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
				installAutoRetries = SessionReducer.MAX_INSTALL_AUTO_RETRIES,
			)

		val transition = reducer.reduce(exhausted, SessionEvent.HostForegrounded)

		assertThat(transition.state).isEqualTo(exhausted)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a failed proxy app rebuild parks recoverable instead of dying to idle`() {
		// The rebaseline defect from manual QA: a broken build file (compileSdk the device has
		// no platform for) dropped the session to Idle, so a source compile error was
		// recoverable while a Gradle config error was terminal.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(),
				SessionEvent.ProxyAppRebuildFailed(
					InvalidationReason.GRADLE_CONFIG_CHANGED,
					deployedGeneration = 3,
				),
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Invalidated(
					InvalidationReason.GRADLE_CONFIG_CHANGED,
					3,
					awaitingRetry = true,
				),
			)
		// No effect: SurfaceProvisioningError would tear the session down, and an automatic
		// retry would just rebuild the same broken file.
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a failed proxy app rebuild CARRIES the auto-retry count - it does not refund it`() {
		// A build file the user has not fixed must not buy a fresh budget of Gradle builds on
		// every return to the editor.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(installAutoRetries = 2),
				SessionEvent.ProxyAppRebuildFailed(
					InvalidationReason.GRADLE_CONFIG_CHANGED,
					deployedGeneration = 3,
				),
			)

		assertThat((transition.state as QuickBuildSessionState.Invalidated).installAutoRetries)
			.isEqualTo(2)
	}

	@Test
	fun `saving the fix for a failed rebuild retries it - the user never leaves the editor`() {
		// The other half of the rebaseline defect: reverting the bad compileSdk left the
		// session stuck, because the park only listened for a tap or a foreground return and
		// neither was coming - the user stayed in the editor the whole time. The save IS the
		// recovery gesture.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.GRADLE_CONFIG_CHANGED,
				3,
				awaitingRetry = true,
				installAutoRetries = 2,
			)

		val transition =
			reducer.reduce(
				parked,
				SessionEvent.InvalidationDetected(InvalidationReason.GRADLE_CONFIG_CHANGED),
			)

		// A changed file is a genuinely new attempt, so the budget resets; awaitingRetry drops
		// with the effect so a second save cannot double-run the Gradle build.
		assertThat(transition.state).isEqualTo(parked.copy(awaitingRetry = false, installAutoRetries = 0))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunProxyAppRebuild))
	}

	@Test
	fun `a save while a proxy app rebuild is in flight does not start a second one`() {
		val inFlight =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.GRADLE_CONFIG_CHANGED,
				3,
				awaitingRetry = false,
			)

		val transition =
			reducer.reduce(
				inFlight,
				SessionEvent.InvalidationDetected(InvalidationReason.MANIFEST_CHANGED),
			)

		assertThat(transition.state).isEqualTo(inFlight)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a Quick Build tap retries even with the auto-retry budget spent and re-arms it`() {
		// An explicit tap is fresh consent: it always re-prompts and resets the
		// HostForegrounded budget.
		val exhausted =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
				installAutoRetries = SessionReducer.MAX_INSTALL_AUTO_RETRIES,
			)

		val transition = reducer.reduce(exhausted, SessionEvent.QuickBuildTapped())

		assertThat(transition.state)
			.isEqualTo(exhausted.copy(awaitingRetry = false, installAutoRetries = 0))
		// The tap is also a request to see the app, recorded here and held by the shell until
		// the rebuild lands - a rebaseline must not hand the user their stale app mid-build.
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.RunProxyAppRebuild, SessionEffect.SwitchToProxyApp))
	}

	@Test
	fun `the auto-retry count survives the park - retry - park round trip`() {
		// The budget is per unconfirmed install, not per park: it rides Invalidated ->
		// Provisioning (ProxyAppRebuildStarted) -> Invalidated (ProxyAppRebuildInstallNotConfirmed).
		// Without the carry, every park would reset the count and the cap could never
		// be reached.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.INSTALL_NOT_CONFIRMED,
				2,
				awaitingRetry = true,
			)

		val retried = reducer.reduce(parked, SessionEvent.HostForegrounded)
		val provisioning = reducer.reduce(retried.state, SessionEvent.ProxyAppRebuildStarted)
		assertThat(provisioning.state)
			.isEqualTo(
				QuickBuildSessionState.Provisioning(
					installAutoRetries = 1,
					rebaselineReason = InvalidationReason.INSTALL_NOT_CONFIRMED,
				),
			)

		val reParked =
			reducer.reduce(
				provisioning.state,
				SessionEvent.ProxyAppRebuildInstallNotConfirmed(deployedGeneration = 2),
			)
		assertThat(reParked.state)
			.isEqualTo(
				QuickBuildSessionState.Invalidated(
					InvalidationReason.INSTALL_NOT_CONFIRMED,
					2,
					awaitingRetry = true,
					installAutoRetries = 1,
				),
			)

		// The second foreground return spends the last unit; the third does nothing.
		val secondRetry = reducer.reduce(reParked.state, SessionEvent.HostForegrounded)
		assertThat(secondRetry.effects).isEqualTo(listOf(SessionEffect.RunProxyAppRebuild))
		val secondProvisioning = reducer.reduce(secondRetry.state, SessionEvent.ProxyAppRebuildStarted)
		val secondPark =
			reducer.reduce(
				secondProvisioning.state,
				SessionEvent.ProxyAppRebuildInstallNotConfirmed(deployedGeneration = 2),
			)
		val thirdAttempt = reducer.reduce(secondPark.state, SessionEvent.HostForegrounded)
		assertThat(thirdAttempt.state).isEqualTo(secondPark.state)
		assertThat(thirdAttempt.effects).isEmpty()
	}

	@Test
	fun `invalidated with a proxy app rebuild in flight ignores HostForegrounded`() {
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
			QuickBuildSessionState.Idle(),
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
	fun `invalidated with a proxy app rebuild in flight ignores QuickBuildTapped`() {
		val invalidated = QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1)

		val transition = reducer.reduce(invalidated, SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(invalidated)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `retried proxy app rebuild start moves the parked session to provisioning`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Invalidated(InvalidationReason.INSTALL_NOT_CONFIRMED, 2),
				SessionEvent.ProxyAppRebuildStarted,
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Provisioning(rebaselineReason = InvalidationReason.INSTALL_NOT_CONFIRMED),
			)
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

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
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

		// Still no auto-retry - that is deliberate, and the no-spin property. What changed is
		// that the state stops claiming a restart is under way, since nothing is running one.
		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Degraded(1, restartFailed = true))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `degraded plus DaemonRestartFailed records the failure and schedules nothing`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.DaemonRestartFailed)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Degraded(1, restartFailed = true))
		assertThat(transition.effects).isEmpty()
		// The status is the whole point of the flag: "restarting" was a claim about work that
		// had already failed.
		assertThat(QuickBuildStatus.from(transition.state))
			.isEqualTo(QuickBuildStatus.Reconnecting(1, restartFailed = true))
	}

	@Test
	fun `a failed restart is not undone by a stale DaemonRespawned for the daemon that died`() {
		// The second-death race: the respawned child died between start() returning Ok and this
		// event landing, so its death was recorded first. Going Ready here would announce a live
		// compiler that is already gone.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Degraded(1, restartFailed = true),
				SessionEvent.DaemonRespawned,
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Degraded(1, restartFailed = true))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a tap after a failed restart clears the flag, so the status is honest again`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Degraded(3, restartFailed = true),
				SessionEvent.QuickBuildTapped(),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(3))
		assertThat(transition.effects)
			.isEqualTo(
				listOf(
					SessionEffect.SurfaceMessage(QuickBuildMessage.DaemonRestartRetrying),
					SessionEffect.RespawnDaemon,
				),
			)
		// And a respawn that now succeeds is believed again.
		assertThat(reducer.reduce(transition.state, SessionEvent.DaemonRespawned).state)
			.isEqualTo(QuickBuildSessionState.Ready(3))
	}

	@Test
	fun `only degraded acts on DaemonRestartFailed`() {
		// It records a fact about a respawn, and no other state has one in flight.
		val states =
			listOf(
				QuickBuildSessionState.Ready(1),
				QuickBuildSessionState.Building(1),
				QuickBuildSessionState.Deployed(1, 5),
				QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1),
				QuickBuildSessionState.Idle(),
			)

		for (state in states) {
			val transition = reducer.reduce(state, SessionEvent.DaemonRestartFailed)

			assertThat(transition.state).isEqualTo(state)
			assertThat(transition.effects).isEmpty()
		}
	}

	@Test
	fun `deployed plus ProxyAppCrashed falls back to ready with the crash recorded`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Deployed(2, 500),
				SessionEvent.ProxyAppCrashed("NPE in onCreate"),
			)

		assertThat(transition.state)
			.isEqualTo(
				QuickBuildSessionState.Ready(
					2,
					lastFailure = SessionFailure.ProxyAppCrash("NPE in onCreate"),
				),
			)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `building plus ProxyAppCrashed stays building while the next build runs`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.ProxyAppCrashed("crash"))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(1))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `idle plus PrebuildRequested starts the eager proxy app build`() {
		val transition = reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.PrebuildRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = false))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartProxyAppPrebuild))
	}

	@Test
	fun `prebuilding finished without a tap returns to idle - install is deferred`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Prebuilding(), SessionEvent.PrebuildFinished)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `tap during prebuilding queues instead of racing the warm build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Prebuilding(), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = true))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `prebuilding finished with a queued tap starts provisioning`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Prebuilding(tapQueued = true),
				SessionEvent.PrebuildFinished,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartProvisioning))
	}

	@Test
	fun `prebuild requested while a session is live is a no-op`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(2), SessionEvent.PrebuildRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(2))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `prebuild requested while prebuilding does not start a second warm build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Prebuilding(), SessionEvent.PrebuildRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prebuilding())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus ExternalBuildCompleted stays ready and refreshes the baseline`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(2), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(2))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RefreshBaseline))
	}

	@Test
	fun `deployed plus ExternalBuildCompleted refreshes the baseline`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Deployed(3, 700), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(3, 700))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RefreshBaseline))
	}

	@Test
	fun `building plus ExternalBuildCompleted coalesces the refresh into the follow-up build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RefreshBaseline))
	}

	@Test
	fun `degraded plus ExternalBuildCompleted refreshes the baseline`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RefreshBaseline))
	}

	@Test
	fun `idle plus ExternalBuildCompleted does nothing - no session to refresh`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated plus ExternalBuildCompleted does nothing - the proxy app rebuild absorbs it`() {
		val invalidated = QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1)
		val transition = reducer.reduce(invalidated, SessionEvent.ExternalBuildCompleted)

		assertThat(transition.state).isEqualTo(invalidated)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `degraded plus InvalidationDetected proxy app rebuilds instead of stranding the session`() {
		// Regression: the orchestrator reports an invalidation ONCE. Dropping it while
		// Degraded meant no proxy app rebuild would ever run and no build could ever start again.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Degraded(1),
				SessionEvent.InvalidationDetected(InvalidationReason.GRADLE_CONFIG_CHANGED),
			)

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 1))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RunProxyAppRebuild))
	}

	@Test
	fun `idle plus SessionRestartRequested is a no-op - nothing to tear down`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `ready plus SessionRestartRequested tears down and returns to idle`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Ready(3), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	@Test
	fun `building plus SessionRestartRequested tears down mid-build`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(1), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	@Test
	fun `degraded plus SessionRestartRequested tears down instead of waiting on a respawn`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(1), SessionEvent.SessionRestartRequested)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	@Test
	fun `prebuilding plus SessionRestartRequested tears down the warm-up`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Prebuilding(tapQueued = true),
				SessionEvent.SessionRestartRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownSession))
	}

	// The user-facing restart (T15). Resting at Idle is what made the menu item read as dead:
	// Hidden and a settled session share the READY tone, so nothing on screen changed, and the
	// fresh proxy app build that three notices name as the remedy never ran.

	@Test
	fun `ready plus SessionRestartAndReprovisionRequested tears down and provisions in one step`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Ready(3),
				SessionEvent.SessionRestartAndReprovisionRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownAndProvision))
	}

	@Test
	fun `idle plus SessionRestartAndReprovisionRequested provisions with nothing to tear down`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Idle(),
				SessionEvent.SessionRestartAndReprovisionRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartProvisioning))
	}

	@Test
	fun `deployed plus SessionRestartAndReprovisionRequested restarts rather than resting at idle`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Deployed(4, 900),
				SessionEvent.SessionRestartAndReprovisionRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownAndProvision))
	}

	@Test
	fun `building plus SessionRestartAndReprovisionRequested restarts mid-build`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(1),
				SessionEvent.SessionRestartAndReprovisionRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownAndProvision))
	}

	@Test
	fun `degraded plus SessionRestartAndReprovisionRequested restarts instead of waiting on a respawn`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Degraded(1),
				SessionEvent.SessionRestartAndReprovisionRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownAndProvision))
	}

	@Test
	fun `invalidated plus SessionRestartAndReprovisionRequested restarts rather than rebaselining`() {
		// The restart is a fresh proxy app build from scratch, not the invalidated session's
		// rebaseline, so it must not carry the rebaseline reason into Provisioning - the
		// surfaces would then narrate it as "rebuilding because <reason>".
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 2),
				SessionEvent.SessionRestartAndReprovisionRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat((transition.state as QuickBuildSessionState.Provisioning).rebaselineReason).isNull()
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.TeardownAndProvision))
	}

	// Bryan's button spec (2026-07-29). The reducer owns two of the five decisions: WHO the
	// proxy app is brought forward for (behaviours 2/3), and what a stop does per state
	// (behaviour 5). The other three are shape/timing and live in the shell and the action.

	@Test
	fun `a user-initiated provision brings the proxy app forward when the session goes live`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(userInitiated = true),
				SessionEvent.ProvisioningSucceeded(1),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.StartWarmCompile, SessionEffect.SwitchToProxyApp))
	}

	@Test
	fun `a proxy app rebuild going live leaves the user in the editor`() {
		// Provisioning is also the proxy app rebuild's state, and a plain save can trigger one:
		// finishing a minute-long Gradle build is not an answer to anything the user asked.
		val transition =
			reducer.reduce(QuickBuildSessionState.Provisioning(), SessionEvent.ProvisioningSucceeded(1))

		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.StartWarmCompile))
	}

	@Test
	fun `a deploy the user asked for switches to the proxy app`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Building(1),
				SessionEvent.BuildSucceeded(2, 800, userInitiated = true),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(2, 800))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.SwitchToProxyApp))
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
		// The in-flight build deploys anyway, so the tap needs no build of its own - but
		// dropping it outright means a tap landing on a save-triggered build does nothing
		// the user can see. Mark, don't trigger: a second forced build behind one that
		// already deploys is a full recompile for nothing.
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(3), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(3))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.MarkBuildUserInitiated))
	}

	@Test
	fun `stopping a real build returns to ready with no failure recorded`() {
		val transition =
			reducer.reduce(QuickBuildSessionState.Building(4), SessionEvent.CancelRequested)

		// Ready at the generation the proxy app still runs, lastFailure null: a cancellation
		// the user chose must not render as the ATTENTION icon a broken build gets.
		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(4, lastFailure = null))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.CancelLiveReload))
	}

	@Test
	fun `stopping does nothing during the background warm compile`() {
		val warmCompiling = QuickBuildSessionState.Building(4, warmingCompiler = true)

		val transition = reducer.reduce(warmCompiling, SessionEvent.CancelRequested)

		assertThat(transition.state).isEqualTo(warmCompiling)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `stopping during provisioning cancels the Gradle proxy app build and tears down`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Provisioning(userInitiated = true),
				SessionEvent.CancelRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		// Order matters: the Gradle build has to be cancelled BEFORE the teardown cancels the
		// coroutine that is awaiting it, or nothing would ever reach the cancellation token.
		assertThat(transition.effects)
			.isEqualTo(listOf(SessionEffect.CancelProxyAppBuild, SessionEffect.TeardownSession))
	}

	@Test
	fun `stopping a queued tap during prebuild drops the tap and cancels the proxy app build`() {
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Prebuilding(tapQueued = true),
				SessionEvent.CancelRequested,
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.CancelProxyAppBuild))
	}

	@Test
	fun `stopping is a no-op in every state that does not own a build the user asked for`() {
		// The button only shows the stop affordance in the states above, but the shell
		// dispatches without checking - so every other state has to absorb it silently
		// rather than, say, tearing a live session down.
		for (state in listOf(
			QuickBuildSessionState.Idle(),
			QuickBuildSessionState.Prebuilding(tapQueued = false),
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

	// ADFA-4128 known issue #89 and the blocker it belongs to: reduceInvalidated and
	// reduceDegraded each ended in a silent `else` that swallowed events changing what the user
	// can do, so a session could reach a state where every save and every tap produced no build,
	// no message and no state change - "I saved my fix and nothing happened".

	@Test
	fun `degraded plus QuickBuildTapped retries the respawn and says so`() {
		// Catches: dropping QuickBuildTapped from reduceDegraded, or emitting RespawnDaemon with
		// no acknowledgement. A respawn already in flight answers with Superseded and reports
		// nothing, so the effect alone can still leave the tap looking ignored.
		val transition = reducer.reduce(QuickBuildSessionState.Degraded(3), SessionEvent.QuickBuildTapped())

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(3))
		assertThat(transition.effects)
			.isEqualTo(
				listOf(
					SessionEffect.SurfaceMessage(QuickBuildMessage.DaemonRestartRetrying),
					SessionEffect.RespawnDaemon,
				),
			)
	}

	@Test
	fun `degraded plus BuildStarted narrates the save's build instead of dropping it`() {
		// Catches: dropping BuildStarted from reduceDegraded. The watcher never stops, so a save
		// while the compiler is down still starts a build; staying Degraded left it invisible.
		val transition = reducer.reduce(QuickBuildSessionState.Degraded(3), SessionEvent.BuildStarted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(3))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `degraded plus BuildSucceeded reports the deploy that landed`() {
		// Catches: dropping BuildSucceeded from reduceDegraded. The daemon death listener can fire
		// mid-build, so a build can land while the session sits here; reporting the old generation
		// would be a lie the status surface carries until the next build.
		val transition =
			reducer.reduce(
				QuickBuildSessionState.Degraded(3),
				SessionEvent.BuildSucceeded(4, 900, restarted = false, userInitiated = true),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(4, 900))
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.SwitchToProxyApp))
	}

	@Test
	fun `degraded plus BuildFailed surfaces the failure at the unchanged generation`() {
		// Catches: dropping BuildFailed from reduceDegraded, or losing the diagnostics. A build
		// that reported diagnostics reached a working compiler, so Ready is honest.
		val failure =
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "unresolved reference", "A.kt", 3, 1)),
			)

		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(3), SessionEvent.BuildFailed(failure))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(3, failure))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `degraded ignores a warm compile so the respawn keeps the status`() {
		// Catches: routing WarmCompileStarted through Building, which would swap "restarting the
		// compiler" for "up to date" while the daemon is still down. Deliberate no-op.
		val started =
			reducer.reduce(QuickBuildSessionState.Degraded(3), SessionEvent.WarmCompileStarted)
		val finished =
			reducer.reduce(QuickBuildSessionState.Degraded(3), SessionEvent.WarmCompileFinished)

		assertThat(started.state).isEqualTo(QuickBuildSessionState.Degraded(3))
		assertThat(started.effects).isEmpty()
		assertThat(finished.state).isEqualTo(QuickBuildSessionState.Degraded(3))
		assertThat(finished.effects).isEmpty()
	}

	@Test
	fun `degraded plus ProxyAppCrashed keeps the respawn status - the notice carries the crash`() {
		// Catches: replacing the Reconnecting status with the crash. The manager flashes
		// RELOAD_CRASHED on every crash before dispatching this, so the user is told either way,
		// and a dead compiler is the more urgent of the two. Deliberate no-op.
		val transition =
			reducer.reduce(QuickBuildSessionState.Degraded(3), SessionEvent.ProxyAppCrashed("NPE"))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Degraded(3))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus BuildStarted narrates the build`() {
		// Catches: dropping BuildStarted from reduceInvalidated. A failed proxy app rebuild clears
		// the orchestrator's absorption gate, so a save it judges absorbable really does start a
		// quick build - and without narrating it the session reports "a full build is needed"
		// throughout.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.EXTERNAL_FULL_BUILD,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.BuildStarted)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Building(2))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus BuildSucceeded reports the deploy`() {
		// Catches: dropping BuildSucceeded from reduceInvalidated. Reached when the park and the
		// build raced, so no BuildStarted arrived here.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.EXTERNAL_FULL_BUILD,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.BuildSucceeded(3, 700))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Deployed(3, 700))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus BuildFailed surfaces the failure`() {
		// Catches: dropping BuildFailed from reduceInvalidated, which left a compile error the
		// user could fix in seconds invisible.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.EXTERNAL_FULL_BUILD,
				2,
				awaitingRetry = true,
			)
		val failure = SessionFailure.DeployError("not connected")

		val transition = reducer.reduce(parked, SessionEvent.BuildFailed(failure))

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Ready(2, failure))
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated with a proxy app rebuild in flight keeps the park through build events`() {
		// The other half of the awaitingRetry gate, and the reason it exists: with a rebuild in
		// flight, ProxyAppRebuildStarted still has to land here to move the session to
		// Provisioning. Catches a fix that narrates build events unconditionally - that would
		// leave a multi-minute Gradle build reading as "up to date" with the rebuild hop dropped.
		val rebuilding = QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 2)

		val started = reducer.reduce(rebuilding, SessionEvent.BuildStarted)
		val succeeded = reducer.reduce(rebuilding, SessionEvent.BuildSucceeded(3, 700))
		val failed =
			reducer.reduce(rebuilding, SessionEvent.BuildFailed(SessionFailure.DeployError("boom")))

		assertThat(started.state).isEqualTo(rebuilding)
		assertThat(succeeded.state).isEqualTo(rebuilding)
		assertThat(failed.state).isEqualTo(rebuilding)
		assertThat(started.effects).isEmpty()
		assertThat(succeeded.effects).isEmpty()
		assertThat(failed.effects).isEmpty()
	}

	@Test
	fun `invalidated awaiting retry plus DaemonDied respawns without leaving the park`() {
		// Catches: dropping DaemonDied from reduceInvalidated (every later save's quick build then
		// dies on a dead compiler and nothing ever moves again), and equally catches "fixing" it by
		// moving to Degraded, which would drop the reason and the retry the park exists to hold.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.GRADLE_CONFIG_CHANGED,
				2,
				awaitingRetry = true,
			)

		val transition = reducer.reduce(parked, SessionEvent.DaemonDied)

		assertThat(transition.state).isEqualTo(parked)
		assertThat(transition.effects).isEqualTo(listOf(SessionEffect.RespawnDaemon))
	}

	@Test
	fun `invalidated with a proxy app rebuild in flight does not respawn on DaemonDied`() {
		// The rebuild restarts the daemon itself (ProxyAppRebuildResult.DaemonRestartFailed), so a
		// respawn issued here would race it. Catches an unconditional respawn.
		val rebuilding = QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 2)

		val transition = reducer.reduce(rebuilding, SessionEvent.DaemonDied)

		assertThat(transition.state).isEqualTo(rebuilding)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `invalidated ignores DaemonRespawned, warm compiles and crashes - the park outranks them`() {
		// Deliberate no-ops. Catches a fix that clears the park on any of them: a working compiler
		// does not make a stale baseline fresh, a warm compile deploys nothing, and a crash is
		// already flashed as RELOAD_CRASHED by the manager.
		val parked =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.GRADLE_CONFIG_CHANGED,
				2,
				awaitingRetry = true,
			)

		val ignored =
			listOf(
				SessionEvent.DaemonRespawned,
				SessionEvent.WarmCompileStarted,
				SessionEvent.WarmCompileFinished,
				SessionEvent.ProxyAppCrashed("NPE"),
			)

		ignored.forEach { event ->
			val transition = reducer.reduce(parked, event)
			assertThat(transition.state).isEqualTo(parked)
			assertThat(transition.effects).isEmpty()
		}
	}
}
