package org.appdevforall.cotg.quickbuild.service.deploy

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.AssetPackager
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.DeployDecision
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.appdevforall.cotg.quickbuild.service.telemetry.E2eTimelineRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Retention side of [PayloadDeployer] (concurrency.md rules 3-4): a confirmed hot-swap
 * deploy leaves its bytes in the [RetainedPayloadStore] for the reconnect re-send, an
 * unconfirmed one leaves the store exactly as it was, and a confirmed restart deploy clears
 * it - its payload must never be replayed as a hot swap.
 */
class PayloadDeployerRetentionTest {
	@TempDir lateinit var workDir: File

	private val deploy = FakeDeploy()
	private val store by lazy { RetainedPayloadStore.forWorkDir(workDir) }

	private fun deployer() =
		PayloadDeployer(
			deploy = deploy,
			generations = GenerationTracker(MemoryGenerationStore(), initial = 0L),
			entryActivity = "com.example.app.MainActivity",
			proxyAppPackage = "com.example.app",
			launcherActivity = "com.example.app.Proxy0Activity",
			launcher = ProxyAppLauncher { _, _ -> true },
			restartDisconnectTimeoutMillis = 5_000,
			restartReconnectTimeoutMillis = 15_000,
			clock = { 1_000 },
			reportTimeline = {},
			retention = store,
		)

	private fun recorder() = E2eTimelineRecorder(trigger = 0) { null }

	private fun artifact(
		name: String,
		content: String,
	): File = File(workDir, name).apply { writeText(content) }

	@Test
	fun `a confirmed hot-swap deploy retains its payload at the deployed generation`() =
		runTest {
			val dex = artifact("built.dex", "dex-bytes")
			val assetsZip = artifact("assets-payload.zip", "assets-bytes")

			val outcome =
				deployer().deploy(
					DeployDecision.Recreate,
					dex,
					null,
					AssetPackager.PackagedAssets(assetsZip, listOf("data/levels.json")),
					loopStartedAt = 0,
					recorder = recorder(),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.Success::class.java)
			val retained = store.load()!!
			assertThat(retained.generation).isEqualTo((outcome as BuildOutcome.Success).generation)
			assertThat(retained.dexFile!!.readText()).isEqualTo("dex-bytes")
			assertThat(retained.arscFile).isNull()
			assertThat(retained.assetsZip!!.readText()).isEqualTo("assets-bytes")
			assertThat(retained.metadataJson).contains("com.example.app.MainActivity")
		}

	@Test
	fun `the next confirmed deploy replaces the retained set`() =
		runTest {
			val deployer = deployer()
			deployer.deploy(
				DeployDecision.Recreate,
				artifact("built.dex", "gen-1-dex"),
				null,
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)
			deployer.deploy(
				DeployDecision.Recreate,
				artifact("built.dex", "gen-2-dex"),
				artifact("built.arsc", "gen-2-arsc"),
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)

			val retained = store.load()!!
			assertThat(retained.generation).isEqualTo(2L)
			assertThat(retained.dexFile!!.readText()).isEqualTo("gen-2-dex")
			assertThat(retained.arscFile!!.readText()).isEqualTo("gen-2-arsc")
		}

	@Test
	fun `a failed deploy retains nothing`() =
		runTest {
			deploy.result = DeployResult.Failed("binder broke")

			deployer().deploy(
				DeployDecision.Recreate,
				artifact("built.dex", "dex-bytes"),
				null,
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)

			// The proxy app never confirmed these bytes; re-sending them on a reconnect
			// would claim a generation the app never ran.
			assertThat(store.load()).isNull()
		}

