package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.DeployDecision
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.junit.jupiter.api.Test

class PayloadDeployerTest {
	private val deploy = FakeDeploy()
	private val timelines = mutableListOf<E2eTimeline>()
	private val launchCalls = mutableListOf<Pair<String, String?>>()
	private var launchResult = true

	private fun deployer(
		proxyAppPackage: String? = "com.example.app",
		withLauncher: Boolean = true,
		userInitiated: Boolean = true,
	) = PayloadDeployer(
		deploy = deploy,
		generations = GenerationTracker(MemoryGenerationStore()),
		entryActivity = "com.example.app.MainActivity",
		proxyAppPackage = proxyAppPackage,
		launcherActivity = "com.example.app.Proxy0Activity",
		launcher =
			if (withLauncher) {
				ProxyAppLauncher { packageName, activityClass ->
					launchCalls += packageName to activityClass
					launchResult
				}
			} else {
				null
			},
		restartDisconnectTimeoutMillis = 5_000,
		restartReconnectTimeoutMillis = 15_000,
		clock = { 1_000 },
		reportTimeline = timelines::add,
		userInitiated = { userInitiated },
	)

	private fun recorder() = E2eTimelineRecorder(trigger = 0) { null }

	private val restart = DeployDecision.Restart(ComponentKind.SERVICE, "com.example.app.SyncService")

	private suspend fun deployRestart(deployer: PayloadDeployer): BuildOutcome =
		deployer.deploy(restart, null, null, null, "code", startedAt = 0, recorder = recorder())

	@Test
	fun `restart reconnect below the deployed generation requires a proxy app rebuild`() =
		runTest {
			deploy.result = DeployResult.Reloaded(40)
			deploy.reconnectGeneration = { deployed -> (deployed ?: 1) - 1 }
			val outcome = deployRestart(deployer())
			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
			assertThat((outcome as BuildOutcome.RequiresProxyAppRebuild).detail)
				.contains("did not persist")
		}

	@Test
	fun `restart reconnect at the deployed generation is a restarted success`() =
		runTest {
			deploy.result = DeployResult.Reloaded(40)
			val outcome = deployRestart(deployer())
			assertThat(outcome).isInstanceOf(BuildOutcome.Success::class.java)
			assertThat((outcome as BuildOutcome.Success).restarted).isTrue()
			assertThat(timelines).hasSize(1)
		}

	@Test
	fun `restart ack without binder death names the pre-restart runtime`() =
		runTest {
			deploy.result = DeployResult.Reloaded(40)
			deploy.disconnects = false
			val outcome = deployRestart(deployer())
			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
			assertThat((outcome as BuildOutcome.RequiresProxyAppRebuild).detail)
				.contains("predates restart support")
		}

	/**
	 * Characterization, not endorsement. The restart route relaunches unconditionally, so a
	 * plain save that happens to touch a Service or Receiver pulls the proxy app to the
	 * foreground - the focus steal the recovery route was fixed to avoid. The behaviour is a
	 * known deferred followup: a restart genuinely cannot finish without the process coming
	 * back, so suppressing the relaunch needs a decision about what a half-restarted app
	 * should do, not a one-line gate.
	 *
	 * This pins today's answer so the followup cannot land silently: gating the relaunch on
	 * [userInitiated] turns this red, and whoever does it has to come here and say so.
	 */
	@Test
	fun `a save that restarts a component relaunches the app - the deferred focus steal`() =
		runTest {
			deploy.result = DeployResult.Reloaded(40)

			val outcome = deployRestart(deployer(userInitiated = false))

			assertThat(launchCalls).containsExactly("com.example.app" to "com.example.app.Proxy0Activity")
			assertThat(outcome).isInstanceOf(BuildOutcome.Success::class.java)
			assertThat((outcome as BuildOutcome.Success).restarted).isTrue()
		}

