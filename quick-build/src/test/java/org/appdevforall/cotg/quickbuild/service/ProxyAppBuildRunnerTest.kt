package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Seam tests for the Gradle proxy-app-build runner, directly against
 * [ProxyAppBuildRunner] (the manager's tests drive the same paths end-to-end;
 * these pin the runner's own contract).
 */
class ProxyAppBuildRunnerTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()

	/** Records rebuild metric calls; everything else is a no-op. */
	private class RecordingMetrics : QuickBuildMetricsSink {
		val rebuilds = mutableListOf<Boolean>()

		override fun onSessionStarted() = Unit

		override fun onBuildStarted(
			buildId: Long,
			route: BuildRoute,
			changes: ChangedFiles,
		) = Unit

		override fun onBuildFinished(
			buildId: Long,
			outcome: BuildOutcome,
		) = Unit

		override fun onInvalidation(reason: InvalidationReason) = Unit

		override fun onProxyAppRebuild(
			isSuccess: Boolean,
			durationMillis: Long,
		) {
			rebuilds += isSuccess
		}
	}

	/** Scripted provisioner that records call order against the daemon's state. */
	private class FakeProvisioner(
		private val daemon: FakeDaemon,
	) : QuickBuildProvisioner {
		var provisionCalls = 0
		var rebuildCalls = 0

		/** The daemon's shutdown count observed when the rebuild's Gradle build ran. */
		var daemonShutdownsAtRebuild = -1
		var provisionOutcome: () -> ProvisionOutcome = { ProvisionOutcome.Failure("unscripted") }
		var rebuildOutcome: () -> ProxyAppRebuildOutcome = { ProxyAppRebuildOutcome.Failure("unscripted") }

		override suspend fun provision(): ProvisionOutcome {
			provisionCalls++
			return provisionOutcome()
		}

		override suspend fun rebuildProxyApp(): ProxyAppRebuildOutcome {
			rebuildCalls++
			daemonShutdownsAtRebuild = daemon.shutdownCount
			return rebuildOutcome()
		}
	}

	private val metrics = RecordingMetrics()
	private val provisioner = FakeProvisioner(daemon)

	private fun runner(minFreeBytes: Long = 0L): ProxyAppBuildRunner {
		val scratch = QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot, minFreeBytes)
		val daemonController =
			QuickBuildDaemonController(
				daemon = daemon,
				scratch = scratch,
				paths = FakePaths(projectRoot),
			)
		return ProxyAppBuildRunner(
			provisioner = provisioner,
			daemonController = daemonController,
			connections = ProxyAppConnections(),
			scratch = scratch,
			sessionFactory =
				LiveSessionFactory(
					daemon = daemon,
					deploy = FakeDeploy(),
					scratch = scratch,
					launcher = ProxyAppLauncher { _, _ -> true },
					metrics = metrics,
					nowMillis = { 1000L },
					executorFactory = null,
					watcherFactory = { _, _, _, _ -> error("not used by these seams") },
					scope = CoroutineScope(StandardTestDispatcher()),
					onOrchestratorEvent = {},
				),
			generationStoreFactory = { MemoryGenerationStore() },
			metrics = metrics,
		)
	}

	private fun proxyApp(root: File = projectRoot) =
		ProxyAppInfo(
			proxyAppPackage = "com.example.quickbuild",
			entryActivity = "com.example.MainActivity",
			apk = File(root, "proxy-app.apk"),
			classpath = emptyList(),
			proxyClassesDir = null,
			transformedManifest = null,
		)

	@Test
	fun `a deferred rebuild - slot busy while parked - books no rebuild metric`() =
		runTest {
			provisioner.rebuildOutcome = { ProxyAppRebuildOutcome.BuildSlotBusy }
			val result = runner().rebuildProxyApp(parkedRetry = true, superseded = { false })
			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.BuildSlotBusy)
			assertThat(metrics.rebuilds).isEmpty()
		}

	@Test
	fun `a first rebuild losing the slot books a failed rebuild metric`() =
		runTest {
			provisioner.rebuildOutcome = { ProxyAppRebuildOutcome.BuildSlotBusy }
			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })
			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.BuildSlotBusy)
			assertThat(metrics.rebuilds).containsExactly(false)
		}

	@Test
	fun `the daemon is down during the Gradle build and restarts against the NEW setup's config`() =
		runTest {
			daemon.isRunning = true
			val newRoot = File(projectRoot, "moved-project").apply { mkdirs() }
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(newRoot), DefaultQuickBuildProjectLayout(newRoot))
			}

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isInstanceOf(ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded::class.java)
			// Shut down BEFORE the Gradle build ran (the two must not coexist in memory).
			assertThat(provisioner.daemonShutdownsAtRebuild).isEqualTo(1)
			// Restarted against the NEW setup's config, not the old baseline's.
			assertThat(daemon.startConfigs.single().projectRoot).isEqualTo(newRoot)
			assertThat(metrics.rebuilds).containsExactly(true)
		}

	@Test
	fun `a daemon that refuses the restart yields DaemonRestartFailed`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), DefaultQuickBuildProjectLayout(projectRoot))
			}
			daemon.startReply = DaemonReply.Failed("no memory")
			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })
			assertThat(result)
				.isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.DaemonRestartFailed("no memory"))
		}

	@Test
	fun `a rebuild provisioner that throws becomes Failed, not a propagated exception`() =
		runTest {
			provisioner.rebuildOutcome = { throw IllegalStateException("gradle exploded") }
			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })
			assertThat(result)
				.isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.Failed("gradle exploded"))
			// A real attempt that died still books a failed rebuild.
			assertThat(metrics.rebuilds).containsExactly(false)
		}

	@Test
	fun `a disk-space shortfall short-circuits before the provisioner is called at all`() =
		runTest {
			val result = runner(minFreeBytes = Long.MAX_VALUE).provision(superseded = { false })
			assertThat(result)
				.isInstanceOf(ProxyAppBuildRunner.ProvisionResult.DiskSpaceShort::class.java)
			assertThat(provisioner.provisionCalls).isEqualTo(0)
		}

	@Test
	fun `a provisioner that throws becomes Failed, not a propagated exception`() =
		runTest {
			provisioner.provisionOutcome = { throw IllegalStateException("provision exploded") }
			val result = runner().provision(superseded = { false })
			assertThat(result)
				.isEqualTo(ProxyAppBuildRunner.ProvisionResult.Failed("provision exploded"))
		}

	@Test
	fun `a provision outlived by a session restart is Superseded and starts no daemon`() =
		runTest {
			provisioner.provisionOutcome = {
				ProvisionOutcome.Success(
					proxyApp(),
					proxyAppUid = 10001,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
				)
			}
			val result = runner().provision(superseded = { true })
			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProvisionResult.Superseded)
			assertThat(daemon.startConfigs).isEmpty()
		}
}
