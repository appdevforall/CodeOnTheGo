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
			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
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
			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
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
			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
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
