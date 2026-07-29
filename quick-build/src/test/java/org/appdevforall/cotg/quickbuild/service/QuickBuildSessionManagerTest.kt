package org.appdevforall.cotg.quickbuild.service

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
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
	private val historyStore = FakeQuickBuildHistoryStore()
	private val userMessages = mutableListOf<String>()

	/** Requests seen by the scripted executor, with per-request scripted outcomes. */
	private val executed = mutableListOf<BuildRequest>()

	/**
	 * Background seed builds ([BuildRoute.Seed]) recorded separately: they are a
	 * post-provisioning warm-up, not user work, so keeping them out of [executed]
	 * preserves every "the user's save produced exactly these builds" assertion.
	 */
	private val seeds = mutableListOf<BuildRequest>()

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

	/** Scripted outcomes for SEED builds only; empty = every seed succeeds unmoved. */
	private val seedOutcomes = ArrayDeque<BuildOutcome>()
	private var provisionCount = 0
	private var rebaselineCount = 0
	private var prewarmCount = 0
	private var provisionOutcome: (() -> ProvisionOutcome)? = null
	private var rebaselineOutcome: () -> RebaselineOutcome = { defaultRebaselineSuccess() }
	private var prewarmGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var prewarmError: Throwable? = null
	private var provisionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var provisionSurvivesCancel = false
	private var rebaselineGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

	/** Set to make the scripted executor await mid-build, so a test can observe Building. */
	private var executionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var seedGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

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
		private var onBatch: ((ChangedFiles.Known) -> Unit)? = null

		override fun start(onBatch: (ChangedFiles.Known) -> Unit) {
			this.onBatch = onBatch
		}

		override fun stop() {
			onBatch = null
		}

		/** Simulates a coalesced burst: modified/created paths plus deleted ones. */
		fun emit(
			modified: Set<File>,
			removed: Set<File> = emptySet(),
		) {
			val m = modified.filterTo(HashSet(), filter::isRelevant)
			val r = removed.filterTo(HashSet(), filter::isRelevant)
			if (m.isNotEmpty() || r.isNotEmpty()) onBatch?.invoke(ChangedFiles.Known(m, r))
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

	private fun TestScope.createManager(backgroundSeedEnabled: () -> Boolean = { true }): QuickBuildSessionManager {
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
					rebaselineGate?.await()
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
			historyStore = historyStore,
			dispatcher = StandardTestDispatcher(testScheduler),
			generationStoreFactory = { store },
			executorFactory = { setup, _, tracker ->
				factorySetups += setup
				object : QuickBuildExecutor {
					override suspend fun execute(request: BuildRequest): BuildOutcome {
						if (request.route is BuildRoute.Seed) {
							// Mirror the real executor's seed contract: compile-only,
							// nothing deployed, generation unmoved, scripted outcomes
							// (which script USER builds) untouched.
							seeds += request
							seedGate?.await()
							return seedOutcomes.removeFirstOrNull()
								?: BuildOutcome.Success(tracker.current, 5)
						}
						executed += request
						executionGate?.await()
						return scriptedOutcomes.removeFirstOrNull()
							?: BuildOutcome.Success(tracker.next(), 5)
					}
				}
			},
			onUserMessage = { userMessages += it },
			watcherFactory = { _, _, filter, _ -> FakeWatcher(filter).also { watcher = it } },
			metrics = recordingMetrics,
			backgroundSeedEnabled = backgroundSeedEnabled,
		)
	}

	/** Simulate an on-device file change (from any source) landing on the watcher. */
	private fun QuickBuildSessionManager.save(vararg files: File) {
		watcher?.emit(files.toSet())
	}

	/** Simulate a standalone deletion the watcher's delete path detected (poll/inotify). */
	private fun QuickBuildSessionManager.deleted(vararg files: File) {
		watcher?.emit(modified = emptySet(), removed = files.toSet())
	}

	/**
	 * Simulate a rename/move within `src/` as the watcher observes it: the destination
	 * [to] arrives as a create/modify (MOVED_TO) and the source [from] as a deletion
	 * (MOVED_FROM), coalesced into ONE burst (see AndroidProjectWatcher's DELETE_MASK).
	 */
	private fun QuickBuildSessionManager.renamed(
		from: File,
		to: File,
	) {
		watcher?.emit(modified = setOf(to), removed = setOf(from))
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
	fun `provisioning fires exactly one background seed that ends back in Ready`() =
		runTest {
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val seed = seeds.single()
			assertThat(seed.route).isEqualTo(BuildRoute.Seed)
			assertThat(seed.changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(seed.forced).isFalse()
			// The seed deployed nothing: generation unmoved, no Deployed state lingering.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))
			// User-build bookkeeping untouched.
			assertThat(executed).isEmpty()
		}

	@Test
	fun `bench seam off - provisioning lands Ready with no seed, and a later save still builds`() =
		runTest {
			val manager = createManager(backgroundSeedEnabled = { false })

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// No seed was requested; the session simply stays Ready at the base generation.
			assertThat(seeds).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			// The seam only skips the warm-up: real user work is untouched.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	@Test
	fun `a save during the seed queues and builds right after it - never lost, never overlapped`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			seedGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(seeds).hasSize(1)
			assertThat(executed).isEmpty()

			manager.save(File(projectRoot, "app/src/main/java/com/example/A.kt"))
			advanceUntilIdle()
			// Single-flight: the save waits for the in-flight seed.
			assertThat(executed).isEmpty()

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	// Review finding (2026-07-26 #3): the seed compiles what the test app already runs
	// and deploys nothing - it must not present as a blocking Building for its whole
	// 12-50s window.
	@Test
	fun `the background seed does not present as Building - status stays up to date`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			seedGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The seed is in flight (gated), yet the surface reads up to date.
			assertThat(seeds).hasSize(1)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Building(0, seeding = true))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// Review finding (2026-07-26 #3): a forced-redeploy tap during the seed must not
	// vanish - the seed deploys nothing, so nothing else would satisfy it.
	@Test
	fun `a tap during the seed queues and forces a build right after it`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			seedGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(seeds).hasSize(1)

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			// Single-flight: the tap waits for the in-flight seed.
			assertThat(executed).isEmpty()

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(executed.single().forced).isTrue()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	// Review finding (2026-07-26 #1): a crash of the running generation during the seed
	// window surfaces like any other test-app crash instead of being swallowed by the
	// seed's silent SeedFinished -> Ready path.
	@Test
	fun `a test-app crash during the seed surfaces as a session failure`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			seedGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(seeds).hasSize(1)

			connections.report(TargetReport.Crashed(0, "NPE in onCreate"))
			advanceUntilIdle()
			// Surfaced immediately, not deferred to the end of the seed window.
			assertThat(manager.status.value)
				.isEqualTo(
					QuickBuildStatus.Failed(0, SessionFailure.TestAppCrash("NPE in onCreate")),
				)

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Ready(
						0,
						lastFailure = SessionFailure.TestAppCrash("NPE in onCreate"),
					),
				)
		}

	// Review gap (2026-07-26 #69): the daemon dying DURING the seed must surface as
	// Degraded and recover through the normal respawn, never end in SeedFinished's
	// silent "up to date" over a dead daemon.
	@Test
	fun `a daemon death during the seed degrades, respawns and re-seeds the fresh daemon`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			seedGate = gate
			seedOutcomes +=
				BuildOutcome.InfrastructureFailure("daemon connection lost", daemonDied = true)

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(seeds).hasSize(1)

			// Hold the respawn's start so the honest Degraded window is observable.
			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))

			respawnGate.complete(Unit)
			advanceUntilIdle()
			// The fresh daemon re-warmed via a second deploy-nothing seed; nothing
			// user-visible happened: no user build, no deploy, generation unmoved.
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(seeds).hasSize(2)
			assertThat(executed).isEmpty()
			assertThat(deploy.calls).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
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
	fun `a build start before any deploy this session tells the test app its own connect-time generation`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()

			val building =
				deploy.statusCalls.single {
					JsonParser
						.parseString(it)
						.asJsonObject
						.get("kind")
						.asString == "building"
				}
			assertThat(
				JsonParser
					.parseString(building)
					.asJsonObject
					.get("runningGeneration")
					.asString,
			).isEqualTo("0")
		}

	@Test
	fun `a build start after a deploy uses the session's own tally, not a stale connect-time value`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			// First build: nothing deployed yet this session and no test app connected in
			// this test, so there is nothing truthful to say - no "building" message.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(deploy.statusCalls).isEmpty()

			// Second build: the session's own tally (gen 1, from the first build) is now
			// authoritative, even though no reconnect ever refreshed a connected target.
			manager.save(sourceFile)
			advanceUntilIdle()

			val building =
				deploy.statusCalls.single {
					JsonParser
						.parseString(it)
						.asJsonObject
						.get("kind")
						.asString == "building"
				}
			assertThat(
				JsonParser
					.parseString(building)
					.asJsonObject
					.get("runningGeneration")
					.asString,
			).isEqualTo("1")
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
	fun `a vanished external-tool temp file is dropped without poisoning the batch to a rebaseline`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Simulates `sed -i` rewriting sourceFile: a sibling temp file with no
			// dot-prefix or recognizable suffix (WatchFilter can't name-filter it) is
			// created and then renamed away before the batch settles, so it must not
			// exist on disk by the time onWatcherBatch classifies the batch.
			val vanishedTemp = File(projectRoot, "app/src/main/java/com/example/sedAbC123")
			sourceFile.writeText("class Foo { fun bar() {} }")

			manager.save(vanishedTemp, sourceFile)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(0)
			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			assertThat((request.changes as ChangedFiles.Known).files).containsExactly(sourceFile)
		}

	@Test
	fun `a modify event whose target has since vanished is reclassified as a removal`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A modify/move event arrives for a tracked .kt that is gone by batch-settle
			// time (a git checkout MOVED_TO whose target was then dropped). It has a
			// recognized shape, so it is NOT dropped as noise; it is routed as a removal
			// (removed set), not compiled as a now-absent source.
			assertThat(sourceFile.delete()).isTrue()

			manager.save(sourceFile)
			advanceUntilIdle()

			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			val changes = request.changes as ChangedFiles.Known
			assertThat(changes.files).isEmpty()
			assertThat(changes.removed).containsExactly(sourceFile)
		}

	@Test
	fun `a standalone deletion of a tracked kt file routes CodeOnly through the removed set`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The Bug-12 gap: a `git pull`/branch-switch/`rm` deletes a tracked source with
			// NO accompanying create/modify, so it only reaches the pipeline via the
			// watcher's removed channel. It must fire an incremental CodeOnly build (its
			// outputs dropped + dependents recompiled), never linger until an unrelated edit.
			assertThat(sourceFile.delete()).isTrue()

			manager.deleted(sourceFile)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(0)
			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			val changes = request.changes as ChangedFiles.Known
			assertThat(changes.files).isEmpty()
			assertThat(changes.removed).containsExactly(sourceFile)
		}

	@Test
	fun `a deletion with no recognized shape is dropped as noise`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The delete detector can fire for an external tool's sibling temp
			// (`sedXXXXXX`, a `patch` dropping) it saw created-then-removed. With no
			// recognized project-file shape it is pure noise - dropped, no build.
			val vanishedTemp = File(projectRoot, "app/src/main/java/com/example/sedAbC123")

			manager.deleted(vanishedTemp)
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(rebaselineCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `deleting a gradle file routes to a rebaseline`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A removed build.gradle is a baseline-invalidating change (like a modified
			// one): it must force the honest full Gradle rebaseline, not a quick build.
			manager.deleted(gradleFile)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(executed).isEmpty()
		}

	@Test
	fun `a surviving unclassifiable file under src still forces the honest Gradle fallback`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A real (not vanished) java-resource the quick path can't package - existing
			// on disk at batch-settle time must not exempt a genuinely unsupported file
			// from the honest fallback (no over-correction from the vanished-file drop).
			val unsupported =
				File(projectRoot, "app/src/main/resources/config.properties").apply {
					parentFile!!.mkdirs()
					writeText("k=v")
				}

			manager.save(unsupported)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(executed).isEmpty()
		}

	@Test
	fun `a plain in-place kt modify still classifies as code only`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// CoGo's own editor writes in place (truncate + write) - the surviving file
			// never disappears, so the batch-settle existence check must not touch this
			// path at all.
			sourceFile.writeText("class Foo { fun bar() = 1 }")
			manager.save(sourceFile)
			advanceUntilIdle()

			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			assertThat((request.changes as ChangedFiles.Known).files).containsExactly(sourceFile)
		}

	@Test
	fun `a newly created source file routes CodeOnly through the modified set`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A brand-new .kt appearing under src/ - a plugin IdeFileService.writeFile of a
			// new file (audit rows 4, 7), a `git pull`/`checkout` CREATE (row 10), a Termux
			// `cp`/`mv` into src (rows 19, 20), or a file-manager New Class (row 24). All land
			// as a CREATE the watcher reports as a modified path that EXISTS at settle time.
			val created =
				File(projectRoot, "app/src/main/java/com/example/Bar.kt").apply {
					parentFile!!.mkdirs()
					writeText("class Bar")
				}

			manager.save(created)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(0)
			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			val changes = request.changes as ChangedFiles.Known
			assertThat(changes.files).containsExactly(created)
			assertThat(changes.removed).isEmpty()
		}

	@Test
	fun `a rename within src carries the new file modified and the old removed in one CodeOnly build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A file-manager rename `Foo.kt` -> `Bar.kt` or a move between src/ dirs (audit
			// rows 25, 26): MOVED_TO on the destination + MOVED_FROM on the source, coalesced
			// into one burst. The new file compiles and the old one feeds the removed-sources
			// slot (its stale .class dropped) - a single CodeOnly build, never a rebaseline.
			val renamedTo =
				File(projectRoot, "app/src/main/java/com/example/Bar.kt").apply {
					writeText("class Bar")
				}
			assertThat(sourceFile.delete()).isTrue()

			manager.renamed(from = sourceFile, to = renamedTo)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(0)
			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			val changes = request.changes as ChangedFiles.Known
			assertThat(changes.files).containsExactly(renamedTo)
			assertThat(changes.removed).containsExactly(sourceFile)
		}

	@Test
	fun `a standalone deletion of a resource routes ResourcesOnly through the removed set`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A deleted res/ file with no accompanying edit (a `git pull` that drops a layout,
			// a file-manager delete - audit row 27 for the resource case, Gap A). It reaches
			// the pipeline only via the removed channel and must relink the shrunk resource
			// set, never linger until an unrelated edit and never over-escalate to a rebuild.
			val layout = File(projectRoot, "app/src/main/res/layout/activity_dead.xml")

			manager.deleted(layout)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(0)
			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.ResourcesOnly)
			val changes = request.changes as ChangedFiles.Known
			assertThat(changes.files).isEmpty()
			assertThat(changes.removed).containsExactly(layout)
		}

	@Test
	fun `deleting the manifest routes to a rebaseline`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A deleted AndroidManifest.xml (a branch switch that drops it - audit rows 11,
			// 12 for the manifest case) is a baseline-invalidating change exactly like a
			// modified manifest: it must force the honest full Gradle rebaseline, not a quick
			// build off the removed set.
			val manifest = File(projectRoot, "app/src/main/AndroidManifest.xml")

			manager.deleted(manifest)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(executed).isEmpty()
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
	fun `a rebaseline tears the daemon down for the Gradle build and restarts it on the new config`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)

			manager.save(gradleFile)
			advanceUntilIdle()

			// Torn down at rebaseline start (the daemon's ~0.5GB must not coexist with
			// the Gradle build's peak on low-RAM devices), restarted on success against
			// the re-read setup - and left RUNNING for the session that continues.
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(daemon.isRunning).isTrue()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// Review gap (2026-07-26 #69): the test above reuses an identical setup/layout, so
	// restarting on the stale provisioning-time config would also pass it. Here the
	// rebaseline moves BOTH - the restarted daemon must reflect the new facts.
	@Test
	fun `the rebaseline's daemon restart uses the re-read setup and layout, not the provisioning-time config`() =
		runTest {
			// The gradle edit that forced the rebaseline added a dependency jar and
			// enabled Compose; the regenerated setup/layout carry both.
			val newJar = File(projectRoot, "libs/new-dep.jar")
			rebaselineOutcome = {
				val base = defaultRebaselineSuccess()
				base.copy(
					setup = base.setup.copy(composeEnabled = true),
					layout = DefaultQuickBuildProjectLayout(projectRoot, classpath = listOf(newJar)),
				)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs.single().classpath).isEmpty()
			assertThat(daemon.startConfigs.single().compilerPlugins).isEmpty()

			manager.save(gradleFile)
			advanceUntilIdle()

			// Restarted against the NEW config - otherwise every quick build after
			// the rebaseline compiles on the old classpath without the Compose plugin.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(daemon.startConfigs).hasSize(2)
			val restarted = daemon.startConfigs.last()
			assertThat(restarted.classpath).containsExactly(newJar)
			assertThat(restarted.compilerPlugins).isNotEmpty()
		}

	// Review finding (2026-07-26 #2): rebaseline calls daemon.shutdown() and can race an
	// in-flight respawn. The daemonEpoch guard must discard the superseded respawn.
	@Test
	fun `a respawn superseded by a completed rebaseline is discarded and leaves the new daemon alone`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Daemon dies; the auto-respawn parks inside daemon.start.
			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			daemon.die(exitCode = 137)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))
			assertThat(daemon.startConfigs).hasSize(2) // provision + parked respawn

			// A gradle edit lands while Degraded: the rebaseline tears the daemon down
			// and restarts it on the new config while the respawn is STILL in flight.
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(daemon.startConfigs).hasSize(3) // + the rebaseline's restart
			assertThat(daemon.isRunning).isTrue()
			val shutdownsBefore = daemon.shutdownCount
			val seedsBefore = seeds.size

			// The parked respawn finally completes - AFTER the rebaseline already owns a
			// fresh daemon. It must discard itself: no DaemonRespawned, no orchestrator
			// poke (a spurious seed), and no touching the rebaseline's NEW daemon.
			respawnGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.shutdownCount).isEqualTo(shutdownsBefore)
			assertThat(seeds).hasSize(seedsBefore)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a respawn completing mid-rebaseline stops its zombie daemon instead of racing the Gradle build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			daemon.die(exitCode = 137)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))

			// The rebaseline tears the daemon down, then parks inside its Gradle build.
			val rebGate = CompletableDeferred<Unit>()
			rebaselineGate = rebGate
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Provisioning)

			// The parked respawn completes while the Gradle build still runs: its daemon
			// must NOT coexist with the build (the shutdown above freed that memory on
			// purpose) - the discarded respawn stops the zombie it just started.
			respawnGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.shutdownCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Provisioning)

			// The rebaseline then finishes normally against its own fresh daemon.
			rebGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.startConfigs).hasSize(3)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a respawn superseded by a session restart is discarded and leaves the daemon down`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val seedsBefore = seeds.size

			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			daemon.die(exitCode = 137)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))

			// "Restart session" tears everything down while the respawn is in flight.
			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)

			// The parked respawn completes into a torn-down session: it must not
			// resurrect an orphan daemon, nor poke the dead session's orchestrator.
			respawnGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isFalse()
			assertThat(seeds).hasSize(seedsBefore)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
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
					// The rebaseline re-enters Ready via ProvisioningSucceeded, which fires
					// a fresh background seed: the full Gradle build may have moved inputs
					// (or respawned the daemon), so re-seeding the IC universe afterwards
					// is deliberate, and its metrics are visible like any build's.
					"started:Seed:0",
					"finished:Success",
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

	// Review gap (2026-07-26 #69): pin the failed rebaseline's DAEMON state and the
	// clean re-tap - the session must die clean, not linger wedged and daemon-less.
	@Test
	fun `a failed rebaseline leaves the daemon down and a tap re-provisions a fresh session`() =
		runTest {
			var failRebaseline = true
			rebaselineOutcome = {
				if (failRebaseline) {
					RebaselineOutcome.Failure("manifest does not build")
				} else {
					defaultRebaselineSuccess()
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			// The daemon was shut down for the Gradle build and there is no new
			// baseline to restart it against: it stays down, torn down with the session.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.startConfigs).hasSize(1)

			// The next tap re-provisions from scratch - not wedged.
			failRebaseline = false
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(provisionCount).isEqualTo(2)
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// Review finding (2026-07-26 #4): the Gradle rebaseline SUCCEEDED but the daemon
	// restart after it fails. Traced safe at review time but unpinned - the session
	// must tear down to Idle (never park daemon-less) and a tap must re-provision.
	@Test
	fun `a daemon restart failure after a successful rebaseline tears down to Idle and a tap re-provisions`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			daemon.startReply = DaemonReply.Failed("daemon JVM would not start")
			manager.save(gradleFile)
			advanceUntilIdle()

			// The rebaseline itself succeeded; only the restart failed. The failure
			// surfaces and the session dies clean instead of wedging half-alive.
			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
			assertThat(userMessages).contains("daemon JVM would not start")
			assertThat(daemon.isRunning).isFalse()

			// The next tap re-provisions from scratch and works again.
			daemon.startReply = DaemonReply.Ok(Unit)
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(provisionCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `an unconfirmed rebaseline install parks the session for retry instead of dying to Idle`() =
		runTest {
			// The multi-module verify's stranded-session failure: the rebaseline's Gradle
			// build succeeded but nobody tapped the reinstall dialog, so the installer
			// timed out. The session must stay recoverable, not drop to Idle.
			rebaselineOutcome = { RebaselineOutcome.InstallNotConfirmed("install was not confirmed") }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(1)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						0,
						awaitingRetry = true,
					),
				)
			// The user is told what happened and how to recover.
			assertThat(userMessages).contains("install was not confirmed. Tap Quick Build to retry.")
			// Parked, not torn down: the daemon stays down (it was shut down for the
			// Gradle build and there is no new baseline to restart it against yet).
			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.startConfigs).hasSize(1)
		}

	@Test
	fun `tapping Quick Build after an unconfirmed install retries the rebaseline and recovers`() =
		runTest {
			var confirmed = false
			rebaselineOutcome = {
				if (confirmed) {
					defaultRebaselineSuccess()
				} else {
					RebaselineOutcome.InstallNotConfirmed("install was not confirmed")
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(rebaselineCount).isEqualTo(1)

			// The user "confirms this time": the retried install goes through.
			confirmed = true
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			// The daemon restarted against the retried rebaseline's setup.
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.startConfigs).hasSize(2)
		}

	@Test
	fun `CoGo returning to the foreground after an unconfirmed install retries the rebaseline`() =
		runTest {
			// The backgrounded-CoGo case (corpus run 20260728T044815Z): the reinstall
			// timed out with NO dialog ever shown - Android does not deliver the
			// PENDING_USER_ACTION broadcast to a backgrounded app. The user's return to
			// CoGo must re-prompt on its own; they never saw anything to tap.
			var foregrounded = false
			rebaselineOutcome = {
				if (foregrounded) {
					defaultRebaselineSuccess()
				} else {
					RebaselineOutcome.InstallNotConfirmed("install was not confirmed")
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(rebaselineCount).isEqualTo(1)

			// The user comes back to CoGo: the editor's onResume forwards this.
			foregrounded = true
			manager.onHostForegrounded()
			advanceUntilIdle()

			assertThat(rebaselineCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(daemon.isRunning).isTrue()
		}

	@Test
	fun `onHostForegrounded is a no-op when the session is not parked`() =
		runTest {
			// Every editor onResume calls this; a live session must be untouched by it.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val before = manager.state.value
			assertThat(before).isEqualTo(QuickBuildSessionState.Ready(0))

			manager.onHostForegrounded()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(before)
			assertThat(rebaselineCount).isEqualTo(0)
		}

	@Test
	fun `saves while parked for retry accumulate for the retried rebaseline - no dead-daemon build`() =
		runTest {
			var confirmed = false
			rebaselineOutcome = {
				if (confirmed) defaultRebaselineSuccess() else RebaselineOutcome.InstallNotConfirmed("not confirmed")
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()

			// A source save while parked must NOT start a quick build: the daemon is
			// down, and the orchestrator still holds the rebaseline's absorbed batch.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()

			// The retried rebaseline absorbs the parked save (the file is on disk for
			// its Gradle build); the session comes back Ready without a quick build.
			confirmed = true
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
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
	fun `daemon death with nothing pending respawns and re-warms via a deploy-nothing seed`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			daemon.die(exitCode = 137)
			advanceUntilIdle()

			// Respawned: configure ran twice (provision + respawn)...
			assertThat(daemon.startConfigs).hasSize(2)
			// ...and with nothing pending the re-warm is a SEED (one per daemon life:
			// provisioning's + the respawn's) - no user build, no deploy, the test app
			// keeps running its current generation untouched.
			assertThat(executed).isEmpty()
			assertThat(seeds).hasSize(2)
			assertThat(seeds.last().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
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
	fun `prewarm runs even on a project that has never used Quick Build`() =
		runTest {
			// The old behaviour skipped the warm-up until Quick Build had been tapped once
			// on the project, which made the FIRST tap on every new project pay the whole
			// cold setup cost (~97 s on an a56 for a small app). If the feature is enabled,
			// warm it -- the flag is the only gate.
			historyStore.setHasUsedQuickBuild(false)
			val manager = createManager()

			manager.prewarm()
			advanceUntilIdle()

			assertThat(prewarmCount).isEqualTo(1)
		}

	@Test
	fun `tapping Quick Build still records that the project used it`() =
		runTest {
			historyStore.setHasUsedQuickBuild(false)
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(historyStore.hasUsedQuickBuild()).isTrue()
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

	@Test
	fun `onTrimMemory below RUNNING_CRITICAL is a no-op`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
			advanceUntilIdle()
			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(daemon.isRunning).isTrue()
		}

	@Test
	fun `onTrimMemory at RUNNING_CRITICAL tears down an idle daemon`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.isRunning).isTrue()

			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.isRunning).isFalse()
		}

	@Test
	fun `onTrimMemory at UI_HIDDEN keeps the daemon warm - backgrounding is mid-loop, not pressure`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The user switched to their running test app to look at the edit they just
			// made; they are coming back to edit again. UI_HIDDEN is not memory pressure.
			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(daemon.isRunning).isTrue()
		}

	@Test
	fun `onTrimMemory at BACKGROUND tears down - a cached-process trim is real pressure`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.isRunning).isFalse()
		}

	@Test
	fun `onTrimMemory is idempotent across repeated critical signals`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
			advanceUntilIdle()
			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
			advanceUntilIdle()

			// One real shutdown call; the second signal found the daemon already down.
			assertThat(daemon.shutdownCount).isEqualTo(1)
		}

	@Test
	fun `onTrimMemory with no live session is a safe no-op`() =
		runTest {
			val manager = createManager()

			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle)
		}

	@Test
	fun `onTrimMemory during a build defers the teardown until the build completes`() =
		runTest {
			executionGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isInstanceOf(QuickBuildSessionState.Building::class.java)

			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
			advanceUntilIdle()

			// Must not tear down mid-compile: the build is still in flight.
			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(daemon.isRunning).isTrue()

			executionGate!!.complete(Unit)
			advanceUntilIdle()

			// The deferred teardown applied the moment the build's own transition landed.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.isRunning).isFalse()
		}

	@Test
	fun `a Quick Build after a low-memory teardown re-warms the daemon and still succeeds`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isFalse()

			// The scripted executor doesn't know the daemon died; script what the REAL
			// executor reports for a torn-down daemon (QuickBuildExecutorImpl.compileAndDex
			// maps DaemonReply.Failed(daemonDied=true) to exactly this outcome).
			scriptedOutcomes += BuildOutcome.InfrastructureFailure("daemon not running", daemonDied = true)

			manager.save(sourceFile)
			advanceUntilIdle()

			// DaemonDied -> Degraded -> auto respawn -> Ready -> Unknown re-seed build
			// succeeds - "slower, not broken": no user retap needed.
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(executed.last().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}
}