	@Test
	fun `NotConnected relaunches and retries exactly once, never a loop`() =
		runTest {
			// Both attempts NotConnected: recovery must launch once, retry once, then stop.
			deploy.result = DeployResult.NotConnected
			val outcome =
				deployer().deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat(launchCalls).hasSize(1)
			assertThat(deploy.calls).hasSize(2)
			// Started and still absent across both attempts: real cannot-stay-up evidence.
			assertThat((outcome as BuildOutcome.DeployFailure).proxyAppNotConnected).isTrue()
		}

	/**
	 * A launch that never started is not evidence the app cannot stay up - nothing ran to
	 * fail. Distinguishing this from a started-but-absent app is the whole point of
	 * tracking the launch rather than inferring it from who asked for the build.
	 */
	@Test
	fun `a launch that fails to start is not evidence the app cannot stay up`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			launchResult = false

			val outcome =
				deployer().deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)

			assertThat(launchCalls).hasSize(1)
			// No retry: the retry only follows an app that actually started.
			assertThat(deploy.calls).hasSize(1)
			assertThat((outcome as BuildOutcome.DeployFailure).proxyAppNotConnected).isFalse()
		}

	/** Started, then never came back within the window - the app really cannot stay up. */
	@Test
	fun `an app that starts but never reconnects is evidence it cannot stay up`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			deploy.reconnectGeneration = { null }

			val outcome =
				deployer().deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)

			assertThat(launchCalls).hasSize(1)
			assertThat(deploy.calls).hasSize(1)
			assertThat((outcome as BuildOutcome.DeployFailure).proxyAppNotConnected).isTrue()
		}

	/**
	 * The behaviour Bryan asked for: a save builds, but it never takes the screen. Starting an
	 * activity is unconditionally a foreground steal on Android, so the only way to honour that
	 * is to not start one.
	 */
	@Test
	fun `a save whose app is closed never launches it, and does not retry`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			val outcome =
				deployer(userInitiated = false).deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat(launchCalls).isEmpty()
			// One attempt only: the retry exists solely to follow a launch.
			assertThat(deploy.calls).hasSize(1)
			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
		}

	/**
	 * proxyAppNotConnected is the evidence a repeat escalates into the cannot-stay-up dialog, so
	 * it must mean "launched and still absent". A save never launches, so an app nobody has
	 * opened must not be accused of crashing on startup.
	 */
	@Test
	fun `a save's not-connected deploy is not evidence the app cannot stay up`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			val saved =
				deployer(userInitiated = false).deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat((saved as BuildOutcome.DeployFailure).proxyAppNotConnected).isFalse()

			launchCalls.clear()
			deploy.calls.clear()
			val tapped =
				deployer(userInitiated = true).deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat((tapped as BuildOutcome.DeployFailure).proxyAppNotConnected).isTrue()
		}

	@Test
	fun `NotConnected with no proxy app package returns without attempting anything`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			val outcome =
				deployer(proxyAppPackage = null).deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat(launchCalls).isEmpty()
			assertThat(deploy.calls).hasSize(1)
			// Nothing was launched, so this is not cannot-stay-up evidence.
			assertThat((outcome as BuildOutcome.DeployFailure).proxyAppNotConnected).isFalse()
		}

	@Test
	fun `NotConnected with no launcher returns without attempting anything`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			val outcome =
				deployer(withLauncher = false).deploy(
					DeployDecision.Recreate,
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat(launchCalls).isEmpty()
			assertThat(deploy.calls).hasSize(1)
			assertThat((outcome as BuildOutcome.DeployFailure).proxyAppNotConnected).isFalse()
		}

	@Test
	fun `rebuild-proxy-app decision refuses before any deploy goes out`() =
		runTest {
			val outcome =
				deployer().deploy(
					DeployDecision.RebuildProxyApp("baseline predates component metadata"),
					null,
					null,
					null,
					"code",
					startedAt = 0,
					recorder = recorder(),
				)
			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
			assertThat(deploy.calls).isEmpty()
		}

	@Test
	fun `restart success carries the restart metadata flag`() =
		runTest {
			deploy.result = DeployResult.Reloaded(40)
			deployRestart(deployer())
			assertThat(deploy.calls.single().metadataJson).contains("\"restart\":\"true\"")
		}
}
