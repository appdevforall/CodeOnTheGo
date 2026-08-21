package org.appdevforall.cotg.quickbuild.service.deploy

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.appdevforall.cotg.quickbuild.service.session.LiveReloadExecutorImpl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Restart-path failure corners of [PayloadDeployer], driven through the real
 * [LiveReloadExecutorImpl] like [LiveReloadExecutorImplTest]'s restart cases: the
 * relaunch preconditions (no launcher wired / no package known), the failure verdicts
 * a restart deploy can come back with, and restart metadata carrying changed assets.
 */
class PayloadDeployerEdgeTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy = FakeDeploy()
	private val store = MemoryGenerationStore()

	private lateinit var tracker: GenerationTracker
	private lateinit var sourceFile: File
	private lateinit var assetFile: File

	@BeforeEach
	fun setUp() {
		val mainDir = File(projectRoot, "app/src/main")
		sourceFile =
			File(mainDir, "java/com/example/SyncService.kt").apply {
				parentFile!!.mkdirs()
				writeText("class SyncService")
			}
		assetFile =
			File(mainDir, "assets/data/levels.json").apply {
				parentFile!!.mkdirs()
				writeText("{}")
			}
		File(mainDir, "AndroidManifest.xml").writeText("<manifest/>")
		tracker = GenerationTracker(store)
		// Every build recompiles the service: the policy then requires a restart deploy.
		daemon.compileReply =
			DaemonReply.Ok(CompileOutput(File("/fake/classes"), listOf("com/example/SyncService.class")))
	}

	private fun servicePolicy() =
		DeployPolicy(
			listOf(ComponentInfo(ComponentKind.SERVICE, "com.example.SyncService")),
		)

	private fun executor(
		proxyAppPackage: String? = "com.example.quickbuild",
		launcher: ProxyAppLauncher? = ProxyAppLauncher { _, _ -> true },
	) = LiveReloadExecutorImpl(
		daemon = daemon,
		deploy = deploy,
		layout = QuickBuildProjectLayout(projectRoot),
		entryActivity = "com.example.MainActivity",
		generations = tracker,
		workDir = File(projectRoot, ".androidide/quickbuild"),
		deployPolicy = servicePolicy(),
		proxyAppPackage = proxyAppPackage,
		launcherActivity = null,
		launcher = launcher,
		clock = { 1000L },
	)

	private fun codeRequest(vararg files: File = arrayOf(sourceFile)) =
		BuildRequest(
			buildId = 1,
			changes = ChangedFiles.Known(files.toSet()),
			route = BuildRoute.CodeOnly,
		)

	@Test
	fun `a restart deploy without a launcher wired fails telling the user to reopen the app`() =
		runTest {
			val outcome = executor(launcher = null).execute(codeRequest())

			val failure = outcome as BuildOutcome.DeployFailure
			assertThat(failure.message).contains("could not be relaunched")
			assertThat(failure.message).contains("open it manually")
		}

	@Test
	fun `a restart deploy without a known package fails the same way`() =
		runTest {
			val launched = mutableListOf<String>()
			val outcome =
				executor(
					proxyAppPackage = null,
					launcher =
						ProxyAppLauncher { packageName, _ ->
							launched += packageName
							true
						},
				).execute(codeRequest())

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("could not be relaunched")
			// With no package there is nothing to launch - the launcher must not be poked blind.
			assertThat(launched).isEmpty()
		}

	@Test
	fun `a restart deploy whose verdict times out reports the unconfirmed generation`() =
		runTest {
			deploy.result = DeployResult.TimedOut(15_000)

			val outcome = executor().execute(codeRequest())

			val failure = outcome as BuildOutcome.DeployFailure
			assertThat(failure.message).contains("did not confirm generation 1")
			assertThat(failure.message).contains("15000 ms")
		}

	@Test
	fun `a restart deploy whose payload crashes carries the stack summary`() =
		runTest {
			deploy.result = DeployResult.Crashed("NPE in SyncService.onCreate")

			val outcome = executor().execute(codeRequest())

			val failure = outcome as BuildOutcome.DeployFailure
			assertThat(failure.message).contains("crashed in the proxy app")
			assertThat(failure.message).contains("NPE in SyncService.onCreate")
		}

	@Test
	fun `a hot-swap deploy that loses its proxy app mid-verdict is a deploy failure`() =
		runTest {
			// No policy: a plain hot-swap deploy. The app dying mid-deploy is fatal here
			// (unlike a restart deploy, where the exit is the expected protocol).
			daemon.compileReply = DaemonReply.Ok(CompileOutput(File("/fake/classes"), emptyList()))
			deploy.result = DeployResult.Disconnected
			val hotSwapExecutor =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = { 1000L },
				)

			val outcome = hotSwapExecutor.execute(codeRequest())

			assertThat(outcome)
				.isEqualTo(BuildOutcome.DeployFailure("Proxy app disconnected during deploy"))
		}

	@Test
	fun `a restart deploy carrying assets still flags the restart, and carries nothing else`() =
		runTest {
			executor().execute(codeRequest(sourceFile, assetFile))

			val metadata = JsonParser.parseString(deploy.calls.single().metadataJson).asJsonObject
			assertThat(metadata.get("restart").asString).isEqualTo("true")
			// The assets ride in the zip beside the metadata, never inside it: the runtime
			// reads exactly these two keys, so any third one is bytes crossing a binder for
			// nobody.
			assertThat(metadata.keySet()).containsExactly("entryActivity", "restart")
		}
}
