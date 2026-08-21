package org.appdevforall.cotg.quickbuild.service.session

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DexOutput
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.RelinkOutput
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.protocol.DexStats
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.deploy.DeployResult
import org.appdevforall.cotg.quickbuild.service.deploy.RetainedPayloadStore
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class LiveReloadExecutorImplTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy = FakeDeploy()
	private val store = MemoryGenerationStore()
	private val launchCalls = mutableListOf<Pair<String, String?>>()

	private lateinit var tracker: GenerationTracker
	private lateinit var sourceFile: File
	private lateinit var resFile: File
	private lateinit var assetFile: File
	private lateinit var executor: LiveReloadExecutorImpl

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
			LiveReloadExecutorImpl(
				daemon = daemon,
				deploy = deploy,
				layout = QuickBuildProjectLayout(projectRoot),
				entryActivity = "com.example.MainActivity",
				generations = tracker,
				workDir = File(projectRoot, ".androidide/quickbuild"),
				clock = { 1000L },
			)
	}

	/**
	 * Builds a tap's request by default. These tests are about the deploy pipeline rather than
	 * who asked for it, and only a tap may open a closed app - a save's refusal to launch is
	 * pinned in PayloadDeployerTest instead.
	 */
	private fun request(
		route: BuildRoute,
		changes: ChangedFiles = ChangedFiles.Known.EMPTY,
		forced: Boolean = false,
		userInitiated: Boolean = true,
	) = BuildRequest(
		buildId = 1,
		changes = changes,
		route = route,
		forced = forced,
		userInitiated = userInitiated,
	)

	private fun metadataOf(call: FakeDeploy.Call) = JsonParser.parseString(call.metadataJson).asJsonObject

	@Test
	fun `a confirmed deploy is retained under the work dir where forWorkDir reads it`() =
		runTest {
			// S8 agreement pin, writer side: the executor derives its retention store
			// internally from workDir; the session manager's reconnect re-send reads through
			// RetainedPayloadStore.forWorkDir over the same dir. If the two derivations
			// diverge, retention is silently never found and every reconnect pays the forced
			// rebuild S8 removed.
			daemon.dexReply =
				DaemonReply.Ok(
					DexOutput(
						File(projectRoot, "built/classes.dex").apply {
							parentFile!!.mkdirs()
							writeText("dex-bytes")
						},
					),
				)

			executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			val retained =
				RetainedPayloadStore
					.forWorkDir(File(projectRoot, ".androidide/quickbuild"))
					.load()
			assertThat(retained).isNotNull()
			assertThat(retained!!.generation).isEqualTo(1)
			assertThat(retained.dexFile!!.readText()).isEqualTo("dex-bytes")
		}

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
			assertThat(metadata.get("entryActivity").asString).isEqualTo("com.example.MainActivity")
		}

	@Test
	fun `warm-compile route compiles everything and dexes but deploys NOTHING at an unmoved generation`() =
		runTest {
			val outcome = executor.execute(request(BuildRoute.WarmCompile, ChangedFiles.Unknown))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(0, 0))
			// The whole source set goes through the compiler (IC-cache priming)...
			assertThat(daemon.compileCalls).hasSize(1)
			assertThat(daemon.compileCalls[0].second).containsExactly(sourceFile)
			// ...d8 warms too...
			assertThat(daemon.dexCalls).hasSize(1)
			// ...but nothing reaches the device: no deploy, no relink, generation unmoved.
			assertThat(deploy.calls).isEmpty()
			assertThat(daemon.relinkCalls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	// Review gap (2026-07-26 #69): the warm compile is invisible by contract - the proxy app
	// already runs exactly the sources it compiles - so its overlay must not flash
	// "build ok" for a build the user never triggered.
	@Test
	fun `a warm-compile success stays silent on the proxy-app status channel`() =
		runTest {
			val outcome = executor.execute(request(BuildRoute.WarmCompile, ChangedFiles.Unknown))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(0, 0))
			assertThat(deploy.statusCalls).isEmpty()
		}

	@Test
	fun `a warm-compile compile error stays silent on the proxy-app status channel but keeps the real outcome`() =
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

			val outcome = executor.execute(request(BuildRoute.WarmCompile, ChangedFiles.Unknown))

			// The orchestrator still needs the honest outcome (it routes recovery),
			// but the proxy-app overlay must not flash "build failed" for sources the
			// app is running fine - the proxy app build compiled them green moments ago.
			assertThat(outcome).isEqualTo(BuildOutcome.CompileError(diagnostics))
			assertThat(deploy.statusCalls).isEmpty()
			assertThat(deploy.calls).isEmpty()
		}

	@Test
	fun `a removed source is threaded into the compiler's removedFiles, not its changed set`() =
		runTest {
			val removedSource = File(projectRoot, "app/src/main/java/com/example/Gone.kt")

			val outcome =
				executor.execute(
					request(
						BuildRoute.CodeOnly,
						ChangedFiles.Known(files = setOf(sourceFile), removed = setOf(removedSource)),
					),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(daemon.compileCalls).hasSize(1)
			// The live edit is a changed source; the deleted one is a removed source.
			assertThat(daemon.compileCalls[0].second).containsExactly(sourceFile)
			assertThat(daemon.compileRemovedFiles.single()).containsExactly(removedSource)
		}

	@Test
	fun `a pure deletion compiles with an empty changed set and the removed source`() =
		runTest {
			val removedSource = File(projectRoot, "app/src/main/java/com/example/Gone.kt")

			executor.execute(
				request(BuildRoute.CodeOnly, ChangedFiles.Known(files = emptySet(), removed = setOf(removedSource))),
			)

			assertThat(daemon.compileCalls.single().second).isEmpty()
			assertThat(daemon.compileRemovedFiles.single()).containsExactly(removedSource)
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
		}

	@Test
	fun `relink passes the layout's stable-ids file to the daemon`() =
		runTest {
			val stableIds =
				File(projectRoot, "app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt")
					.apply {
						parentFile!!.mkdirs()
						writeText("demo:string/app_name = 0x7f010000")
					}
			val executorWithStableIds =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot, stableIdsFile = stableIds),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = { 1000L },
				)

			executorWithStableIds.execute(request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile))))

			assertThat(daemon.relinkCalls).hasSize(1)
			assertThat(daemon.relinkCalls.single().stableIdsFile).isEqualTo(stableIds)
		}

	@Test
	fun `relink passes the layout's library-resource units to the daemon`() =
		runTest {
			// a relink of the project's own res/ alone can't resolve a
			// resource a dependency AAR provides, so the layout's reported merged_res /
			// dependency-resource units must reach the daemon on every relink.
			val libraryResource =
				File(projectRoot, "app/build/intermediates/merged_res/debug/values_values.arsc.flat")
					.apply {
						parentFile!!.mkdirs()
						writeText("")
					}
			val executorWithLibraryResources =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot, libraryResourceFlats = listOf(libraryResource)),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = { 1000L },
				)

			executorWithLibraryResources.execute(request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile))))

			assertThat(daemon.relinkCalls).hasSize(1)
			assertThat(daemon.relinkCalls.single().libraryResources).containsExactly(libraryResource)
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

			ZipFile(call.assetsZip!!).use { zip ->
				assertThat(zip.entries().toList().map { it.name }).containsExactly("data/levels.json")
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
			// The zip is the only channel the assets travel on, so its contents are what
			// proves they rode along - the metadata carries no asset list.
			ZipFile(call.assetsZip!!).use { zip ->
				assertThat(zip.entries().toList().map { it.name }).containsExactly("data/levels.json")
			}
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
	fun `compile error notifies the proxy app without the failing location`() =
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
			assertThat(status.get("message").asString).isEqualTo("unresolved reference: foo")
			// The overlay only warns that the app is stale; finding the error is CoGo's job,
			// so no host-side path reaches the device.
			assertThat(status.has("file")).isFalse()
			assertThat(status.has("line")).isFalse()
			assertThat(status.has("column")).isFalse()
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
	fun `proxy-app crash during deploy maps to DeployFailure carrying the summary`() =
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
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot),
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
		}

	@Test
	fun `forced no-op packages the FULL asset set - the classifier gave it no changed-set to derive one from`() =
		runTest {
			// A second asset alongside the one setUp() writes, so "the whole tree" is
			// distinguishable from "whatever setUp() happened to leave lying around".
			File(projectRoot, "app/src/main/assets/data/more.json").apply {
				parentFile!!.mkdirs()
				writeText("{}")
			}

			executor.execute(request(BuildRoute.NoOp, forced = true))

			val call = deploy.calls.single()
			assertThat(call.assetsZip).isNotNull()
			ZipFile(call.assetsZip!!).use { zip ->
				assertThat(zip.entries().toList().map { it.name })
					.containsExactly("data/levels.json", "data/more.json")
			}
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

	/** Returns 10, 20, 30, ... on each call - so t0<t1<t2<t3 are distinguishable. */
	private fun steppingClock(): () -> Long {
		var t = 0L
		return {
			t += 10
			t
		}
	}

	/**
	 * Captures the per-generation timeline off the metrics sink - the executor's only
	 * programmatic outlet for it, since the log line is not observable from a test.
	 */
	private fun capturingMetrics(emitted: MutableList<E2eTimeline>): QuickBuildMetricsSink =
		object : QuickBuildMetricsSink by QuickBuildMetricsSink.Noop {
			override fun onReloadTimeline(timeline: E2eTimeline) {
				emitted += timeline
			}
		}

	private fun timingExecutor(emitted: MutableList<E2eTimeline>): LiveReloadExecutorImpl =
		LiveReloadExecutorImpl(
			daemon = daemon,
			deploy = deploy,
			layout = QuickBuildProjectLayout(projectRoot),
			entryActivity = "com.example.MainActivity",
			generations = tracker,
			workDir = File(projectRoot, ".androidide/quickbuild"),
			clock = steppingClock(),
			metrics = capturingMetrics(emitted),
		)

	private fun timedRequest(
		route: BuildRoute,
		changes: ChangedFiles,
		triggeredAtMillis: Long,
	) = BuildRequest(buildId = 1, changes = changes, route = route, triggeredAtMillis = triggeredAtMillis)

	@Test
	fun `a hot-swap deploy emits one e2e timeline with t0 from the request and t1-t3 from the clock`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()
			val executor = timingExecutor(emitted)

			executor.execute(timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5))

			// Clock order: startedAt=10, then the four span boundaries (20 scan start, 30
			// scan done, 40 compile done, 50 policy done, 60 dex done = compileDone),
			// deploySent=70, reloadLive=80.
			val t = emitted.single()
			assertThat(t.generation).isEqualTo(1)
			assertThat(t.trigger).isEqualTo(5)
			assertThat(t.compileDone).isEqualTo(60)
			assertThat(t.deploySent).isEqualTo(70)
			assertThat(t.reloadLive).isEqualTo(80)
			assertThat(t.compileMillis).isEqualTo(55) // trigger(5) -> compiled+dexed(60)
			assertThat(t.reloadMillis).isEqualTo(10) // deploySent(70) -> live(80)
		}

	@Test
	fun `the host spans partition the build and abut with no gap of their own`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			// Each span is one 10 ms clock tick with the stepping clock, and consecutive
			// spans share a boundary read - so the build's own spans cover [20, 60] exactly.
			// The queue span sits before them, from t0 to this build's start.
			val spans = emitted.single().spans!!
			assertThat(spans.queueMillis).isEqualTo(5) // trigger(5) -> startedAt(10)
			assertThat(spans.scanMillis).isEqualTo(10)
			assertThat(spans.compileRpcMillis).isEqualTo(10)
			assertThat(spans.policyMillis).isEqualTo(10)
			assertThat(spans.dexRpcMillis).isEqualTo(10)
			assertThat(spans.relinkRpcMillis).isNull() // no resources on this route
			assertThat(spans.totalMillis).isEqualTo(45)
		}

	@Test
	fun `the residual names the time no span measured, and it stays small`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			val t = emitted.single()
			// total 75 = spans 45 (queue 5 + the build's 40) + reload 10 + 20 of un-timed
			// edges: the startedAt->scan lead (asset packaging) and the dex->deploy tail.
			// Naming the queue moved 5 ms out of the residual and into a span the reader can
			// act on, which is the whole point of measuring it. Every millisecond is either
			// inside a named span or inside the residual - never silently attributed elsewhere.
			assertThat(t.totalMillis).isEqualTo(75)
			assertThat(t.accountedMillis).isEqualTo(55)
			assertThat(t.unaccountedMillis).isEqualTo(20)
			assertThat(t.accountedMillis + t.unaccountedMillis).isEqualTo(t.totalMillis)
		}

	@Test
	fun `a resources route accounts through its relink span`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile)), triggeredAtMillis = 5),
			)

			val t = emitted.single()
			assertThat(t.spans!!.relinkRpcMillis).isEqualTo(10)
			assertThat(t.spans!!.compileRpcMillis).isNull() // nothing compiled
			assertThat(t.accountedMillis + t.unaccountedMillis).isEqualTo(t.totalMillis)
		}

	@Test
	fun `daemon counts and the scratch filesystem ride along with the timing`() =
		runTest {
			daemon.scratchFsType = "fuse"
			daemon.compileReply =
				DaemonReply.Ok(
					CompileOutput(
						File("/fake/classes"),
						changedClassFiles = emptyList(),
						stats =
							CompileStats(
								preSnapMillis = 120,
								postSnapMillis = 130,
								javaAbiSnapMillis = 540,
								allSources = 292,
								kotlinToCompile = 74,
								javaSources = 218,
								changedClasses = 323,
								compileOrdinal = 3,
							),
					),
				)
			daemon.dexReply =
				DaemonReply.Ok(
					DexOutput(File("/fake/classes.dex"), stats = DexStats(classFiles = 464, classBytes = 1_530_112)),
				)
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			val t = emitted.single()
			assertThat(t.counts)
				.isEqualTo(
					E2eTimeline.BuildCounts(
						allSources = 292,
						kotlinDeclaredChanged = 74,
						javaSources = 218,
						changedClasses = 323,
						classFiles = 464,
						classBytes = 1_530_112,
						compileOrdinal = 3,
					),
				)
			assertThat(t.steps!!.preSnapMillis).isEqualTo(120)
			assertThat(t.steps!!.postSnapMillis).isEqualTo(130)
			assertThat(t.steps!!.javaAbiSnapMillis).isEqualTo(540)
			assertThat(t.scratchFsType).isEqualTo("fuse")
		}

	@Test
	fun `a daemon reporting no stats leaves the counts absent rather than zeroed`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			// A zero-filled row would read as "measured, and the build did nothing".
			assertThat(emitted.single().counts).isNull()
			assertThat(emitted.single().scratchFsType).isNull()
		}

	@Test
	fun `daemon step timings thread through to the emitted timeline`() =
		runTest {
			daemon.compileReply =
				DaemonReply.Ok(
					CompileOutput(
						File("/fake/classes"),
						changedClassFiles = emptyList(),
						kotlinMillis = 400,
						javaMillis = 50,
					),
				)
			daemon.dexReply = DaemonReply.Ok(DexOutput(File("/fake/classes.dex"), stripMillis = 20, d8Millis = 150))
			daemon.relinkReply =
				DaemonReply.Ok(RelinkOutput(File("/fake/resources.arsc"), aapt2CompileMillis = 80, aapt2LinkMillis = 120))
			val emitted = mutableListOf<E2eTimeline>()
			val executor = timingExecutor(emitted)

			executor.execute(
				timedRequest(
					BuildRoute.CodeAndResources,
					ChangedFiles.Known(setOf(sourceFile, resFile)),
					triggeredAtMillis = 5,
				),
			)

			assertThat(emitted.single().steps)
				.isEqualTo(
					E2eTimeline.StepTimings(
						kotlinMillis = 400,
						javaMillis = 50,
						stripMillis = 20,
						d8Millis = 150,
						aapt2CompileMillis = 80,
						aapt2LinkMillis = 120,
					),
				)
		}

	@Test
	fun `a resource-only deploy has no compile phase - compileDone folds into deploySent`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()
			val executor = timingExecutor(emitted)

			executor.execute(
				timedRequest(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile)), triggeredAtMillis = 5),
			)

			// No markCompileDone call: startedAt=10, relink spans [20,30], deploySent=40,
			// reloadLive=50. compileDone falls back to deploySent.
			val t = emitted.single()
			assertThat(t.compileDone).isEqualTo(40)
			assertThat(t.deploySent).isEqualTo(40)
			assertThat(t.reloadLive).isEqualTo(50)
			assertThat(t.stageMillis).isEqualTo(0)
			assertThat(t.compileMillis).isEqualTo(35) // relink + package land in compileMillis here
		}

	@Test
	fun `a restart deploy emits its timeline only after the reconnect is verified`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()
			val launcher = FakeLauncher()
			val executor =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					deployPolicy =
						DeployPolicy(listOf(ComponentInfo(ComponentKind.SERVICE, "com.example.SyncService"))),
					proxyAppPackage = "com.example.quickbuild",
					launcherActivity = "com.example.quickbuild.proxies.Proxy0Activity",
					launcher = launcher,
					clock = steppingClock(),
					metrics = capturingMetrics(emitted),
				)
			serviceRecompiled()

			val outcome =
				executor.execute(
					timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
				)

			// Clock: startedAt=10, span boundaries 20..60 (compileDone=60), deploySent=70,
			// reloadLive=80 (after the verified reconnect). The reported duration is that same
			// t3 minus t0 (80 - 5), off one clock read rather than a second later one, so it
			// cannot drift past the loop it describes.
			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 75, restarted = true))
			val t = emitted.single()
			assertThat(t.compileDone).isEqualTo(60)
			assertThat(t.deploySent).isEqualTo(70)
			assertThat(t.reloadLive).isEqualTo(80)
			// The number the user reads is the timeline's own total, which is what made the two
			// Build Output lines reconcilable (manual QA, 2026-08-11).
			assertThat((outcome as BuildOutcome.Success).durationMillis).isEqualTo(t.totalMillis)
		}

	@Test
	fun `a compile error emits no timeline - nothing reloaded`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()
			daemon.compileReply =
				DaemonReply.BuildFailed(listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "boom")))

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			assertThat(emitted).isEmpty()
		}

	@Test
	fun `a warm-compile build emits no timeline - nothing reloaded`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.WarmCompile, ChangedFiles.Unknown, triggeredAtMillis = 5),
			)

			assertThat(emitted).isEmpty()
		}

	@Test
	fun `a failed deploy emits no timeline - the reload never landed`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()
			deploy.result = DeployResult.TimedOut(15_000)

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			assertThat(emitted).isEmpty()
		}

	private class RecordingMetrics : org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink {
		val timelines = mutableListOf<E2eTimeline>()

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
		) = Unit

		override fun onReloadTimeline(timeline: E2eTimeline) {
			timelines += timeline
		}
	}

	@Test
	fun `a successful deploy reports the timeline to the analytics sink exactly once`() =
		runTest {
			val metrics = RecordingMetrics()
			val executor =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = steppingClock(),
					metrics = metrics,
				)

			executor.execute(timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5))

			assertThat(metrics.timelines).hasSize(1)
			assertThat(metrics.timelines.single().trigger).isEqualTo(5)
		}

	@Test
	fun `a failed deploy reports no timeline to analytics - nothing reached the user`() =
		runTest {
			val metrics = RecordingMetrics()
			val executor =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = steppingClock(),
					metrics = metrics,
				)
			deploy.result = DeployResult.TimedOut(15_000)

			executor.execute(timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5))

			assertThat(metrics.timelines).isEmpty()
		}

	@Test
	fun `a throwing analytics sink never fails a build the user already saw reload`() =
		runTest {
			val throwingMetrics =
				object : org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink {
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
					) = Unit

					override fun onReloadTimeline(timeline: E2eTimeline): Unit = throw RuntimeException("sink boom")
				}
			val executor =
				LiveReloadExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = QuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = { 1000L },
					metrics = throwingMetrics,
				)

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			// The sink threw inside reportTimeline but the guard swallowed it: the build the
			// user already saw reload still reports Success.
			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
		}

	private class FakeLauncher(
		var result: Boolean = true,
	) : ProxyAppLauncher {
		val calls = mutableListOf<Pair<String, String?>>()

		/** Per-attempt override of [result]; the argument is the 1-based attempt number. */
		var resultFor: ((attempt: Int) -> Boolean)? = null

		override fun launch(
			packageName: String,
			activityClass: String?,
		): Boolean {
			calls += packageName to activityClass
			return resultFor?.invoke(calls.size) ?: result
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
	) = LiveReloadExecutorImpl(
		daemon = daemon,
		deploy = deploy,
		layout = QuickBuildProjectLayout(projectRoot),
		entryActivity = "com.example.MainActivity",
		generations = tracker,
		workDir = File(projectRoot, ".androidide/quickbuild"),
		deployPolicy = policy,
		proxyAppPackage = "com.example.quickbuild",
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
	fun `helper-only edit restarts too - the payload redefines the service either way`() =
		runTest {
			// This edit names nothing the service inherits from, and the whole pipeline still
			// has to take the restart route: the dex it ships carries the service class.
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			daemon.compileReply =
				DaemonReply.Ok(
					org.appdevforall.cotg.quickbuild.data
						.CompileOutput(File("/fake/classes"), listOf("com/example/util/Formatter.class")),
				)

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			assertThat(metadataOf(deploy.calls.single()).get("restart").asString).isEqualTo("true")
			assertThat(deploy.awaitDisconnectCalls).isNotEmpty()
			assertThat(launcher.calls)
				.containsExactly("com.example.quickbuild" to "com.example.quickbuild.proxies.Proxy0Activity")
		}

	@Test
	fun `helper-only edit hot-swaps when no service, provider or Application is declared`() =
		runTest {
			// The negative control: identical edit and pipeline, an activity-only component
			// list. Without this the test above would pass on a policy that restarts always.
			val launcher = FakeLauncher()
			val executor =
				restartExecutor(
					launcher,
					policy =
						DeployPolicy(
							listOf(
								ComponentInfo(
									ComponentKind.ACTIVITY,
									"com.example.MainActivity",
									proxyClass = "com.example.quickbuild.proxies.Proxy0Activity",
									launcher = true,
								),
							),
						),
				)
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
	fun `restart relaunch reconnecting at an older generation routes to a proxy app rebuild - the payload did not persist`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			// The process died around the payload and the fresh boot came back on the
			// previous generation: claiming success would be the silent-stale lie.
			deploy.reconnectGeneration = { 0L }

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
			assertThat((outcome as BuildOutcome.RequiresProxyAppRebuild).reason)
				.isEqualTo(InvalidationReason.OUTDATED_BASELINE)
			assertThat(outcome.detail).contains("generation 0")
		}

	@Test
	fun `restart relaunch that never reconnects is a deploy failure, after a second try`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			deploy.reconnectGeneration = { null }

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			val message = (outcome as BuildOutcome.DeployFailure).message
			assertThat(message).contains("did not come back")
			// The launch call cannot tell a start Android blocked from one that worked, so the
			// message must report what we know - the app never came back - and must not assert
			// a relaunch that may never have happened.
			assertThat(message).doesNotContain("was relaunched")
			// Two attempts, and no more: a dead app has to reach the user rather than become a
			// retry storm.
			assertThat(launcher.calls).hasSize(2)
			assertThat(deploy.awaitReconnectCalls).hasSize(2)
		}

	@Test
	fun `a relaunch swallowed by the dead task is recovered by the second one`() =
		runTest {
			// The measured defect: the first start is handed to the killed process's own
			// activity record and dropped, so nothing comes back. The second start finds no
			// task and creates one. Without the retry this build ends at "open it manually".
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			var attempt = 0
			deploy.reconnectGeneration = { deployed ->
				attempt++
				if (attempt == 1) null else deployed
			}

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			assertThat(launcher.calls).hasSize(2)
		}

	@Test
	fun `a first relaunch that reconnects is not retried`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()

			executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(launcher.calls).hasSize(1)
			assertThat(deploy.awaitReconnectCalls).hasSize(1)
		}

	@Test
	fun `a second relaunch that cannot even start is not waited on`() =
		runTest {
			// The launcher refusing outright is not the swallowed-start case: nothing ran, so
			// another reconnect wait would just be 15 s of silence for the user.
			val launcher = FakeLauncher().apply { resultFor = { attempt -> attempt == 1 } }
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			deploy.reconnectGeneration = { null }

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat(launcher.calls).hasSize(2)
			assertThat(deploy.awaitReconnectCalls).hasSize(1)
		}

	@Test
	fun `restart ack without a process exit routes to a proxy app rebuild - old runtime hot-swapped`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			deploy.disconnects = false

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
			assertThat((outcome as BuildOutcome.RequiresProxyAppRebuild).reason)
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
	fun `pre-v2 baseline refuses a code deploy BEFORE deploying - proxy app rebuild instead`() =
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

			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
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

	/**
	 * Executor wired the way a real session is (launcher + package known) but with no
	 * restart-forcing policy, so deploys hot-swap: the defect-#88 surface, where a
	 * proxy app rebuild reinstall killed the proxy app and the next deploy finds NotConnected.
	 */
	private fun relaunchExecutor(
		launcher: FakeLauncher,
		reconnectTimeoutMillis: Long = 15_000L,
	) = LiveReloadExecutorImpl(
		daemon = daemon,
		deploy = deploy,
		layout = QuickBuildProjectLayout(projectRoot),
		entryActivity = "com.example.MainActivity",
		generations = tracker,
		workDir = File(projectRoot, ".androidide/quickbuild"),
		proxyAppPackage = "com.example.quickbuild",
		launcherActivity = "com.example.quickbuild.proxies.Proxy0Activity",
		launcher = launcher,
		restartReconnectTimeoutMillis = reconnectTimeoutMillis,
		clock = { 1000L },
	)

	@Test
	fun `NotConnected deploy relaunches the proxy app, awaits the rebind and retries exactly once - defect 88`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = relaunchExecutor(launcher)
			// First attempt hits the post-reinstall dead connection; the retry (default
			// result) lands.
			deploy.resultQueue += DeployResult.NotConnected

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(deploy.calls).hasSize(2)
			// Same payload both times: the first attempt never reached the app.
			assertThat(deploy.calls[0].generation).isEqualTo(deploy.calls[1].generation)
			assertThat(launcher.calls)
				.containsExactly("com.example.quickbuild" to "com.example.quickbuild.proxies.Proxy0Activity")
			assertThat(deploy.awaitReconnectCalls).hasSize(1)
		}

	@Test
	fun `NotConnected RESTART deploy recovers too - relaunch, rebind, one retry, then the restart sequence`() =
		runTest {
			// The other half of the defect-88 surface: a service/receiver/provider edit
			// after the proxy app rebuild reinstall deploys through deployRestart, which must
			// route through the same recovery as the hot-swap path.
			val launcher = FakeLauncher()
			val executor = restartExecutor(launcher)
			serviceRecompiled()
			// First attempt hits the post-reinstall dead connection; the retried deploy
			// (default result) acks, and the normal restart sequence follows.
			deploy.resultQueue += DeployResult.NotConnected

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0, restarted = true))
			// Same restart payload both times: the first attempt never reached the app.
			assertThat(deploy.calls).hasSize(2)
			assertThat(deploy.calls[0].generation).isEqualTo(deploy.calls[1].generation)
			deploy.calls.forEach { call ->
				assertThat(metadataOf(call).get("restart").asString).isEqualTo("true")
			}
			// One launch for the recovery rebind, one for the restart relaunch itself;
			// likewise one reconnect wait each.
			assertThat(launcher.calls).hasSize(2)
			assertThat(deploy.awaitReconnectCalls).hasSize(2)
			assertThat(deploy.awaitDisconnectCalls).hasSize(1)
		}

	@Test
	fun `a connected proxy app deploys with no relaunch and no rebind wait`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = relaunchExecutor(launcher)

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			assertThat(deploy.calls).hasSize(1)
			assertThat(launcher.calls).isEmpty()
			assertThat(deploy.awaitReconnectCalls).isEmpty()
		}

	@Test
	fun `still NotConnected after the one retry keeps the failure with the relaunch remedy - no third attempt`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = relaunchExecutor(launcher)
			deploy.result = DeployResult.NotConnected // both attempts fail

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			// A plain DeployFailure: the reducer keeps the session Ready on it (no
			// teardown, no proxy app rebuild), so the next save just tries again.
			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("Tap Quick Build to start it")
			assertThat(deploy.calls).hasSize(2)
			assertThat(launcher.calls).hasSize(1)
		}

	@Test
	fun `rebind wait is bounded by the injected reconnect timeout and a timeout skips the retry`() =
		runTest {
			val launcher = FakeLauncher()
			val executor = relaunchExecutor(launcher, reconnectTimeoutMillis = 1_234)
			deploy.result = DeployResult.NotConnected
			deploy.reconnectGeneration = { null } // app never rebinds within the bound

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			// Retrying against a still-dead connection would just double the wait.
			assertThat(deploy.calls).hasSize(1)
			assertThat(deploy.awaitReconnectCalls).containsExactly(1_234L)
		}

	@Test
	fun `a relaunch that cannot even start skips the rebind wait and keeps the failure`() =
		runTest {
			val launcher = FakeLauncher(result = false)
			val executor = relaunchExecutor(launcher)
			deploy.result = DeployResult.NotConnected

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("Tap Quick Build to start it")
			assertThat(deploy.calls).hasSize(1)
			assertThat(deploy.awaitReconnectCalls).isEmpty()
		}

	@Test
	fun `NotConnected with no launcher wired fails on the first attempt but still names the remedy`() =
		runTest {
			// The default executor from setUp has no launcher/package (pre-#88 wiring).
			deploy.result = DeployResult.NotConnected

			val outcome =
				executor.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isInstanceOf(BuildOutcome.DeployFailure::class.java)
			assertThat((outcome as BuildOutcome.DeployFailure).message).contains("Tap Quick Build to start it")
			assertThat(deploy.calls).hasSize(1)
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
							org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason.MANIFEST_CHANGED,
						),
					),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.InfrastructureFailure::class.java)
			assertThat(deploy.calls).isEmpty()
		}

	/**
	 * Builds an executor wired to a launcher, so the deploy pipeline can actually reach
	 * the launch decision. The rest of the suite leaves the launcher null, which makes
	 * [org.appdevforall.cotg.quickbuild.service.deploy.PayloadDeployer] bail before the decision and hides the wiring these three tests
	 * cover.
	 */
	private fun launchableExecutor(): LiveReloadExecutorImpl =
		LiveReloadExecutorImpl(
			daemon = daemon,
			deploy = deploy,
			layout = QuickBuildProjectLayout(projectRoot),
			entryActivity = "com.example.MainActivity",
			generations = tracker,
			workDir = File(projectRoot, ".androidide/quickbuild"),
			proxyAppPackage = "com.example.app",
			launcher =
				ProxyAppLauncher { packageName, activityClass ->
					launchCalls += packageName to activityClass
					true
				},
			clock = { 1000L },
		)

	/**
	 * The seeding half of the gate: `execute` copies the request's ask onto the flag the
	 * deployer reads, so a save's failed deploy must never open the app.
	 *
	 * Pins the mutation "seed the flag to true" - which is the shipped bug this feature
	 * fixed - at the executor, where [PayloadDeployerTest] cannot see it.
	 */
	@Test
	fun `a save's deploy to a closed app does not launch it`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			val executor = launchableExecutor()

			val outcome =
				executor.execute(
					request(
						BuildRoute.CodeOnly,
						ChangedFiles.Known(setOf(sourceFile)),
						userInitiated = false,
					),
				)

			assertThat(launchCalls).isEmpty()
			assertThat(deploy.calls).hasSize(1)
			val failure = outcome as BuildOutcome.DeployFailure
			assertThat(failure.proxyAppNotConnected).isFalse()
		}

	/**
	 * The promotion half: a tap landing mid-build must change the launch decision of the
	 * build already in flight, which is why the deployer reads the flag live rather than
	 * capturing it. Pins two mutations - dropping the `markCurrentBuildUserInitiated`
	 * override (the interface default is a no-op, so everything else stays green), and
	 * snapshotting `userInitiated()` at deploy entry.
	 */
	@Test
	fun `a tap landing mid-build promotes it, so its deploy opens the closed app`() =
		runTest {
			val executor = launchableExecutor()
			// Fires while the build is between compile and deploy, which is exactly when a
			// real tap lands: the request was a save, so only the promotion can open the app.
			daemon.onCompile = { executor.markCurrentBuildUserInitiated() }
			deploy.result = DeployResult.NotConnected

			val outcome =
				executor.execute(
					request(
						BuildRoute.CodeOnly,
						ChangedFiles.Known(setOf(sourceFile)),
						userInitiated = false,
					),
				)

			assertThat(launchCalls).containsExactly("com.example.app" to null)
			val failure = outcome as BuildOutcome.DeployFailure
			// Launched and still absent - the honest cannot-stay-up evidence.
			assertThat(failure.proxyAppNotConnected).isTrue()
		}

	/**
	 * The reseed half: the promotion belongs to the build that was promoted, not to the
	 * session. Without the per-request reseed the flag latches true and every later save
	 * opens the app.
	 */
	@Test
	fun `a promotion does not carry over to the next save`() =
		runTest {
			val executor = launchableExecutor()
			daemon.onCompile = { executor.markCurrentBuildUserInitiated() }
			deploy.result = DeployResult.NotConnected
			executor.execute(
				request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), userInitiated = false),
			)
			assertThat(launchCalls).hasSize(1)

			daemon.onCompile = {}
			val outcome =
				executor.execute(
					request(
						BuildRoute.CodeOnly,
						ChangedFiles.Known(setOf(sourceFile)),
						userInitiated = false,
					),
				)

			assertThat(launchCalls).hasSize(1)
			assertThat((outcome as BuildOutcome.DeployFailure).proxyAppNotConnected).isFalse()
		}
}
