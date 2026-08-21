package org.appdevforall.cotg.quickbuild.service.provision

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.FakePaths
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.deploy.ProxyAppConnections
import org.appdevforall.cotg.quickbuild.service.session.LiveSessionFactory
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildDaemonController
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

		/** The relaunch fields of each booked rebuild, parallel to [rebuilds]. */
		val relaunches = mutableListOf<Pair<Boolean, Long?>>()

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
			relaunchOk: Boolean,
			toRunningMillis: Long?,
		) {
			rebuilds += isSuccess
			relaunches += relaunchOk to toRunningMillis
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
		var provisionOutcome: () -> ProvisionOutcome = { ProvisionOutcome.Failure(QuickBuildMessage.Literal("unscripted")) }
		var rebuildOutcome: () -> ProxyAppRebuildOutcome = { ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("unscripted")) }

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
	private val connections = ProxyAppConnections()
	private val deploy = FakeDeploy()

	/** Every rebuild relaunch, as (package, launcherActivity) - the deployRestart shape. */
	private val launches = mutableListOf<Pair<String, String?>>()

	/** What the launcher answers; false stands in for a refused start. */
	private var launchResult = true

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
			connections = connections,
			deploy = deploy,
			launcher =
				ProxyAppLauncher { packageName, activityClass ->
					launches += packageName to activityClass
					launchResult
				},
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
					assetsLiveReloadable = true,
				),
			generationStoreFactory = { MemoryGenerationStore() },
			metrics = metrics,
		)
	}

	private fun proxyApp(
		root: File = projectRoot,
		entryActivity: String? = "com.example.MainActivity",
		components: List<ComponentInfo> = emptyList(),
	) = ProxyAppInfo(
		proxyAppPackage = "com.example.quickbuild",
		entryActivity = entryActivity,
		apk = File(root, "proxy-app.apk"),
		classpath = emptyList(),
		proxyClassesDir = null,
		transformedManifest = null,
		components = components,
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
				ProxyAppRebuildOutcome.Success(proxyApp(newRoot), QuickBuildProjectLayout(newRoot))
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
	fun `a successful rebuild relaunches the reinstalled app at the proxied launcher activity`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(
					proxyApp(
						components =
							listOf(
								// A non-launcher activity declared first, so the assertion pins
								// "the launcher one", not "the first one".
								ComponentInfo(ComponentKind.ACTIVITY, "com.example.Other", proxyClass = "com.example.QbOther"),
								ComponentInfo(
									ComponentKind.ACTIVITY,
									"com.example.MainActivity",
									proxyClass = "com.example.QbMain",
									launcher = true,
								),
							),
					),
					QuickBuildProjectLayout(projectRoot),
				)
			}
			deploy.reconnectGeneration = { 7L }

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isInstanceOf(ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded::class.java)
			// Same (package, launcherActivity) shape the restart deploy launches with.
			assertThat(launches).containsExactly("com.example.quickbuild" to "com.example.QbMain")
			assertThat(metrics.rebuilds).containsExactly(true)
			val (relaunchOk, toRunningMillis) = metrics.relaunches.single()
			assertThat(relaunchOk).isTrue()
			assertThat(toRunningMillis).isNotNull()
		}

	@Test
	fun `an alias-launched app relaunches with a null activity so the default launch intent resolves it`() =
		runTest {
			// No proxied activity carries MAIN/LAUNCHER - the <activity-alias> case.
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
			}
			deploy.reconnectGeneration = { 7L }

			runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(launches).containsExactly("com.example.quickbuild" to null)
		}

	@Test
	fun `a failed rebuild never relaunches and books relaunchOk false`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("bad build.gradle"))
			}

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.Failed(QuickBuildMessage.Literal("bad build.gradle")))
			assertThat(launches).isEmpty()
			assertThat(metrics.relaunches).containsExactly(false to null)
		}

	@Test
	fun `a daemon restart failure never relaunches - a live app next to the failure would lie`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
			}
			daemon.startReply = DaemonReply.Failed("no memory")

			runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(launches).isEmpty()
			// The Gradle build itself succeeded, so isSuccess stays true as before...
			assertThat(metrics.rebuilds).containsExactly(true)
			// ...but the relaunch fields must not read like a relaunched app.
			assertThat(metrics.relaunches).containsExactly(false to null)
		}

	@Test
	fun `a refused relaunch start leaves the rebuild Succeeded but books relaunchOk false`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
			}
			launchResult = false

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			// The relaunch is best-effort: the baseline and daemon are fine, so the
			// rebuild result must not fail on it.
			assertThat(result)
				.isInstanceOf(ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded::class.java)
			// A refused start is not a swallowed start; no retry.
			assertThat(launches).hasSize(1)
			assertThat(metrics.relaunches).containsExactly(false to null)
		}

	@Test
	fun `a swallowed first start gets exactly one more launch, and a reconnect then counts`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
			}
			val reconnects = ArrayDeque(listOf<Long?>(null, 7L))
			deploy.reconnectGeneration = { reconnects.removeFirst() }

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isInstanceOf(ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded::class.java)
			assertThat(launches).hasSize(2)
			val (relaunchOk, toRunningMillis) = metrics.relaunches.single()
			assertThat(relaunchOk).isTrue()
			assertThat(toRunningMillis).isNotNull()
		}

	@Test
	fun `a relaunch that never reconnects books relaunchOk false with no toRunningMillis`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
			}
			deploy.reconnectGeneration = { null }

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			// Two starts were issued (the swallowed-start retry), then it gave up.
			assertThat(launches).hasSize(2)
			// Still a success: the new baseline is installed and the daemon is up.
			assertThat(result)
				.isInstanceOf(ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded::class.java)
			assertThat(metrics.rebuilds).containsExactly(true)
			assertThat(metrics.relaunches).containsExactly(false to null)
		}

	@Test
	fun `an unconfirmed reinstall books relaunchOk false and never launches`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("tap install"))
			}

			runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(launches).isEmpty()
			assertThat(metrics.relaunches).containsExactly(false to null)
		}

	@Test
	fun `a superseded rebuild books its metric with relaunchOk false and never launches`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
			}

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { true })

			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.Superseded)
			assertThat(launches).isEmpty()
			assertThat(metrics.rebuilds).containsExactly(true)
			assertThat(metrics.relaunches).containsExactly(false to null)
		}

	@Test
	fun `a daemon that refuses the restart yields DaemonRestartFailed`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), QuickBuildProjectLayout(projectRoot))
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
				.isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.Failed(QuickBuildMessage.Literal("gradle exploded")))
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
				.isEqualTo(ProxyAppBuildRunner.ProvisionResult.Failed(QuickBuildMessage.Literal("provision exploded")))
		}

	@Test
	fun `a session assembly throw after the daemon started unwinds the session and daemon and becomes Failed`() =
		runTest {
			provisioner.provisionOutcome = {
				ProvisionOutcome.Success(
					// Null entryActivity makes sessionFactory.create throw its checkNotNull -
					// the assembly-stage throw the runner's error boundary must catch instead
					// of letting it crash the session scope with a uid session registered.
					proxyApp(entryActivity = null),
					proxyAppUid = 10001,
					layout = QuickBuildProjectLayout(projectRoot),
				)
			}

			val result = runner().provision(superseded = { false })

			assertThat(result).isInstanceOf(ProxyAppBuildRunner.ProvisionResult.Failed::class.java)
			assertThat((result as ProxyAppBuildRunner.ProvisionResult.Failed).message)
				.isEqualTo(QuickBuildMessage.Literal("Quick Build session started without an entry activity"))
			// The uid session registered before the throw was ended...
			assertThat(connections.expectedPackage).isNull()
			assertThat(connections.expectedUid).isNull()
			// ...and the daemon started before it was shut down, intentionally (no respawn).
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.isRunning).isFalse()
		}

	@Test
	fun `a provision outlived by a session restart is Superseded and starts no daemon`() =
		runTest {
			provisioner.provisionOutcome = {
				ProvisionOutcome.Success(
					proxyApp(),
					proxyAppUid = 10001,
					layout = QuickBuildProjectLayout(projectRoot),
				)
			}
			val result = runner().provision(superseded = { true })
			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProvisionResult.Superseded)
			assertThat(daemon.startConfigs).isEmpty()
		}
}