	@Test
	fun `a confirmed restart deploy clears the retained payload instead of retaining it`() =
		runTest {
			val deployer = deployer()
			deployer.deploy(
				DeployDecision.Recreate,
				artifact("built.dex", "gen-1-dex"),
				null,
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)
			assertThat(store.load()).isNotNull()

			val outcome =
				deployer.deploy(
					DeployDecision.Restart(ComponentKind.SERVICE, "com.example.app.SyncService"),
					artifact("built.dex", "gen-2-dex"),
					null,
					null,
					loopStartedAt = 0,
					recorder = recorder(),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.Success::class.java)
			// A reconnect catch-up replays retained bytes as a hot swap, which would land on
			// the live service this deploy restarted for and redefine its classes under it -
			// the cross-loader CCE the restart existed to prevent. Nothing may stay retained;
			// a below-deployed reconnect falls back to the forced catch-up rebuild.
			assertThat(store.load()).isNull()
		}

	@Test
	fun `the retention copy runs outside the timed save-to-live span`() =
		runTest {
			// The live-at clock read (t3) happens before retain(): the copy is post-deploy
			// bookkeeping, and a clock read after it would bill the copy - which scales with
			// app size, not edit size - to every successful deploy's save-to-live latency.
			var clockReadAfterRetention = false
			val deployer =
				PayloadDeployer(
					deploy = deploy,
					generations = GenerationTracker(MemoryGenerationStore(), initial = 0L),
					entryActivity = "com.example.app.MainActivity",
					proxyAppPackage = "com.example.app",
					launcherActivity = "com.example.app.Proxy0Activity",
					launcher = ProxyAppLauncher { _, _ -> true },
					restartDisconnectTimeoutMillis = 5_000,
					restartReconnectTimeoutMillis = 15_000,
					clock = {
						if (store.load() != null) clockReadAfterRetention = true
						1_000
					},
					reportTimeline = {},
					retention = store,
				)

			deployer.deploy(
				DeployDecision.Recreate,
				artifact("built.dex", "dex-bytes"),
				null,
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)

			assertThat(store.load()).isNotNull()
			assertThat(clockReadAfterRetention).isFalse()
		}

	@Test
	fun `the restart path's retention clear also runs outside the timed span`() =
		runTest {
			// Same ordering rule as the hot-swap path: the clear is bookkeeping, so t3 is
			// read while the previous deploy's bytes are still retained.
			var tracking = false
			var clockReadAfterClear = false
			val deployer =
				PayloadDeployer(
					deploy = deploy,
					generations = GenerationTracker(MemoryGenerationStore(), initial = 0L),
					entryActivity = "com.example.app.MainActivity",
					proxyAppPackage = "com.example.app",
					launcherActivity = "com.example.app.Proxy0Activity",
					launcher = ProxyAppLauncher { _, _ -> true },
					restartDisconnectTimeoutMillis = 5_000,
					restartReconnectTimeoutMillis = 15_000,
					clock = {
						if (tracking && store.load() == null) clockReadAfterClear = true
						1_000
					},
					reportTimeline = {},
					retention = store,
				)
			deployer.deploy(
				DeployDecision.Recreate,
				artifact("built.dex", "gen-1-dex"),
				null,
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)
			assertThat(store.load()).isNotNull()
			tracking = true

			deployer.deploy(
				DeployDecision.Restart(ComponentKind.SERVICE, "com.example.app.SyncService"),
				artifact("built.dex", "gen-2-dex"),
				null,
				null,
				loopStartedAt = 0,
				recorder = recorder(),
			)

			assertThat(store.load()).isNull()
			assertThat(clockReadAfterClear).isFalse()
		}

	@Test
	fun `a restart deploy whose relaunch never comes back retains nothing`() =
		runTest {
			deploy.result = DeployResult.Reloaded(40)
			deploy.reconnectGeneration = { null }

			val outcome =
				deployer().deploy(
					DeployDecision.Restart(ComponentKind.SERVICE, "com.example.app.SyncService"),
					artifact("built.dex", "dex-bytes"),
					null,
					null,
					loopStartedAt = 0,
					recorder = recorder(),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat(store.load()).isNull()
		}
}
