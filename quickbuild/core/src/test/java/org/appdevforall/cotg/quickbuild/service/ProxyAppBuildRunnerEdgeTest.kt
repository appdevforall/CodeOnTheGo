package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Failure and supersession corners of [ProxyAppBuildRunner] beyond
 * [ProxyAppBuildRunnerTest]: message-less throws, a blocked scratch tree, a daemon that
 * rejects (rather than fails) the start, the restart-raced-daemon-start unwind, and
 * the artifacts-intact probe's field-by-field contract.
 */
class ProxyAppBuildRunnerEdgeTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val connections = ProxyAppConnections()

	// Lazy: @TempDir injects projectRoot after construction.
	private val scratch by lazy { QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot, minFreeBytes = 0L) }

	private class ScriptedProvisioner : QuickBuildProvisioner {
		var provisionOutcome: () -> ProvisionOutcome = {
			ProvisionOutcome.Failure(QuickBuildMessage.Literal("unscripted"))
		}
		var rebuildOutcome: () -> ProxyAppRebuildOutcome = {
			ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("unscripted"))
		}

		override suspend fun provision(): ProvisionOutcome = provisionOutcome()

		override suspend fun rebuildProxyApp(): ProxyAppRebuildOutcome = rebuildOutcome()
	}

	private val provisioner = ScriptedProvisioner()

	private fun runner(): ProxyAppBuildRunner =
		ProxyAppBuildRunner(
			provisioner = provisioner,
			daemonController =
				QuickBuildDaemonController(
					daemon = daemon,
					scratch = scratch,
					paths = FakePaths(projectRoot),
				),
			connections = connections,
			scratch = scratch,
			sessionFactory =
				LiveSessionFactory(
					daemon = daemon,
					deploy = FakeDeploy(),
					scratch = scratch,
					launcher = ProxyAppLauncher { _, _ -> true },
					metrics = QuickBuildMetricsSink.Noop,
					nowMillis = { 1000L },
					executorFactory = null,
					watcherFactory = { _, _, _, _ -> error("not reached by these seams") },
					scope = CoroutineScope(StandardTestDispatcher()),
					onOrchestratorEvent = {},
				),
			generationStoreFactory = { MemoryGenerationStore() },
			metrics = QuickBuildMetricsSink.Noop,
		)

	private fun proxyApp(
		classpath: List<File> = emptyList(),
		proxyClassesDir: File? = null,
		transformedManifest: File? = null,
	) = ProxyAppInfo(
		proxyAppPackage = "com.example.quickbuild",
		entryActivity = "com.example.MainActivity",
		apk = File(projectRoot, "proxy-app.apk"),
		classpath = classpath,
		proxyClassesDir = proxyClassesDir,
		transformedManifest = transformedManifest,
	)

	private fun successOutcome() =
		ProvisionOutcome.Success(
			proxyApp(),
			proxyAppUid = 10001,
			layout = DefaultQuickBuildProjectLayout(projectRoot),
		)

	@Test
	fun `a message-less provisioner throw is reported by exception class name`() =
		runTest {
			provisioner.provisionOutcome = { throw IllegalStateException() }

			val result = runner().provision(superseded = { false })

			assertThat(result)
				.isEqualTo(
					ProxyAppBuildRunner.ProvisionResult.Failed(
						QuickBuildMessage.Literal(IllegalStateException::class.java.name),
					),
				)
		}

	@Test
	fun `a blocked scratch tree fails provisioning with the preparation message`() =
		runTest {
			provisioner.provisionOutcome = { successOutcome() }
			// A stray file where the project's scratch tree must go defeats mkdirs.
			val tree = scratch.treeFor(projectRoot)
			tree.parentFile!!.mkdirs()
			tree.writeText("in the way")

			val result = runner().provision(superseded = { false })

			assertThat(result).isInstanceOf(ProxyAppBuildRunner.ProvisionResult.Failed::class.java)
			assertThat((result as ProxyAppBuildRunner.ProvisionResult.Failed).message)
				.isInstanceOf(QuickBuildMessage.ScratchDirUnavailable::class.java)
			// Failed before the session/daemon stage: nothing to unwind.
			assertThat(daemon.startConfigs).isEmpty()
		}

	@Test
	fun `a daemon that rejects the configure fails provisioning`() =
		runTest {
			provisioner.provisionOutcome = { successOutcome() }
			daemon.startReply = DaemonReply.BuildFailed(emptyList())

			val result = runner().provision(superseded = { false })

			assertThat(result)
				.isEqualTo(ProxyAppBuildRunner.ProvisionResult.Failed(QuickBuildMessage.DaemonRejectedConfiguration))
		}

	@Test
	fun `a daemon start failure carries the daemon's message`() =
		runTest {
			provisioner.provisionOutcome = { successOutcome() }
			daemon.startReply = DaemonReply.Failed("jdk missing")

			val result = runner().provision(superseded = { false })

			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProvisionResult.Failed(QuickBuildMessage.Literal("jdk missing")))
		}

	@Test
	fun `a restart landing during the daemon start ends the session and reports the special supersession`() =
		runTest {
			provisioner.provisionOutcome = { successOutcome() }
			// False when probed after the Gradle build, true when probed after the daemon
			// start - the exact race this result exists for.
			var probes = 0
			val superseded = { probes++ > 0 }

			val result = runner().provision(superseded = superseded)

			assertThat(result)
				.isEqualTo(ProxyAppBuildRunner.ProvisionResult.SupersededDuringDaemonStart)
			// The uid session the runner had begun is ended again...
			assertThat(connections.expectedUid).isNull()
			// ...and the daemon it started is left for the MANAGER to stop (this
			// coroutine is already cancelled in the real flow).
			assertThat(daemon.startConfigs).hasSize(1)
		}

	@Test
	fun `a rebuild outlived by a session restart is Superseded after booking its metric`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), DefaultQuickBuildProjectLayout(projectRoot))
			}

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { true })

			assertThat(result).isEqualTo(ProxyAppBuildRunner.ProxyAppRebuildResult.Superseded)
			// The superseded rebuild must NOT restart a daemon for a dead session.
			assertThat(daemon.startConfigs).isEmpty()
		}

	@Test
	fun `a message-less rebuild throw is reported by exception class name`() =
		runTest {
			provisioner.rebuildOutcome = { throw IllegalStateException() }

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isEqualTo(
					ProxyAppBuildRunner.ProxyAppRebuildResult.Failed(
						QuickBuildMessage.Literal(IllegalStateException::class.java.name),
					),
				)
		}

	@Test
	fun `an unconfirmed reinstall passes its message through`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("tap install"))
			}

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isEqualTo(
					ProxyAppBuildRunner.ProxyAppRebuildResult.InstallNotConfirmed(
						QuickBuildMessage.Literal("tap install"),
					),
				)
		}

	@Test
	fun `a daemon that rejects the restart configure reports DaemonRestartFailed with the fallback text`() =
		runTest {
			provisioner.rebuildOutcome = {
				ProxyAppRebuildOutcome.Success(proxyApp(), DefaultQuickBuildProjectLayout(projectRoot))
			}
			daemon.startReply = DaemonReply.BuildFailed(emptyList())

			val result = runner().rebuildProxyApp(parkedRetry = false, superseded = { false })

			assertThat(result)
				.isEqualTo(
					ProxyAppBuildRunner.ProxyAppRebuildResult.DaemonRestartFailed(
						"daemon rejected configuration",
					),
				)
		}

	@Test
	fun `artifacts are intact when every reported path still exists`() {
		val jar = File(projectRoot, "libs/a.jar").apply { parentFile!!.mkdirs() }.apply { writeText("jar") }
		val classes = File(projectRoot, "proxy-classes").apply { mkdirs() }
		val manifest = File(projectRoot, "Merged.xml").apply { writeText("<manifest/>") }

		val intact =
			runner().proxyAppArtifactsIntact(
				proxyApp(classpath = listOf(jar), proxyClassesDir = classes, transformedManifest = manifest),
			)

		assertThat(intact).isTrue()
	}

	@Test
	fun `a wiped classpath entry means the artifacts are gone`() {
		val gone = File(projectRoot, "libs/wiped.jar")

		assertThat(runner().proxyAppArtifactsIntact(proxyApp(classpath = listOf(gone)))).isFalse()
	}

	@Test
	fun `a wiped proxy classes dir means the artifacts are gone`() {
		val gone = File(projectRoot, "proxy-classes-wiped")

		assertThat(runner().proxyAppArtifactsIntact(proxyApp(proxyClassesDir = gone))).isFalse()
	}

	@Test
	fun `a wiped transformed manifest means the artifacts are gone`() {
		val gone = File(projectRoot, "Merged-wiped.xml")

		assertThat(runner().proxyAppArtifactsIntact(proxyApp(transformedManifest = gone))).isFalse()
	}

	@Test
	fun `absent optional artifacts do not count as wiped`() {
		// A pre-v2 setup.json reports neither; their absence is normal, not a wipe.
		assertThat(runner().proxyAppArtifactsIntact(proxyApp())).isTrue()
	}
}
