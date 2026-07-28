package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.DexOutput
import org.appdevforall.cotg.quickbuild.data.RelinkOutput
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
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
	fun `seed route compiles everything and dexes but deploys NOTHING at an unmoved generation`() =
		runTest {
			val outcome = executor.execute(request(BuildRoute.Seed, ChangedFiles.Unknown))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(0, 0))
			// The whole source set goes through the compiler (IC-cache seed)...
			assertThat(daemon.compileCalls).hasSize(1)
			assertThat(daemon.compileCalls[0].second).containsExactly(sourceFile)
			// ...d8 warms too...
			assertThat(daemon.dexCalls).hasSize(1)
			// ...but nothing reaches the device: no deploy, no relink, generation unmoved.
			assertThat(deploy.calls).isEmpty()
			assertThat(daemon.relinkCalls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	// Review gap (2026-07-26 #69): the seed is invisible by contract - the test app
	// already runs exactly the sources it compiles - so its overlay must not flash
	// "build ok" for a build the user never triggered.
	@Test
	fun `a seed success stays silent on the test-app status channel`() =
		runTest {
			val outcome = executor.execute(request(BuildRoute.Seed, ChangedFiles.Unknown))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(0, 0))
			assertThat(deploy.statusCalls).isEmpty()
		}

	@Test
	fun `a seed compile error stays silent on the test-app status channel but keeps the real outcome`() =
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

			val outcome = executor.execute(request(BuildRoute.Seed, ChangedFiles.Unknown))

			// The orchestrator still needs the honest outcome (it routes recovery),
			// but the test-app overlay must not flash "build failed" for sources the
			// app is running fine - the setup build compiled them green moments ago.
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
			assertThat(metadataOf(call).get("reason").asString).isEqualTo("resources")
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
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot, stableIdsFile = stableIds),
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
			// ADFA-4128 Bug 8: a relink of the project's own res/ alone can't resolve a
			// resource a dependency AAR provides, so the layout's reported merged_res /
			// dependency-resource units must reach the daemon on every relink.
			val libraryResource =
				File(projectRoot, "app/build/intermediates/merged_res/debug/values_values.arsc.flat")
					.apply {
						parentFile!!.mkdirs()
						writeText("")
					}
			val executorWithLibraryResources =
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot, libraryResourceFlats = listOf(libraryResource)),
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

	/** Returns 10, 20, 30, ... on each call - so t0<t1<t2<t3 are distinguishable. */
	private fun steppingClock(): () -> Long {
		var t = 0L
		return {
			t += 10
			t
		}
	}

	private fun timingExecutor(emitted: MutableList<E2eTimeline>): QuickBuildExecutorImpl =
		QuickBuildExecutorImpl(
			daemon = daemon,
			deploy = deploy,
			layout = DefaultQuickBuildProjectLayout(projectRoot),
			entryActivity = "com.example.MainActivity",
			generations = tracker,
			workDir = File(projectRoot, ".androidide/quickbuild"),
			clock = steppingClock(),
			emitTimeline = { emitted += it },
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
			// spans share a boundary read - so they cover [20, 60] exactly.
			val spans = emitted.single().spans!!
			assertThat(spans.scanMillis).isEqualTo(10)
			assertThat(spans.compileRpcMillis).isEqualTo(10)
			assertThat(spans.policyMillis).isEqualTo(10)
			assertThat(spans.dexRpcMillis).isEqualTo(10)
			assertThat(spans.relinkRpcMillis).isNull() // no resources on this route
			assertThat(spans.totalMillis).isEqualTo(40)
		}

	@Test
	fun `the residual names the time no span measured, and it stays small`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
			)

			val t = emitted.single()
			// total 75 = spans 40 + reload 10 + 25 of un-timed edges: the trigger->scan lead
			// (asset packaging) and the dex->deploy tail. Every millisecond is either inside
			// a named span or inside the residual - never silently attributed elsewhere.
			assertThat(t.totalMillis).isEqualTo(75)
			assertThat(t.accountedMillis).isEqualTo(50)
			assertThat(t.unaccountedMillis).isEqualTo(25)
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
						kotlinCompiled = 74,
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
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					deployPolicy =
						DeployPolicy(listOf(ComponentInfo(ComponentKind.SERVICE, "com.example.SyncService"))),
					testAppPackage = "com.example.quickbuild",
					launcherActivity = "com.example.quickbuild.proxies.Proxy0Activity",
					launcher = launcher,
					clock = steppingClock(),
					emitTimeline = { emitted += it },
				)
			serviceRecompiled()

			val outcome =
				executor.execute(
					timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5),
				)

			// Clock: startedAt=10, span boundaries 20..60 (compileDone=60), deploySent=70,
			// reloadLive=80 (after the verified reconnect), durationMillis read at 90.
			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 80, restarted = true))
			val t = emitted.single()
			assertThat(t.compileDone).isEqualTo(60)
			assertThat(t.deploySent).isEqualTo(70)
			assertThat(t.reloadLive).isEqualTo(80)
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
	fun `a seed build emits no timeline - nothing reloaded`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()

			timingExecutor(emitted).execute(
				timedRequest(BuildRoute.Seed, ChangedFiles.Unknown, triggeredAtMillis = 5),
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

	private class RecordingMetrics : org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink {
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

		override fun onRebaseline(
			isSuccess: Boolean,
			durationMillis: Long,
		) = Unit

		override fun onReloadTimeline(timeline: E2eTimeline) {
			timelines += timeline
		}
	}

	@Test
	fun `a successful deploy reports the timeline to the analytics sink, matching the logged line`() =
		runTest {
			val emitted = mutableListOf<E2eTimeline>()
			val metrics = RecordingMetrics()
			val executor =
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
					entryActivity = "com.example.MainActivity",
					generations = tracker,
					workDir = File(projectRoot, ".androidide/quickbuild"),
					clock = steppingClock(),
					emitTimeline = { emitted += it },
					metrics = metrics,
				)

			executor.execute(timedRequest(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile)), triggeredAtMillis = 5))

			// The analytics channel and the log line get the SAME timeline (no parallel path).
			assertThat(metrics.timelines).hasSize(1)
			assertThat(metrics.timelines).isEqualTo(emitted)
			assertThat(metrics.timelines.single().trigger).isEqualTo(5)
		}

	@Test
	fun `a failed deploy reports no timeline to analytics - nothing reached the user`() =
		runTest {
			val metrics = RecordingMetrics()
			val executor =
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
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
				object : org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink {
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

					override fun onRebaseline(
						isSuccess: Boolean,
						durationMillis: Long,
					) = Unit

					override fun onReloadTimeline(timeline: E2eTimeline): Unit = throw RuntimeException("sink boom")
				}
			val executor =
				QuickBuildExecutorImpl(
					daemon = daemon,
					deploy = deploy,
					layout = DefaultQuickBuildProjectLayout(projectRoot),
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
