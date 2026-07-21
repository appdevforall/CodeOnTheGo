package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.SetupInfo
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildExecutor
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class QuickBuildSessionManagerTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy = FakeDeploy()
	private val connections = TestAppConnections()
	private val store = MemoryGenerationStore()
	private val userMessages = mutableListOf<String>()

	/** Requests seen by the scripted executor, with per-request scripted outcomes. */
	private val executed = mutableListOf<BuildRequest>()

	/** SetupInfo of every executor the manager built (provision + each rebaseline). */
	private val factorySetups = mutableListOf<SetupInfo>()

	/** Flat trace of metrics-sink calls, e.g. "started:CodeOnly:1", "rebaseline:true". */
	private val metricsEvents = mutableListOf<String>()
	private var metricsThrow = false

	private val recordingMetrics =
		object : QuickBuildMetricsSink {
			override fun onSessionStarted() {
				record { "session:started" }
			}

			override fun onBuildStarted(
				buildId: Long,
				route: BuildRoute,
				changes: ChangedFiles,
			) {
				record {
					val count = (changes as? ChangedFiles.Known)?.files?.size
					"started:${route.javaClass.simpleName}:$count"
				}
			}

			override fun onBuildFinished(
				buildId: Long,
				outcome: BuildOutcome,
			) {
				record { "finished:${outcome.javaClass.simpleName}" }
			}

			override fun onInvalidation(reason: InvalidationReason) {
				record { "invalidated:$reason" }
			}

			override fun onRebaseline(
				isSuccess: Boolean,
				durationMillis: Long,
			) {
				record { "rebaseline:$isSuccess" }
			}

			private fun record(event: () -> String) {
				if (metricsThrow) error("metrics sink boom")
				metricsEvents += event()
			}
		}
	private val scriptedOutcomes = ArrayDeque<BuildOutcome>()
	private var provisionCount = 0
	private var rebaselineCount = 0
	private var prewarmCount = 0
	private var provisionOutcome: (() -> ProvisionOutcome)? = null
	private var rebaselineOutcome: () -> RebaselineOutcome = { defaultRebaselineSuccess() }
	private var prewarmGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var prewarmError: Throwable? = null
	private var provisionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var provisionSurvivesCancel = false

	/** Captures the watcher the manager builds so a test can push change batches. */
	private var watcher: FakeWatcher? = null

	/**
	 * Stands in for [AndroidProjectWatcher]: mirrors its two observable behaviours -
	 * it only forwards after [start] (a change before a live session is dropped), and it
	 * applies the same [WatchFilter] so irrelevant paths (build intermediates) are ignored.
	 */
	private class FakeWatcher(
		private val filter: WatchFilter,
	) : ProjectWatcher {
		private var onBatch: ((Set<File>) -> Unit)? = null

		override fun start(onBatch: (Set<File>) -> Unit) {
			this.onBatch = onBatch
		}

		override fun stop() {
			onBatch = null
		}

		fun emit(files: Set<File>) {
			val relevant = files.filterTo(HashSet(), filter::isRelevant)
			if (relevant.isNotEmpty()) onBatch?.invoke(relevant)
		}
	}

	private lateinit var sourceFile: File
	private lateinit var gradleFile: File

	@BeforeEach
	fun setUp() {
		sourceFile =
			File(projectRoot, "app/src/main/java/com/example/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}
		gradleFile = File(projectRoot, "build.gradle.kts").apply { writeText("// build") }
	}

	private fun defaultProvisionOutcome(): ProvisionOutcome =
		ProvisionOutcome.Success(
			setup =
				SetupInfo(
					testAppPackage = "com.example.quickbuild",
					entryActivity = "com.example.MainActivity",
					apk = File(projectRoot, "test-app.apk"),
					classpath = emptyList(),
					proxyClassesDir = null,
					transformedManifest = null,
				),
			testAppUid = 10123,
			layout = DefaultQuickBuildProjectLayout(projectRoot),
		)

	private fun defaultRebaselineSuccess(): RebaselineOutcome.Success {
		val provision = defaultProvisionOutcome() as ProvisionOutcome.Success
		return RebaselineOutcome.Success(setup = provision.setup, layout = provision.layout)
	}

	private fun TestScope.createManager(): QuickBuildSessionManager {
		val provisioner =
			object : QuickBuildProvisioner {
				override suspend fun provision(): ProvisionOutcome {
					provisionCount++
					provisionGate?.let { gate ->
						if (provisionSurvivesCancel) {
							try {
								gate.await()
							} catch (e: kotlinx.coroutines.CancellationException) {
								// Simulates provisioning work already past the point of no
								// return: the cancel does not stop it from producing an
								// outcome, so only the epoch guard can discard it.
							}
						} else {
							gate.await()
						}
					}
					return provisionOutcome?.invoke() ?: defaultProvisionOutcome()
				}

				override suspend fun rebaseline(): RebaselineOutcome {
					rebaselineCount++
					return rebaselineOutcome()
				}

				override suspend fun warmSetupBuild() {
					prewarmCount++
					prewarmGate?.await()
					prewarmError?.let { throw it }
				}
			}
		return QuickBuildSessionManager(
			daemon = daemon,
			deploy = deploy,
			provisioner = provisioner,
			connections = connections,
			paths = FakePaths(projectRoot),
			dispatcher = StandardTestDispatcher(testScheduler),
			generationStoreFactory = { store },
			executorFactory = { setup, _, tracker ->
				factorySetups += setup
				object : QuickBuildExecutor {
					override suspend fun execute(request: BuildRequest): BuildOutcome {
						executed += request
						return scriptedOutcomes.removeFirstOrNull()
							?: BuildOutcome.Success(tracker.next(), 5)
					}
				}
			},
			onUserMessage = { userMessages += it },
			watcherFactory = { _, _, filter, _ -> FakeWatcher(filter).also { watcher = it } },
			metrics = recordingMetrics,
		)
	}

	/** Simulate an on-device file change (from any source) landing on the watcher. */
	private fun QuickBuildSessionManager.save(file: File) {
		watcher?.emit(setOf(file))
	}

	@Test
	fun `first tap provisions and lands in Ready at the persisted generation`() =
		runTest {
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(1)
			assertThat(connections.expectedUid).isEqualTo(10123)
			assertThat(connections.expectedPackage).isEqualTo("com.example.quickbuild")
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))
		}

	@Test
	fun `a non-compose project configures the daemon without compiler plugins`() =
		runTest {
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(daemon.startConfigs.single().compilerPlugins).isEmpty()
		}

	@Test
	fun `a compose project configures the daemon with the staged compose plugin`() =
		runTest {
			provisionOutcome = {
				val default = defaultProvisionOutcome() as ProvisionOutcome.Success
				default.copy(setup = default.setup.copy(composeEnabled = true))
			}
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(daemon.startConfigs.single().compilerPlugins)
				.containsExactly(FakePaths(projectRoot).composeCompilerPlugin)
		}

	@Test
	fun `provisioning failure surfaces the error and returns to Idle`() =
		runTest {
			provisionOutcome = { ProvisionOutcome.Failure("no build service") }
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(userMessages).containsExactly("no build service")
		}

	@Test
	fun `a relevant save flows through the orchestrator to a deploy`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()

			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			assertThat((request.changes as ChangedFiles.Known).files).containsExactly(sourceFile)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(1, 5))
		}

	@Test
	fun `an irrelevant save (build intermediates) triggers nothing`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val outside =
				File(projectRoot, "app/build/generated/Gen.kt").apply {
					parentFile!!.mkdirs()
					writeText("class Gen")
				}
			manager.save(outside)
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `saves before any session are ignored`() =
		runTest {
			val manager = createManager()

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
		}

	@Test
	fun `a gradle file save invalidates and runs the full rebaseline round trip`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(1)
			// Quick path never ran for the gradle change.
			assertThat(executed).isEmpty()
			// Rebaseline succeeded: back to Ready at the unchanged generation.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a RequiresRebaseline outcome routes into the rebaseline fallback`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			scriptedOutcomes +=
				BuildOutcome.RequiresRebaseline(
					InvalidationReason.OUTDATED_BASELINE,
					"baseline predates component metadata",
				)

			manager.save(sourceFile)
			advanceUntilIdle()

			// The quick build ran once, refused to deploy, and the session fell back to
			// the full rebaseline (which absorbs the pending change) instead of failing.
			assertThat(executed).hasSize(1)
			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(metricsEvents).contains("invalidated:OUTDATED_BASELINE")
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a restart deploy surfaces restarted in state and status`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			scriptedOutcomes += BuildOutcome.Success(1, 5, restarted = true)

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Deployed(1, 5, restarted = true))
			assertThat(manager.status.value)
				.isEqualTo(QuickBuildStatus.UpToDate(1, 5, restarted = true))
		}

	@Test
	fun `a deployed build reports started and finished metrics`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			// Provisioning reported the session boundary (build ids restart per session).
			assertThat(metricsEvents).contains("session:started")
			metricsEvents.clear()

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(metricsEvents)
				.containsExactly(
					"started:CodeOnly:1",
					"finished:Success",
				).inOrder()
		}

	@Test
	fun `an invalidating save reports invalidation and rebaseline metrics`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			metricsEvents.clear()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(metricsEvents)
				.containsExactly(
					"invalidated:GRADLE_CONFIG_CHANGED",
					"rebaseline:true",
				).inOrder()
		}

	@Test
	fun `a throwing metrics sink never breaks the build`() =
		runTest {
			metricsThrow = true
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()

			// The sink threw on every call; the build still deployed.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	@Test
	fun `a failed rebaseline surfaces the error`() =
		runTest {
			rebaselineOutcome = { RebaselineOutcome.Failure("manifest does not build") }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(userMessages).contains("manifest does not build")
		}

	@Test
	fun `a rebaseline rebuilds the executor from the re-read setup`() =
		runTest {
			// The rebaseline regenerates setup.json; here it comes back schema v2 (e.g.
			// a manifest edit added a service the new baseline proxies).
			rebaselineOutcome = {
				val base = defaultRebaselineSuccess()
				base.copy(setup = base.setup.copy(schema = 2))
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(factorySetups).hasSize(1)

			manager.save(gradleFile)
			advanceUntilIdle()

			// The live session's executor was rebuilt from the RE-READ setup, not left
			// on the provisioning-time snapshot - otherwise the deploy policy would
			// keep routing on stale component facts for the rest of the session.
			assertThat(factorySetups).hasSize(2)
			assertThat(factorySetups.last().schema).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a stale reconnect triggers a catch-up build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)

			// A killed-and-relaunched test app that lost the deployed payload boots and
			// reconnects at gen 0 - verifiably running code this session superseded.
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			assertThat(executed).hasSize(2)
			assertThat(executed.last().forced).isTrue()
		}

	@Test
	fun `a reconnect at the deployed generation does not trigger a build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)

			connections.onConnected(connectedAt(1))
			advanceUntilIdle()

			assertThat(executed).hasSize(1)
		}

	@Test
	fun `a gen-0 reconnect after a rebaseline does not trigger a catch-up build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			val buildsBefore = executed.size

			// The rebaseline reinstalled a fresh baseline; its gen-0 IS current code,
			// so a reconnect at 0 must not be mistaken for staleness.
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			assertThat(executed).hasSize(buildsBefore)
		}

	private fun connectedAt(generation: Long): ConnectedTarget =
		ConnectedTarget(
			target =
				object : com.itsaky.androidide.quickbuild.IQuickBuildTarget {
					override fun onBuildStatus(statusJson: String?) = Unit

					override fun onPayload(
						generation: Long,
						dexPayload: android.os.ParcelFileDescriptor?,
						resourcesPayload: android.os.ParcelFileDescriptor?,
						assetsPayload: android.os.ParcelFileDescriptor?,
						metadataJson: String?,
					) = Unit

					override fun asBinder(): android.os.IBinder? = null
				},
			packageName = "com.example.quickbuild",
			runningGeneration = generation,
		)

	@Test
	fun `tap while Ready forces a build even with nothing changed`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val request = executed.single()
			assertThat(request.forced).isTrue()
			assertThat(request.route).isEqualTo(BuildRoute.NoOp)
		}

	@Test
	fun `compile error lands in Ready with the failure surfaced and generation unmoved`() =
		runTest {
			val diagnostics =
				listOf(
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "unresolved reference"),
				)
			scriptedOutcomes += BuildOutcome.CompileError(diagnostics)
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()

			val state = manager.state.value
			assertThat(state).isInstanceOf(QuickBuildSessionState.Ready::class.java)
			assertThat((state as QuickBuildSessionState.Ready).generation).isEqualTo(0)
			assertThat(state.lastFailure)
				.isEqualTo(SessionFailure.CompileError(diagnostics))
			assertThat(manager.status.value)
				.isEqualTo(QuickBuildStatus.Failed(0, SessionFailure.CompileError(diagnostics)))
		}

	@Test
	fun `daemon death degrades, respawns and re-seeds with Unknown`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			daemon.die(exitCode = 137)
			advanceUntilIdle()

			// Respawned: configure ran twice (provision + respawn)...
			assertThat(daemon.startConfigs).hasSize(2)
			// ...and the re-seed forced a full recompile via Unknown.
			val request = executed.single()
			assertThat(request.changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(request.route).isEqualTo(BuildRoute.CodeAndResources)
			// The re-seed build deployed generation 1.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	@Test
	fun `test app crash reported by the host service surfaces as a session failure`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			connections.report(TargetReport.Crashed(0, "NullPointerException in onCreate"))
			advanceUntilIdle()

			val state = manager.state.value
			assertThat(state).isInstanceOf(QuickBuildSessionState.Ready::class.java)
			assertThat((state as QuickBuildSessionState.Ready).lastFailure)
				.isEqualTo(SessionFailure.TestAppCrash("NullPointerException in onCreate"))
		}

	@Test
	fun `prewarm runs the setup build only - no install, no daemon, back to Idle`() =
		runTest {
			val manager = createManager()

			manager.prewarm()
			advanceUntilIdle()

			assertThat(prewarmCount).isEqualTo(1)
			// Nothing provisioned: no install path, no daemon, no watcher, no session.
			assertThat(provisionCount).isEqualTo(0)
			assertThat(daemon.startConfigs).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden)
		}

	@Test
	fun `tap during prewarm queues and provisions once the warm build finishes`() =
		runTest {
			prewarmGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()

			manager.prewarm()
			advanceUntilIdle()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The tap does not race the warm Gradle build.
			assertThat(provisionCount).isEqualTo(0)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Prewarming(tapQueued = true))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Provisioning)

			prewarmGate!!.complete(Unit)
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `prewarm failure is silent and leaves the session Idle`() =
		runTest {
			prewarmError = RuntimeException("setup build failed")
			val manager = createManager()

			manager.prewarm()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			// The user never asked for the warm build; no error surfaces.
			assertThat(userMessages).isEmpty()
		}

	@Test
	fun `prewarm while a session is live does not disturb it`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.prewarm()
			advanceUntilIdle()

			assertThat(prewarmCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `standard run completion re-seeds - the next save recompiles everything`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			// Deferred re-seed: no build behind the user's back, state unchanged.
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			manager.save(sourceFile)
			advanceUntilIdle()

			// The save after the hand-back recompiles from current disk, never stale.
			val request = executed.single()
			assertThat(request.changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(request.route).isEqualTo(BuildRoute.CodeAndResources)
		}

	@Test
	fun `standard run completion with clobbered setup artifacts forces a full rebaseline`() =
		runTest {
			provisionOutcome = {
				val base = defaultProvisionOutcome() as ProvisionOutcome.Success
				base.copy(
					setup =
						base.setup.copy(
							// Points at nothing on disk - as after an external clean.
							proxyClassesDir = File(projectRoot, "build/quickbuild/proxy-gone"),
						),
				)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			// EXTERNAL_FULL_BUILD routed through the invalidation machinery.
			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `standard run completion with all setup artifacts present re-seeds incrementally`() =
		runTest {
			val jar =
				File(projectRoot, "build/intermediates/r.jar").apply {
					parentFile!!.mkdirs()
					writeText("jar")
				}
			val proxyDir = File(projectRoot, "build/quickbuild/proxies").apply { mkdirs() }
			val manifest =
				File(projectRoot, "build/quickbuild/AndroidManifest.xml").apply {
					writeText("<manifest/>")
				}
			provisionOutcome = {
				val base = defaultProvisionOutcome() as ProvisionOutcome.Success
				base.copy(
					setup =
						base.setup.copy(
							classpath = listOf(jar),
							proxyClassesDir = proxyDir,
							transformedManifest = manifest,
						),
				)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(0)
			assertThat(executed.single().changes).isEqualTo(ChangedFiles.Unknown)
		}

	@Test
	fun `standard run completion with a missing classpath jar forces a full rebaseline`() =
		runTest {
			provisionOutcome = {
				val base = defaultProvisionOutcome() as ProvisionOutcome.Success
				base.copy(
					setup =
						base.setup.copy(
							classpath = listOf(File(projectRoot, "build/intermediates/r.jar")),
						),
				)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(1)
		}

	@Test
	fun `standard run completion without a session is a no-op`() =
		runTest {
			val manager = createManager()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(rebaselineCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
		}

	@Test
	fun `restartSession tears down a live session and a later tap re-provisions fresh`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)

			manager.restartSession()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden)
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(connections.expectedUid).isNull()
			assertThat(connections.expectedPackage).isNull()
			// The old watcher must not still be able to trigger a build post-restart.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(2)
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `restart during provisioning cancels the in-flight provision - no zombie session`() =
		runTest {
			provisionGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Provisioning)

			manager.restartSession()
			advanceUntilIdle()
			provisionGate!!.complete(Unit)
			advanceUntilIdle()

			// The cancelled provision never went live: no daemon, no watcher, still Idle.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(daemon.startConfigs).isEmpty()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()

			// The next tap provisions from scratch, with exactly one live watcher/daemon.
			provisionGate = null
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(provisionCount).isEqualTo(2)
			assertThat(daemon.startConfigs).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a provision that outlives the restart is discarded by the epoch guard`() =
		runTest {
			provisionGate = kotlinx.coroutines.CompletableDeferred()
			provisionSurvivesCancel = true
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Restart while provisioning; the provision ignores the cancel and still
			// produces a Success outcome - it must not resurrect a session behind Idle.
			manager.restartSession()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden)
			assertThat(daemon.startConfigs).isEmpty()
			assertThat(connections.expectedPackage).isNull()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()
		}

	@Test
	fun `restart during prewarm cancels the warm wait and the next tap provisions fresh`() =
		runTest {
			prewarmGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()

			manager.prewarm()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Prewarming())

			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `provisioning failure emits on the userMessages flow`() =
		runTest {
			provisionOutcome = { ProvisionOutcome.Failure("no build service") }
			val manager = createManager()
			val flowMessages = mutableListOf<String>()
			backgroundScope.launch { manager.userMessages.collect { flowMessages += it } }

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(flowMessages).containsExactly("no build service")
		}

	@Test
	fun `restartSession while idle is a no-op`() =
		runTest {
			val manager = createManager()

			manager.restartSession()
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
		}

	@Test
	fun `infrastructure failure with daemonDied routes to the Degraded flow, not BuildFailed`() =
		runTest {
			scriptedOutcomes += BuildOutcome.InfrastructureFailure("pipe broke", daemonDied = true)
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()

			// DaemonDied -> Degraded -> respawn -> Ready -> Unknown re-seed build succeeds.
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(executed.last().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}
}
