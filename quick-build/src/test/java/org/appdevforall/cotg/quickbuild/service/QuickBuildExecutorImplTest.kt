package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class QuickBuildExecutorImplTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy = FakeDeploy()
	private val store = MemoryGenerationStore()

	private lateinit var tracker: GenerationTracker
	private lateinit var sourceFile: File
	private lateinit var resFile: File
	private lateinit var assetFile: File
	private lateinit var executor: QuickBuildExecutorImpl

	@BeforeEach
	fun setUp() {
		val mainDir = File(projectRoot, "app/src/main")
		sourceFile =
			File(mainDir, "java/com/example/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}
		resFile =
			File(mainDir, "res/values/strings.xml").apply {
				parentFile!!.mkdirs()
				writeText("<resources/>")
			}
		assetFile =
			File(mainDir, "assets/data/levels.json").apply {
				parentFile!!.mkdirs()
				writeText("{}")
			}
		File(mainDir, "AndroidManifest.xml").writeText("<manifest/>")

		tracker = GenerationTracker(store)
		executor =
			QuickBuildExecutorImpl(
				daemon = daemon,
				deploy = deploy,
				layout = DefaultQuickBuildProjectLayout(projectRoot),
				entryActivity = "com.example.MainActivity",
				generations = tracker,
				workDir = File(projectRoot, ".androidide/quickbuild"),
				clock = { 1000L },
			)
	}

	private fun request(
		route: BuildRoute,
		changes: ChangedFiles = ChangedFiles.Known.EMPTY,
		forced: Boolean = false,
	) = BuildRequest(buildId = 1, changes = changes, route = route, forced = forced)

	private fun metadataOf(call: FakeDeploy.Call) = JsonParser.parseString(call.metadataJson).asJsonObject

	@Test
	fun `code-only route compiles, dexes and deploys the dex`() =
		runTest {
			val outcome =
				executor.execute(
					request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(daemon.compileCalls).hasSize(1)
			assertThat(daemon.compileCalls[0].second).containsExactly(sourceFile)
			assertThat(daemon.dexCalls).hasSize(1)
			assertThat(daemon.relinkCalls).isEmpty()

			val call = deploy.calls.single()
			assertThat(call.generation).isEqualTo(1)
			assertThat(call.dexFile).isNotNull()
			assertThat(call.arscFile).isNull()
			assertThat(call.assetsZip).isNull()
			val metadata = metadataOf(call)
			assertThat(metadata.get("reason").asString).isEqualTo("code")
			assertThat(metadata.get("entryActivity").asString).isEqualTo("com.example.MainActivity")
		}

	@Test
	fun `resources-only route relinks and deploys the arsc without touching the compiler`() =
		runTest {
			val outcome =
				executor.execute(
					request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile))),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(daemon.compileCalls).isEmpty()
			assertThat(daemon.dexCalls).isEmpty()
			assertThat(daemon.relinkCalls).hasSize(1)

			val call = deploy.calls.single()
			assertThat(call.dexFile).isNull()
			assertThat(call.arscFile).isNotNull()
			assertThat(metadataOf(call).get("reason").asString).isEqualTo("resources")
		}

	@Test
	fun `mixed route compiles AND relinks - never stale resources beside new code`() =
		runTest {
			val outcome =
				executor.execute(
					request(
						BuildRoute.CodeAndResources,
						ChangedFiles.Known(setOf(sourceFile, resFile)),
					),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(daemon.compileCalls).hasSize(1)
			assertThat(daemon.relinkCalls).hasSize(1)

			val call = deploy.calls.single()
			assertThat(call.dexFile).isNotNull()
			assertThat(call.arscFile).isNotNull()
			assertThat(metadataOf(call).get("reason").asString).isEqualTo("mixed")
		}

	@Test
	fun `assets-only route deploys a zip of the changed assets and skips the daemon`() =
		runTest {
			val outcome =
				executor.execute(
					request(BuildRoute.AssetsOnly, ChangedFiles.Known(setOf(assetFile))),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(daemon.compileCalls).isEmpty()
			assertThat(daemon.relinkCalls).isEmpty()

			val call = deploy.calls.single()
			assertThat(call.dexFile).isNull()
			assertThat(call.arscFile).isNull()
			assertThat(call.assetsZip).isNotNull()

			val metadata = metadataOf(call)
			assertThat(metadata.get("reason").asString).isEqualTo("assets")
			assertThat(metadata.getAsJsonArray("changedAssets").map { it.asString })
				.containsExactly("data/levels.json")
			ZipFile(call.assetsZip!!).use { zip ->
				assertThat(zip.getEntry("data/levels.json")).isNotNull()
			}
		}

	@Test
	fun `changed assets ride along on a code route`() =
		runTest {
			executor.execute(
				request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile, assetFile))),
			)

			val call = deploy.calls.single()
			assertThat(call.dexFile).isNotNull()
			assertThat(call.assetsZip).isNotNull()
			assertThat(metadataOf(call).getAsJsonArray("changedAssets").map { it.asString })
				.containsExactly("data/levels.json")
		}

	@Test
	fun `compile error maps to CompileError, burns no generation and never deploys`() =
		runTest {
			val diagnostics =
				listOf(
					BuildDiagnostic(
						severity = BuildDiagnostic.Severity.ERROR,
						message = "unresolved reference",
						file = sourceFile.path,
						line = 1,
					),
				)
			daemon.compileReply = DaemonReply.BuildFailed(diagnostics)

			val outcome =
				executor.execute(
					request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.CompileError(diagnostics))
			assertThat(deploy.calls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	@Test
	fun `compile error notifies the test app with the failing location - plan A1`() =
		runTest {
			daemon.compileReply =
				DaemonReply.BuildFailed(
					listOf(
						BuildDiagnostic(
							severity = BuildDiagnostic.Severity.ERROR,
							message = "unresolved reference: foo\nsecond line",
							file = sourceFile.path,
							line = 3,
							column = 7,
						),
					),
				)

			executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			val status = JsonParser.parseString(deploy.statusCalls.single()).asJsonObject
			assertThat(status.get("kind").asString).isEqualTo("build_failed")
			assertThat(status.get("file").asString).isEqualTo(sourceFile.path)
			assertThat(status.get("line").asString).isEqualTo("3")
			assertThat(status.get("message").asString).isEqualTo("unresolved reference: foo")
		}

	@Test
	fun `success notifies build_ok so a previously shown failure clears`() =
		runTest {
			executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			val status = JsonParser.parseString(deploy.statusCalls.single()).asJsonObject
			assertThat(status.get("kind").asString).isEqualTo("build_ok")
		}

	@Test
	fun `deploy and infrastructure failures send no build status`() =
		runTest {
			deploy.result = DeployResult.TimedOut(15_000)
			executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			daemon.compileReply = DaemonReply.Failed("daemon gone", daemonDied = true)
			executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(deploy.statusCalls).isEmpty()
		}

	@Test
	fun `daemon death during compile maps to InfrastructureFailure with daemonDied`() =
		runTest {
			daemon.compileReply = DaemonReply.Failed("daemon gone", daemonDied = true)

			val outcome =
				executor.execute(
					request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.InfrastructureFailure("daemon gone", true))
			assertThat(deploy.calls).isEmpty()
		}

	@Test
	fun `deploy timeout maps to DeployFailure`() =
		runTest {
			deploy.result = DeployResult.TimedOut(15_000)

			val outcome =
				executor.execute(
					request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
		}

	@Test
	fun `test-app crash during deploy maps to DeployFailure carrying the summary`() =
		runTest {
			deploy.result = DeployResult.Crashed("NullPointerException at Foo.kt:1")

			val outcome =
				executor.execute(
					request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message)
				.contains("NullPointerException")
		}

	@Test
	fun `forced no-op rebuilds current sources and deploys a FRESH generation`() =
		runTest {
			store.value = 5
			tracker = GenerationTracker(store)
			executor =
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = { 1000L },
				)

			val outcome = executor.execute(request(BuildRoute.NoOp, forced = true))

			// A replay of generation 5 would be dropped by the runtime (strictly-newer
			// rule); the forced redeploy must ship real artifacts at generation 6.
			assertThat(outcome).isEqualTo(BuildOutcome.Success(6, 0))
			// Full re-seed: every source is recompiled, resources relinked.
			val (all, changed) = daemon.compileCalls.single()
			assertThat(changed).isEqualTo(all)
			assertThat(daemon.relinkCalls).hasSize(1)
			val call = deploy.calls.single()
			assertThat(call.generation).isEqualTo(6)
			assertThat(call.dexFile).isNotNull()
			assertThat(call.arscFile).isNotNull()
			assertThat(metadataOf(call).get("reason").asString).isEqualTo("forced")
		}

	@Test
	fun `forced no-op with a broken resource maps to CompileError and burns no generation`() =
		runTest {
			val diagnostics =
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "invalid color"))
			daemon.relinkReply = DaemonReply.BuildFailed(diagnostics)

			val outcome = executor.execute(request(BuildRoute.NoOp, forced = true))

			assertThat(outcome).isEqualTo(BuildOutcome.CompileError(diagnostics))
			assertThat(deploy.calls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	@Test
	fun `forced no-op with a broken source maps to CompileError and burns no generation`() =
		runTest {
			val diagnostics =
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "unresolved reference"))
			daemon.compileReply = DaemonReply.BuildFailed(diagnostics)

			val outcome = executor.execute(request(BuildRoute.NoOp, forced = true))

			assertThat(outcome).isEqualTo(BuildOutcome.CompileError(diagnostics))
			assertThat(deploy.calls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	@Test
	fun `unforced no-op does nothing`() =
		runTest {
			val outcome = executor.execute(request(BuildRoute.NoOp, forced = false))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(0, 0))
			assertThat(deploy.calls).isEmpty()
			assertThat(daemon.compileCalls).isEmpty()
		}

	@Test
	fun `Unknown changes recompile everything - IC re-seed`() =
		runTest {
			val outcome = executor.execute(request(BuildRoute.CodeAndResources, ChangedFiles.Unknown))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			val (all, changed) = daemon.compileCalls.single()
			assertThat(changed).isEqualTo(all)
			assertThat(all).containsExactly(sourceFile)
		}

	private class FakeLauncher(
		var result: Boolean = true,
	) : TestAppLauncher {
		val calls = mutableListOf<Pair<String, String?>>()

		override fun launch(
			packageName: String,
			activityClass: String?,
		): Boolean {
			calls += packageName to activityClass
			return result
		}
	}

	private fun restartExecutor(
		launcher: FakeLauncher,
		launcherActivity: String? = "com.example.quickbuild.proxies.Proxy0Activity",
		policy: DeployPolicy =
			DeployPolicy(
				listOf(
					ComponentInfo(
						ComponentKind.ACTIVITY,
						"com.example.MainActivity",
						proxyClass = "com.example.quickbuild.proxies.Proxy0Activity",
						launcher = true,
					),
					ComponentInfo(ComponentKind.SERVICE, "com.example.SyncService"),
				),
			),
	) = QuickBuildExecutorImpl(
		daemon = daemon,
		deploy = deploy,
		layout = DefaultQuickBuildProjectLayout(projectRoot),
		entryActivity = "com.example.MainActivity",
		generations = tracker,
		workDir = File(projectRoot, ".androidide/quickbuild"),
		deployPolicy = policy,
		testAppPackage = "com.example.quickbuild",
		launcherActivity = launcherActivity,
		launcher = launcher,
		clock = { 1000L },
	)

	private fun serviceRecompiled() {
		daemon.compileReply =
			DaemonReply.Ok(
				org.appdevforall.cotg.quickbuild.data
					.CompileOutput(File("/fake/classes"), listOf("com/example/SyncService.class")),
			)
	}

	@Test
	fun `service edit deploys with restart metadata, awaits the exit and relaunches`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			val call = deploy.calls.single()
			assertThat(metadataOf(call).get("restart").asString).isEqualTo("true")
			assertThat(deploy.awaitDisconnectCalls).hasSize(1)
			assertThat(launcher.calls)
				.containsExactly("com.example.quickbuild" to "com.example.quickbuild.proxies.Proxy0Activity")
		}

	@Test
	fun `restart relaunches by package when the launcher is an activity-alias (no launcher activity)`() =
		runTest {
			val launcher = FakeLauncher()
			// launcherActivity null models a MAIN/LAUNCHER filter on an <activity-alias>:
			// no proxied activity carries launcher=true, so the relaunch must fall back to
			// the package's default launch intent (activityClass = null) rather than fail.
			val executor = restartExecutor(launcher, launcherActivity = null)
			serviceRecompiled()

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			assertThat(launcher.calls).containsExactly("com.example.quickbuild" to null)
		}

	@Test
	fun `helper-only edit hot-swaps - no restart metadata, no relaunch`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			daemon.compileReply =
				DaemonReply.Ok(
					org.appdevforall.cotg.quickbuild.data
						.CompileOutput(File("/fake/classes"), listOf("com/example/util/Formatter.class")),
				)

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(metadataOf(deploy.calls.single()).has("restart")).isFalse()
			assertThat(deploy.awaitDisconnectCalls).isEmpty()
			assertThat(launcher.calls).isEmpty()
		}

	@Test
	fun `restart deploy that disconnects before acking succeeds once the relaunch reconnects at the new generation`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			deploy.result = DeployResult.Disconnected

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			assertThat(launcher.calls).hasSize(1)
			// Success was VERIFIED against the reconnect, not assumed.
			assertThat(deploy.awaitReconnectCalls).hasSize(1)
		}

	@Test
	fun `restart relaunch reconnecting at an older generation routes to rebaseline - the payload did not persist`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			// The process died around the payload and the fresh boot came back on the
			// previous generation: claiming success would be the silent-stale lie.
			deploy.reconnectGeneration = { 0L }

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresRebaseline::class.java)
			assertThat((outcome as BuildOutcome.RequiresRebaseline).reason)
				.isEqualTo(InvalidationReason.OUTDATED_BASELINE)
			assertThat(outcome.detail).contains("generation 0")
		}

	@Test
	fun `restart relaunch that never reconnects is a deploy failure`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			deploy.reconnectGeneration = { null }

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("did not reconnect")
		}

	@Test
	fun `restart ack without a process exit routes to rebaseline - old runtime hot-swapped`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			deploy.disconnects = false

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresRebaseline::class.java)
			assertThat((outcome as BuildOutcome.RequiresRebaseline).reason)
				.isEqualTo(InvalidationReason.OUTDATED_BASELINE)
			assertThat(launcher.calls).isEmpty()
		}

	@Test
	fun `failed relaunch is a deploy failure telling the user to reopen the app`() =
		runTest {
			val launcher = FakeLauncher(result = false)
			val executor = restartExecutor(launcher)
			serviceRecompiled()

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("relaunched")
		}

	@Test
	fun `pre-v2 baseline refuses a code deploy BEFORE deploying - rebaseline instead`() =
		runTest {
			val launcher = FakeLauncher()
			val executor =
				restartExecutor(launcher, policy = DeployPolicy(emptyList(), componentInfoAvailable = false))
			daemon.compileReply =
				DaemonReply.Ok(
					org.appdevforall.cotg.quickbuild.data
						.CompileOutput(File("/fake/classes"), listOf("com/example/Foo.class")),
				)

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresRebaseline::class.java)
			assertThat(deploy.calls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	@Test
	fun `unknown recompiled set with a service restarts conservatively`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			daemon.compileReply =
				DaemonReply.Ok(
					org.appdevforall.cotg.quickbuild.data
						.CompileOutput(File("/fake/classes"), changedClassFiles = null),
				)

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			assertThat(metadataOf(deploy.calls.single()).get("restart").asString).isEqualTo("true")
		}

	@Test
	fun `resource-only deploys never restart even with a service present`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)

			val outcome =
				executor.execute(request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(metadataOf(deploy.calls.single()).has("restart")).isFalse()
			assertThat(launcher.calls).isEmpty()
		}

	@Test
	fun `disconnect during a NORMAL deploy is a deploy failure`() =
		runTest {
			deploy.result = DeployResult.Disconnected

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("disconnected")
		}

	@Test
	fun `FullGradleBuild route is refused as an infrastructure failure`() =
		runTest {
			val outcome =
				executor.execute(
					request(
						BuildRoute.FullGradleBuild(
							org.appdevforall.cotg.quickbuild.domain.InvalidationReason.MANIFEST_CHANGED,
						),
					),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.InfrastructureFailure::class.java)
			assertThat(deploy.calls).isEmpty()
		}

	@Test
	fun `class-header feed reads real class files and extends the restart closure`() =
		runTest {
			// Real .class bytes in a real classes dir - the /fake/classes paths the other
			// tests use skip the header read silently, so this pins the actual file wiring.
			val classesDir = File(projectRoot, "out/classes").apply { mkdirs() }
			copyClassFile(classesDir, ExecutorFeedService::class.java)
			copyClassFile(classesDir, ExecutorFeedBaseService::class.java)
			val serviceFqn = ExecutorFeedService::class.java.name
			val servicePath = serviceFqn.replace('.', '/') + ".class"
			val basePath = ExecutorFeedBaseService::class.java.name.replace('.', '/') + ".class"

			val launcher = FakeLauncher()
			val executor =
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					// No baked supertypes: the base is in the closure ONLY if the real-file
					// feed reads the service's header (super = ExecutorFeedBaseService).
					deployPolicy = DeployPolicy(listOf(ComponentInfo(ComponentKind.SERVICE, serviceFqn))),
					testAppPackage = "com.example.quickbuild",
					launcherActivity = "com.example.quickbuild.proxies.Proxy0Activity",
					launcher = launcher,
					clock = { 1000L },
				)

			// Build 1: the service class itself recompiles (direct hit -> restart) and the
			// feed records its real superclass edge.
			daemon.compileReply =
				DaemonReply.Ok(
					org.appdevforall.cotg.quickbuild.data
						.CompileOutput(classesDir, listOf(servicePath)),
				)
			assertThat(
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)))),
			).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))

			// Build 2: only the superclass recompiles. With a broken/no-op header read the
			// seeded closure would be {service} alone -> Recreate; the recorded edge makes
			// it -> Restart, proving the real file was read.
			daemon.compileReply =
				DaemonReply.Ok(
					org.appdevforall.cotg.quickbuild.data
						.CompileOutput(classesDir, listOf(basePath)),
				)
			assertThat(
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)))),
			).isEqualTo(BuildOutcome.Success(2, 0, restarted = true))
		}

	private fun copyClassFile(
		classesDir: File,
		clazz: Class<*>,
	) {
		val resource = clazz.name.replace('.', '/') + ".class"
		val bytes = clazz.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() }
		File(classesDir, resource).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
	}
}

/** Fixtures for the class-header feed test: a service whose real superclass is a project class. */
private open class ExecutorFeedBaseService

private class ExecutorFeedService : ExecutorFeedBaseService()
