package org.appdevforall.cotg.quickbuild.service.session

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildNotice
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.session.SessionReducer
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.watch.WatchFilter
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.FakePaths
import org.appdevforall.cotg.quickbuild.service.FakeQuickBuildHistoryStore
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.deploy.ConnectedTarget
import org.appdevforall.cotg.quickbuild.service.deploy.DeployResult
import org.appdevforall.cotg.quickbuild.service.deploy.ProxyAppConnections
import org.appdevforall.cotg.quickbuild.service.deploy.RetainedPayloadStore
import org.appdevforall.cotg.quickbuild.service.deploy.TargetReport
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppRebuildOutcome
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.telemetry.report
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class QuickBuildSessionManagerTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy =
		FakeDeploy().apply {
			// The rebaseline relaunch awaits the relaunched app's reconnect; model an app
			// that comes back at the baseline stamp, so every successful rebaseline in these
			// tests books exactly ONE launch (no swallowed-start retry).
			reconnectGeneration = { 0L }
		}
	private val connections = ProxyAppConnections()
	private val store = MemoryGenerationStore()
	private val historyStore = FakeQuickBuildHistoryStore()
	private val userMessages = mutableListOf<QuickBuildMessage>()

	/** Requests seen by the scripted executor, with per-request scripted outcomes. */
	private val executed = mutableListOf<BuildRequest>()

	/**
	 * Background warm-compile builds ([BuildRoute.WarmCompile]) recorded separately: they are a
	 * post-provisioning warm-up, not user work, so keeping them out of [executed]
	 * preserves every "the user's save produced exactly these builds" assertion.
	 */
	private val warmCompiles = mutableListOf<BuildRequest>()

	/** ProxyAppInfo of every executor the manager built (provision + each proxy app rebuild). */
	private val factoryProxyApps = mutableListOf<ProxyAppInfo>()

	/** Flat trace of metrics-sink calls, e.g. "started:CodeOnly:1", "proxyAppRebuild:true". */
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

			override fun onProxyAppRebuild(
				isSuccess: Boolean,
				durationMillis: Long,
				relaunchOk: Boolean,
				toRunningMillis: Long?,
			) {
				record { "proxyAppRebuild:$isSuccess" }
			}

			private fun record(event: () -> String) {
				if (metricsThrow) error("metrics sink boom")
				metricsEvents += event()
			}
		}
	private val scriptedOutcomes = ArrayDeque<BuildOutcome>()

	/** Scripted outcomes for WARM-COMPILE builds only; empty = every warm compile succeeds unmoved. */
	private val warmCompileOutcomes = ArrayDeque<BuildOutcome>()
	private var provisionCount = 0
	private var proxyAppRebuildCount = 0
	private var prebuildCount = 0
	private var provisionOutcome: (() -> ProvisionOutcome)? = null
	private var proxyAppRebuildOutcome: () -> ProxyAppRebuildOutcome = { defaultProxyAppRebuildSuccess() }
	private var prebuildGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var prebuildError: Throwable? = null
	private var provisionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var provisionSurvivesCancel = false
	private var proxyAppRebuildGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

	/**
	 * Makes a gated proxy app rebuild finish its wait even after the session teardown
	 * cancelled it - the Gradle build runs out of process, so a cancel cannot un-run it.
	 * Only the epoch guard can discard the outcome it then produces.
	 */
	private var proxyAppRebuildSurvivesCancel = false

	/**
	 * When set, every executorFactory call throws it. Stands in for the real factory's
	 * checkNotNull(entryActivity) during a rebuild's re-baseline (the rebuild contract
	 * does not guarantee it non-null).
	 */
	private var executorFactoryError: (() -> Throwable)? = null

	/** Set to make the scripted executor await mid-build, so a test can observe Building. */
	private var executionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
	private var warmCompileGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

	/** Captures the watcher the manager builds so a test can push change batches. */
	private var watcher: FakeWatcher? = null

	/**
	 * Every request to bring the proxy app to the foreground, as (package, launcherActivity).
	 * Behaviours 2/3/4 are exactly "is this list empty, and when did it grow", so it is the
	 * assertion surface for all three.
	 */
	private val launches = mutableListOf<Pair<String, String?>>()

	/** What the launcher answers; false stands in for a refused foreground request. */
	private var launchResult = true

	/**
	 * Wall-clock stand-in for tests that age the deferred foreground ask; only read when
	 * [createManager] is given `nowMillis = { fakeNowMillis }`.
	 */
	private var fakeNowMillis = 0L

	/** How many times a stop reached the real Gradle proxy-app-build cancellation. */
	private var proxyAppBuildCancelCount = 0

	/** What the Gradle cancellation answers; false means the build had already finished. */
	private var proxyAppBuildCancelResult = true

	/**
	 * Stands in for [org.appdevforall.cotg.quickbuild.data.AndroidProjectWatcher]: mirrors its two observable behaviours -
	 * it only forwards after [start] (a change before a live session is dropped), and it
	 * applies the same [WatchFilter] so irrelevant paths (build intermediates) are ignored.
	 */
	private class FakeWatcher(
		private val filter: WatchFilter,
	) : ProjectWatcher {
		private var onBatch: ((ChangedFiles.Known) -> Unit)? = null

		/** Survives [stop]; see [emitRacingStop]. */
		private var lastOnBatch: ((ChangedFiles.Known) -> Unit)? = null

		override fun start(onBatch: (ChangedFiles.Known) -> Unit) {
			this.onBatch = onBatch
			this.lastOnBatch = onBatch
		}

		override fun stop() {
			onBatch = null
		}

		/**
		 * A batch the watcher thread was already delivering when [stop] landed: inotify
		 * cannot unwind a callback that is mid-flight, so the manager still sees it.
		 */
		fun emitRacingStop(modified: Set<File>) {
			val m = modified.filterTo(HashSet(), filter::isRelevant)
			if (m.isNotEmpty()) lastOnBatch?.invoke(ChangedFiles.Known(m, emptySet()))
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

	private fun defaultProvisionOutcome(variantName: String? = null): ProvisionOutcome =
		ProvisionOutcome.Success(
			proxyApp =
				ProxyAppInfo(
					proxyAppPackage = "com.example.quickbuild",
					entryActivity = "com.example.MainActivity",
					apk = File(projectRoot, "proxy-app.apk"),
					classpath = emptyList(),
					proxyClassesDir = null,
					transformedManifest = null,
				),
			proxyAppUid = 10123,
			layout = QuickBuildProjectLayout(projectRoot),
			variantName = variantName,
		)

	private fun defaultProxyAppRebuildSuccess(): ProxyAppRebuildOutcome.Success {
		val provision = defaultProvisionOutcome() as ProvisionOutcome.Success
		return ProxyAppRebuildOutcome.Success(proxyApp = provision.proxyApp, layout = provision.layout)
	}

	/**
	 * @param collectUserMessages whether to attach the shared [userMessages] collector. Pass false
	 *   to test what a message raised with NOBODY collecting does - the queue is single-consumer,
	 *   so the shared collector would take the message before the test's own could.
	 */
	private fun TestScope.createManager(
		warmCompileEnabled: () -> Boolean = { true },
		scratch: QuickBuildScratch = QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot),
		nowMillis: () -> Long = System::currentTimeMillis,
		collectUserMessages: Boolean = true,
	): QuickBuildSessionManager {
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

				override suspend fun rebuildProxyApp(): ProxyAppRebuildOutcome {
					proxyAppRebuildCount++
					proxyAppRebuildGate?.let { gate ->
						if (proxyAppRebuildSurvivesCancel) {
							kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
						} else {
							gate.await()
						}
					}
					return proxyAppRebuildOutcome()
				}

				override suspend fun prebuildProxyApp() {
					prebuildCount++
					prebuildGate?.await()
					prebuildError?.let { throw it }
				}

				override fun cancelProxyAppBuild(): Boolean {
					proxyAppBuildCancelCount++
					return proxyAppBuildCancelResult
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
			executorFactory = { proxyApp, _, tracker ->
				executorFactoryError?.let { throw it() }
				factoryProxyApps += proxyApp
				object : LiveReloadExecutor {
					override suspend fun execute(request: BuildRequest): BuildOutcome {
						if (request.route is BuildRoute.WarmCompile) {
							// Mirror the real executor's warm-compile contract: compile-only,
							// nothing deployed, generation unmoved, scripted outcomes
							// (which script USER builds) untouched.
							warmCompiles += request
							warmCompileGate?.await()
							return warmCompileOutcomes.removeFirstOrNull()
								?: BuildOutcome.Success(tracker.current, 5)
						}
						executed += request
						executionGate?.await()
						return scriptedOutcomes.removeFirstOrNull()
							?: BuildOutcome.Success(tracker.next(), 5)
					}
				}
			},
			watcherFactory = { _, _, filter, _ -> FakeWatcher(filter).also { watcher = it } },
			metrics = recordingMetrics,
			warmCompileEnabled = warmCompileEnabled,
			nowMillis = nowMillis,
			launcher =
				ProxyAppLauncher { packageName, activityClass ->
					launches += packageName to activityClass
					launchResult
				},
			scratch = scratch,
		).also { manager ->
			// Same hazard [recordNotices] documents, and the same reason for Unconfined.
			if (collectUserMessages) {
				backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
					manager.userMessages.collect { userMessages += it }
				}
			}
		}
	}

	/**
	 * Records the neutral notice flow for the whole test; see [QuickBuildNotice].
	 *
	 * ONE recorder per test: [QuickBuildSessionManager.notices] is a single-consumer queue, so a
	 * second collector would steal notices from this one.
	 *
	 * The collector MUST run on an [UnconfinedTestDispatcher]: on a StandardTestDispatcher the
	 * resumed collector is a background task that [advanceUntilIdle] considers idle work - once
	 * nothing else is queued it returns without ever running it, so a notice that really was
	 * raised reads as "no notice". Unconfined resumes the collector inside the sender's own call
	 * stack instead.
	 */
	private fun TestScope.recordNotices(manager: QuickBuildSessionManager): List<QuickBuildNotice> {
		val seen = mutableListOf<QuickBuildNotice>()
		backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
			manager.notices.collect { seen += it }
		}
		return seen
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
	fun `provisioning fires exactly one background warm compile that ends back in Ready`() =
		runTest {
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val warmCompile = warmCompiles.single()
			assertThat(warmCompile.route).isEqualTo(BuildRoute.WarmCompile)
			assertThat(warmCompile.changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(warmCompile.forced).isFalse()
			// The warm compile deployed nothing: generation unmoved, no Deployed state lingering.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))
			// User-build bookkeeping untouched.
			assertThat(executed).isEmpty()
		}

	@Test
	fun `bench seam off - provisioning lands Ready with no warm compile, and a later save still builds`() =
		runTest {
			val manager = createManager(warmCompileEnabled = { false })

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// No warm compile was requested; the session simply stays Ready at the base generation.
			assertThat(warmCompiles).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			// The seam only skips the warm-up: real user work is untouched.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	@Test
	fun `a save during the warm compile queues and builds right after it - never lost, never overlapped`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(warmCompiles).hasSize(1)
			assertThat(executed).isEmpty()

			manager.save(File(projectRoot, "app/src/main/java/com/example/A.kt"))
			advanceUntilIdle()
			// Single-flight: the save waits for the in-flight warm compile.
			assertThat(executed).isEmpty()

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	// The warm compile compiles what the proxy app already runs
	// and deploys nothing - it must not present as a blocking Building for its whole
	// 12-50s window.
	@Test
	fun `the background warm compile does not present as Building - status stays up to date`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The warm compile is in flight (gated), yet the surface reads up to date.
			assertThat(warmCompiles).hasSize(1)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Building(0, warmingCompiler = true))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// A clean tap during the warm compile must not vanish - the warm compile deploys nothing,
	// so nothing else would satisfy it - but the app is current, so it is answered by the
	// switch alone: no forced build queues behind the warm compile.
	@Test
	fun `a clean tap during the warm compile switches without queueing a forced build`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(warmCompiles).hasSize(1)
			val launchesBefore = launches.size

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			// Answered immediately, mid-warm-compile: the deployed app is current.
			assertThat(launches).hasSize(launchesBefore + 1)
			assertThat(executed).isEmpty()

			gate.complete(Unit)
			advanceUntilIdle()
			// And no build ran for the tap once the warm compile finished, either.
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	// A crash of the running generation during the warm compile
	// window surfaces like any other proxy-app crash instead of being swallowed by the
	// warm compile's silent WarmCompileFinished -> Ready path.
	@Test
	fun `a proxy-app crash during the warm compile surfaces as a session failure`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(warmCompiles).hasSize(1)

			connections.report(TargetReport.Crashed(0, "NPE in onCreate"))
			advanceUntilIdle()
			// Surfaced immediately, not deferred to the end of the warm-compile window.
			assertThat(manager.status.value)
				.isEqualTo(
					QuickBuildStatus.Failed(0, SessionFailure.ProxyAppCrash("NPE in onCreate")),
				)

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Ready(
						0,
						lastFailure = SessionFailure.ProxyAppCrash("NPE in onCreate"),
					),
				)
		}

	// Review gap (2026-07-26 #69): the daemon dying DURING the warm compile must surface as
	// Degraded and recover through the normal respawn, never end in WarmCompileFinished's
	// silent "up to date" over a dead daemon.
	@Test
	fun `a daemon death during the warm compile degrades, respawns and re-seeds the fresh daemon`() =
		runTest {
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate
			warmCompileOutcomes +=
				BuildOutcome.InfrastructureFailure("daemon connection lost", daemonDied = true)

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(warmCompiles).hasSize(1)

			// Hold the respawn's start so the honest Degraded window is observable.
			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))

			respawnGate.complete(Unit)
			advanceUntilIdle()
			// The fresh daemon re-warmed via a second deploy-nothing warm compile; nothing
			// user-visible happened: no user build, no deploy, generation unmoved.
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(warmCompiles).hasSize(2)
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
				default.copy(proxyApp = default.proxyApp.copy(composeEnabled = true))
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
			provisionOutcome = { ProvisionOutcome.Failure(QuickBuildMessage.Literal("no build service")) }
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The failed start parks Idle with the flag, so the bolt keeps the error tone (Q8).
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
			assertThat(userMessages).containsExactly(QuickBuildMessage.Literal("no build service"))
		}

	@Test
	fun `a save after a failed start clears the error tone and starts nothing`() =
		runTest {
			provisionOutcome = { ProvisionOutcome.Failure(QuickBuildMessage.Literal("no build service")) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden(lastStartFailed = true))
			val provisionsAfterFailure = provisionCount

			manager.onFileSaved()
			advanceUntilIdle()

			// The tone is cleared, and the save did NOT retry the start - a retry stays a tap.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden())
			assertThat(provisionCount).isEqualTo(provisionsAfterFailure)
			assertThat(daemon.startConfigs).isEmpty()
		}

	@Test
	fun `a tap after a failed start provisions again`() =
		runTest {
			var failFirst = true
			provisionOutcome = {
				if (failFirst) {
					failFirst = false
					ProvisionOutcome.Failure(QuickBuildMessage.Literal("no build service"))
				} else {
					defaultProvisionOutcome()
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Ordinary progression: the retry provisioned and the session is live, tone READY.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// ADFA-4930: intermediates live on app-private storage, keyed per project, guarded
	// by a free-space floor, removed on teardown, swept at manager start.

	@Test
	fun `a full private volume fails fast with the disk message - before the proxy app build`() =
		runTest {
			val scratchRoot = FakePaths(projectRoot).projectScratchRoot
			val manager =
				createManager(scratch = QuickBuildScratch(scratchRoot, minFreeBytes = Long.MAX_VALUE))

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Failed BEFORE the expensive Gradle proxy app build and before any daemon spawn.
			assertThat(provisionCount).isEqualTo(0)
			assertThat(daemon.startConfigs).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
			assertThat(userMessages.single())
				.isInstanceOf(QuickBuildMessage.NotEnoughStorage::class.java)
		}

	@Test
	fun `the daemon out dir lands under the private scratch root, not the project`() =
		runTest {
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val scratchRoot = FakePaths(projectRoot).projectScratchRoot
			val outDir = daemon.startConfigs.single().outDir
			assertThat(outDir.path).startsWith(scratchRoot.path)
			assertThat(outDir.path).doesNotContain(".androidide")
			// The tree provisioning prepared actually exists, on the private side.
			assertThat(QuickBuildScratch(scratchRoot).treeFor(projectRoot).isDirectory).isTrue()
		}

	@Test
	fun `session teardown removes the project's scratch tree`() =
		runTest {
			val manager = createManager()
			val tree = QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot).treeFor(projectRoot)

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(tree.isDirectory).isTrue()

			manager.restartSession()
			advanceUntilIdle()

			assertThat(tree.exists()).isFalse()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `manager start sweeps a dead session's scratch tree before anything is live`() =
		runTest {
			val scratchRoot = FakePaths(projectRoot).projectScratchRoot
			val stale =
				File(scratchRoot, "dead-project-0123456789abcdef").apply {
					File(this, "out").mkdirs()
				}

			val manager = createManager()
			advanceUntilIdle()
			assertThat(stale.exists()).isFalse()

			// The sweep is strictly ordered before any tap: a session provisioned after
			// it keeps its (new) tree.
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(QuickBuildScratch(scratchRoot).treeFor(projectRoot).isDirectory).isTrue()
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
	fun `a build start before any deploy this session tells the proxy app its own connect-time generation`() =
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
			// First build: the session already knows the provisioned baseline generation
			// (adopted from the provision's stamp; 0 for this unstamped fake), so even with
			// no proxy app connected the "building" push names it.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			val runningGenerations =
				deploy.statusCalls
					.map { JsonParser.parseString(it).asJsonObject }
					.filter { it.get("kind").asString == "building" }
					.map { it.get("runningGeneration").asString }
			assertThat(runningGenerations).containsExactly("0")

			// Second build: the session's own tally (gen 1, from the first build) is now
			// authoritative, even though no reconnect ever refreshed a connected target.
			manager.save(sourceFile)
			advanceUntilIdle()

			val building =
				deploy.statusCalls
					.map { JsonParser.parseString(it).asJsonObject }
					.last { it.get("kind").asString == "building" }
			assertThat(building.get("runningGeneration").asString).isEqualTo("1")
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
	fun `a vanished external-tool temp file is dropped without poisoning the batch to a proxy app rebuild`() =
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

			assertThat(proxyAppRebuildCount).isEqualTo(0)
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

			assertThat(proxyAppRebuildCount).isEqualTo(0)
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
			assertThat(proxyAppRebuildCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `deleting a gradle file routes to a proxy app rebuild`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A removed build.gradle is a baseline-invalidating change (like a modified
			// one): it must force the honest full Gradle proxy app rebuild, not a quick build.
			manager.deleted(gradleFile)
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(executed).isEmpty()
		}

	@Test
	fun `a surviving unclassifiable file under src still forces the honest Gradle fallback`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A real (not vanished) java-resource the live reload path can't package - existing
			// on disk at batch-settle time must not exempt a genuinely unsupported file
			// from the honest fallback (no over-correction from the vanished-file drop).
			val unsupported =
				File(projectRoot, "app/src/main/resources/config.properties").apply {
					parentFile!!.mkdirs()
					writeText("k=v")
				}

			manager.save(unsupported)
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(executed).isEmpty()
		}

	/** A file in a source set the app variant does not include, created on disk like a real save. */
	private fun sourceIn(
		sourceSet: String,
		name: String = "FooTest.kt",
	): File =
		File(projectRoot, "app/src/$sourceSet/java/com/example/$name").apply {
			parentFile!!.mkdirs()
			writeText("class Foo")
		}

	@Test
	fun `a test source save runs no build at all, and explains itself exactly once`() =
		runTest {
			val manager = createManager()
			val notices = recordNotices(manager)
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Nothing under src/test is in the variant Quick Build deploys, so neither a quick
			// build nor the honest Gradle fallback can carry it. A full rebuild here would cost
			// the user ~97 s to produce an app that cannot differ.
			val unitTest = sourceIn("test")
			manager.save(unitTest)
			advanceUntilIdle()
			manager.save(unitTest)
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(proxyAppRebuildCount).isEqualTo(0)
			// Once, not once per save: a user editing tests saves constantly, and repeating it
			// would bury the notices that report something happening.
			assertThat(notices).containsExactly(QuickBuildNotice.TEST_SOURCE_IGNORED)
		}

	@Test
	fun `an instrumentation test and a testFixtures save are ignored the same way`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceIn("androidTest"))
			advanceUntilIdle()
			manager.save(sourceIn("testFixtures", name = "Fixtures.kt"))
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(proxyAppRebuildCount).isEqualTo(0)
		}

	@Test
	fun `a save-all writing a test beside a main source still builds the main one`() =
		runTest {
			val manager = createManager()
			val notices = recordNotices(manager)
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The shape a save-all really produces. Dropping the whole batch would strand the
			// edit the user can actually see in their running app.
			manager.save(sourceFile, sourceIn("test"))
			advanceUntilIdle()

			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			assertThat((request.changes as ChangedFiles.Known).files).containsExactly(sourceFile)
			assertThat(notices).containsExactly(QuickBuildNotice.TEST_SOURCE_IGNORED)
		}

	@Test
	fun `a debug source set still forces the honest Gradle fallback - it ships in the variant`() =
		runTest {
			val manager = createManager()
			val notices = recordNotices(manager)
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The precision half of this behaviour. src/debug IS compiled into the app the user
			// runs, so ignoring it would leave the running app silently missing their edit -
			// the quick path cannot compile it, which is what the full build is for.
			manager.save(sourceIn("debug", name = "Debug.kt"))
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(executed).isEmpty()
			assertThat(notices).isEmpty()
		}

	@Test
	fun `a deleted test file triggers no build either`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Deletions are classified by the same path shape as modifications, and removing a
			// test deploys no more than saving one.
			val unitTest = sourceIn("test")
			assertThat(unitTest.delete()).isTrue()

			manager.deleted(unitTest)
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(proxyAppRebuildCount).isEqualTo(0)
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

			assertThat(proxyAppRebuildCount).isEqualTo(0)
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
			// slot (its stale .class dropped) - a single CodeOnly build, never a proxy app rebuild.
			val renamedTo =
				File(projectRoot, "app/src/main/java/com/example/Bar.kt").apply {
					writeText("class Bar")
				}
			assertThat(sourceFile.delete()).isTrue()

			manager.renamed(from = sourceFile, to = renamedTo)
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(0)
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

			assertThat(proxyAppRebuildCount).isEqualTo(0)
			val request = executed.single()
			assertThat(request.route).isEqualTo(BuildRoute.ResourcesOnly)
			val changes = request.changes as ChangedFiles.Known
			assertThat(changes.files).isEmpty()
			assertThat(changes.removed).containsExactly(layout)
		}

	@Test
	fun `deleting the manifest routes to a proxy app rebuild`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// A deleted AndroidManifest.xml (a branch switch that drops it - audit rows 11,
			// 12 for the manifest case) is a baseline-invalidating change exactly like a
			// modified manifest: it must force the honest full Gradle proxy app rebuild, not a quick
			// build off the removed set.
			val manifest = File(projectRoot, "app/src/main/AndroidManifest.xml")

			manager.deleted(manifest)
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(executed).isEmpty()
		}

	@Test
	fun `saves before any session are ignored`() =
		runTest {
			val manager = createManager()

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a gradle file save invalidates and runs the full proxy app rebuild round trip`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
			// Live reload path never ran for the gradle change.
			assertThat(executed).isEmpty()
			// Proxy app rebuild succeeded: back to Ready at the unchanged generation.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a proxy app rebuild tears the daemon down for the Gradle build and restarts it on the new config`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)

			manager.save(gradleFile)
			advanceUntilIdle()

			// Torn down at proxy app rebuild start (the daemon's ~0.5GB must not coexist with
			// the Gradle build's peak on low-RAM devices), restarted on success against
			// the re-read proxy app info - and left RUNNING for the session that continues.
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(daemon.isRunning).isTrue()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// Review gap (2026-07-26 #69): the test above reuses an identical proxy app info/layout, so
	// restarting on the stale provisioning-time config would also pass it. Here the
	// proxy app rebuild moves BOTH - the restarted daemon must reflect the new facts.
	@Test
	fun `the proxy app rebuild's daemon restart uses the re-read proxyApp and layout, not the provisioning-time config`() =
		runTest {
			// The gradle edit that forced the proxy app rebuild added a dependency jar and
			// enabled Compose; the regenerated proxy app info/layout carry both.
			val newJar = File(projectRoot, "libs/new-dep.jar")
			proxyAppRebuildOutcome = {
				val base = defaultProxyAppRebuildSuccess()
				base.copy(
					proxyApp = base.proxyApp.copy(composeEnabled = true),
					layout = QuickBuildProjectLayout(projectRoot, classpath = listOf(newJar)),
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
			// the proxy app rebuild compiles on the old classpath without the Compose plugin.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(daemon.startConfigs).hasSize(2)
			val restarted = daemon.startConfigs.last()
			assertThat(restarted.classpath).containsExactly(newJar)
			assertThat(restarted.compilerPlugins).isNotEmpty()
		}

	// The proxy app rebuild calls daemon.shutdown() and can race an
	// in-flight respawn. The daemonEpoch guard must discard the superseded respawn.
	@Test
	fun `a respawn superseded by a completed proxy app rebuild is discarded and leaves the new daemon alone`() =
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

			// A gradle edit lands while Degraded: the proxy app rebuild tears the daemon down
			// and restarts it on the new config while the respawn is STILL in flight.
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(daemon.startConfigs).hasSize(3) // + the proxy app rebuild's restart
			assertThat(daemon.isRunning).isTrue()
			val shutdownsBefore = daemon.shutdownCount
			val warmCompilesBefore = warmCompiles.size

			// The parked respawn finally completes - AFTER the proxy app rebuild already owns a
			// fresh daemon. It must discard itself: no DaemonRespawned, no orchestrator
			// poke (a spurious warm compile), and no touching the proxy app rebuild's NEW daemon.
			respawnGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.shutdownCount).isEqualTo(shutdownsBefore)
			assertThat(warmCompiles).hasSize(warmCompilesBefore)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a respawn completing mid-rebuild stops its zombie daemon instead of racing the Gradle build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			daemon.die(exitCode = 137)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))

			// The proxy app rebuild tears the daemon down, then parks inside its Gradle build.
			val rebGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = rebGate
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Provisioning(
						rebaselineReason = InvalidationReason.GRADLE_CONFIG_CHANGED,
					),
				)

			// The parked respawn completes while the Gradle build still runs: its daemon
			// must NOT coexist with the build (the shutdown above freed that memory on
			// purpose) - the discarded respawn stops the zombie it just started.
			respawnGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.shutdownCount).isEqualTo(2)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Provisioning(
						rebaselineReason = InvalidationReason.GRADLE_CONFIG_CHANGED,
					),
				)

			// The proxy app rebuild then finishes normally against its own fresh daemon.
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
			val warmCompilesBefore = warmCompiles.size

			val respawnGate = CompletableDeferred<Unit>()
			daemon.startGate = respawnGate
			daemon.die(exitCode = 137)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Degraded(0))

			// "Restart session" tears everything down while the respawn is in flight.
			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			// The parked respawn completes into a torn-down session: it must not
			// resurrect an orphan daemon, nor poke the dead session's orchestrator.
			respawnGate.complete(Unit)
			advanceUntilIdle()
			assertThat(daemon.isRunning).isFalse()
			assertThat(warmCompiles).hasSize(warmCompilesBefore)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a RequiresProxyAppRebuild outcome routes into the proxy app rebuild fallback`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			scriptedOutcomes +=
				BuildOutcome.RequiresProxyAppRebuild(
					InvalidationReason.OUTDATED_BASELINE,
					"baseline predates component metadata",
				)

			manager.save(sourceFile)
			advanceUntilIdle()

			// The quick build ran once, refused to deploy, and the session fell back to
			// the full proxy app rebuild (which absorbs the pending change) instead of failing.
			assertThat(executed).hasSize(1)
			assertThat(proxyAppRebuildCount).isEqualTo(1)
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
	fun `an invalidating save reports invalidation and proxy app rebuild metrics`() =
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
					"proxyAppRebuild:true",
					// The proxy app rebuild re-enters Ready via ProvisioningSucceeded, which fires
					// a fresh background warm compile: the full Gradle build may have moved inputs
					// (or respawned the daemon), so re-seeding the IC universe afterwards is
					// deliberate. The count is null, not 0: a warm compile's changed-set is
					// Unknown - it compiles every source, not zero files.
					"started:WarmCompile:null",
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
	fun `a failed proxy app rebuild surfaces the error and parks recoverable`() =
		runTest {
			proxyAppRebuildOutcome = { ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build")) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			// The user's build files do not build; the session is fine. Dying to Idle here is
			// what made a broken .gradle.kts terminal while a broken .kt stayed recoverable.
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.GRADLE_CONFIG_CHANGED,
						0,
						awaitingRetry = true,
					),
				)
			assertThat(userMessages).contains(QuickBuildMessage.Literal("manifest does not build"))
		}

	// Review gap (2026-07-26 #69): pin the failed proxy app rebuild's DAEMON state and the
	// recovery - the session must stay recoverable, not linger wedged and daemon-less.
	@Test
	fun `saving the fix after a failed proxy app rebuild recovers the session`() =
		runTest {
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			// Parked, not torn down: the daemon stays down (it was shut down for the Gradle
			// build and there is no new baseline to restart it against yet).
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.GRADLE_CONFIG_CHANGED,
						0,
						awaitingRetry = true,
					),
				)
			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.startConfigs).hasSize(1)

			// Saving the fix is the recovery gesture - no tap, no leaving the editor.
			failProxyAppRebuild = false
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(2)
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// The rebuild-Succeeded arm must build the new delegates BEFORE mutating
	// session.proxyApp/layout: a factory throw (checkNotNull(entryActivity)) after the
	// mutation escapes the session scope and crashes CoGo with the session half-updated.
	// Built first, the arm can dispatch the ordinary rebuild-failure path instead.
	@Test
	fun `a delegate factory throw during the rebuild's re-baseline dispatches the failure path instead of escaping`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(factoryProxyApps).hasSize(1)

			executorFactoryError = {
				IllegalStateException("Quick Build session started without an entry activity")
			}
			manager.save(gradleFile)
			advanceUntilIdle()

			// Old baseline stayed intact (no second executor was ever installed) and the
			// failure took the same path as any other failed rebuild: torn down clean to
			// Idle with the error surfaced, never a crash or a wedged Provisioning.
			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(factoryProxyApps).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
			assertThat(userMessages).contains(QuickBuildMessage.Literal("Quick Build session started without an entry activity"))

			// The next tap re-provisions from scratch - not wedged.
			executorFactoryError = null
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(provisionCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	// The Gradle proxy app rebuild SUCCEEDED but the daemon restart after it fails: the
	// session must tear down to Idle (never park daemon-less) and a tap must re-provision.
	@Test
	fun `a daemon restart failure after a successful proxy app rebuild tears down to Idle and a tap re-provisions`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			daemon.startReply = DaemonReply.Failed("daemon JVM would not start")
			manager.save(gradleFile)
			advanceUntilIdle()

			// The proxy app rebuild itself succeeded; only the restart failed. The failure
			// surfaces and the session dies clean instead of wedging half-alive - flagged, so
			// the bolt keeps the error tone (Q8).
			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
			assertThat(userMessages).contains(QuickBuildMessage.DaemonRestartFailed("daemon JVM would not start"))
			assertThat(daemon.isRunning).isFalse()

			// The next tap re-provisions from scratch and works again.
			daemon.startReply = DaemonReply.Ok(Unit)
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(provisionCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `an unconfirmed proxy app rebuild install parks the session for retry instead of dying to Idle`() =
		runTest {
			// The multi-module verify's stranded-session failure: the proxy app rebuild's Gradle
			// build succeeded but nobody tapped the reinstall dialog, so the installer
			// timed out. The session must stay recoverable, not drop to Idle.
			proxyAppRebuildOutcome = {
				ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("install was not confirmed"))
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						0,
						awaitingRetry = true,
					),
				)
			// The user is told what happened; the outcome's message is surfaced as-is
			// (the installer's ConfirmationNotGiven text already says how to recover
			// for its specific case - not shown / declined / timed out).
			assertThat(userMessages).contains(QuickBuildMessage.Literal("install was not confirmed"))
			// Parked, not torn down: the daemon stays down (it was shut down for the
			// Gradle build and there is no new baseline to restart it against yet).
			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.startConfigs).hasSize(1)
		}

	@Test
	fun `an unconfirmed reinstall shows the proxy app a return-to-CoGo banner`() =
		runTest {
			// The confirmed A06 finding (runs 20260810T003017Z/023304Z): the user watching
			// the deployed app is the ONE person the CoGo-side signals (snackbar, Build
			// Output, toolbar tone) cannot reach, so the park must tell the proxy app.
			proxyAppRebuildOutcome = {
				ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.ReinstallReturnToCoGo)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			val kinds =
				deploy.statusCalls.map {
					JsonParser
						.parseString(it)
						.asJsonObject
						.get("kind")
						.asString
				}
			assertThat(kinds).contains("reinstall_pending")
		}

	@Test
	fun `a recovered rebuild clears the proxy app's reinstall-pending banner`() =
		runTest {
			// When the retry's rebuild skips the reinstall (bytes already matched - e.g.
			// the deferred confirm completed while parked), the old process keeps running
			// with the banner up; recovery must take it down explicitly.
			var foregrounded = false
			proxyAppRebuildOutcome = {
				if (foregrounded) {
					defaultProxyAppRebuildSuccess()
				} else {
					ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.ReinstallReturnToCoGo)
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()

			foregrounded = true
			manager.onHostForegrounded()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			val kinds =
				deploy.statusCalls.map {
					JsonParser
						.parseString(it)
						.asJsonObject
						.get("kind")
						.asString
				}
			// The park announced itself, and the recovery took the banner down; order
			// matters - a clear before the park would leave the banner stuck.
			assertThat(kinds).containsAtLeast("reinstall_pending", "build_ok").inOrder()
		}

	@Test
	fun `tapping Quick Build after an unconfirmed install retries the proxy app rebuild and recovers`() =
		runTest {
			var confirmed = false
			proxyAppRebuildOutcome = {
				if (confirmed) {
					defaultProxyAppRebuildSuccess()
				} else {
					ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("install was not confirmed"))
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(1)

			// The user "confirms this time": the retried install goes through.
			confirmed = true
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			// The daemon restarted against the retried rebuild's proxy app info.
			assertThat(daemon.isRunning).isTrue()
			assertThat(daemon.startConfigs).hasSize(2)
		}

	@Test
	fun `CoGo returning to the foreground after an unconfirmed install retries the proxy app rebuild`() =
		runTest {
			// The backgrounded-CoGo case (corpus run 20260728T044815Z): the reinstall
			// ran with NO dialog ever shown - Android defers the PENDING_USER_ACTION
			// broadcast until the app is foregrounded, and the dialog-owning subscriber
			// is lifecycle-bound (registered onStart), so the deferred delivery can land
			// before it re-registers. The user's return to CoGo must re-prompt on its
			// own; they never saw anything to tap.
			var foregrounded = false
			proxyAppRebuildOutcome = {
				if (foregrounded) {
					defaultProxyAppRebuildSuccess()
				} else {
					ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("install was not confirmed"))
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(1)

			// The user comes back to CoGo: the editor's onResume forwards this.
			foregrounded = true
			manager.onHostForegrounded()
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(daemon.isRunning).isTrue()
		}

	@Test
	fun `foreground auto-retries are bounded - a user who keeps declining is not re-prompted forever`() =
		runTest {
			// Without a bound, every resume re-runs a full Gradle proxy app rebuild for a
			// user who keeps declining the reinstall. The auto-retry budget caps that; the
			// session ends parked, where a TAP still retries.
			proxyAppRebuildOutcome = {
				ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("install was not confirmed"))
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(1)

			// Each of the first MAX resumes retries (and re-parks, still unconfirmed).
			repeat(SessionReducer.MAX_INSTALL_AUTO_RETRIES) {
				manager.onHostForegrounded()
				advanceUntilIdle()
			}
			assertThat(proxyAppRebuildCount).isEqualTo(1 + SessionReducer.MAX_INSTALL_AUTO_RETRIES)

			// Budget spent: further resumes run NO Gradle build; the session stays parked.
			manager.onHostForegrounded()
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(1 + SessionReducer.MAX_INSTALL_AUTO_RETRIES)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						0,
						awaitingRetry = true,
						installAutoRetries = SessionReducer.MAX_INSTALL_AUTO_RETRIES,
					),
				)

			// An explicit tap is fresh consent: it still retries.
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(2 + SessionReducer.MAX_INSTALL_AUTO_RETRIES)
		}

	@Test
	fun `a retry that cannot get the Gradle slot defers instead of spending the auto-retry`() =
		runTest {
			// Losing the single Gradle slot is contention, not a build failure. Returning to
			// CoGo after a gradle edit starts CoGo's own project sync (the same change
			// invalidated the session) and the foreground retry asks for the slot ~2 s later,
			// so this collision is routine. Charging it to the one bounded retry drops the
			// session to Idle - a dead end instead of the install re-prompt.
			var slotBusy = false
			proxyAppRebuildOutcome = {
				if (slotBusy) {
					ProxyAppRebuildOutcome.BuildSlotBusy
				} else {
					ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.ReinstallReturnToCoGo)
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			val parked =
				QuickBuildSessionState.Invalidated(
					InvalidationReason.INSTALL_NOT_CONFIRMED,
					0,
					awaitingRetry = true,
				)
			assertThat(manager.state.value).isEqualTo(parked)

			slotBusy = true
			manager.onHostForegrounded()
			advanceUntilIdle()

			// It did attempt, and it parked straight back with the budget untouched.
			assertThat(proxyAppRebuildCount).isEqualTo(2)
			assertThat(manager.state.value).isEqualTo(parked)
			// The message does not degrade to a build failure - and it must not re-state
			// the park's "return to CoGo" guidance either: returning to CoGo is exactly
			// what triggered this retry, so the deferral says what is actually happening.
			assertThat(userMessages.last()).isEqualTo(QuickBuildMessage.ReinstallWaitingForGradle)
			// A deferred attempt is not a proxy app rebuild outcome; nothing is booked against the
			// proxy-app-rebuild success rate.
			assertThat(metricsEvents.filter { it.startsWith("proxyAppRebuild:") }).hasSize(1)

			// The retry the deferral gave back still works when the slot frees up.
			slotBusy = false
			proxyAppRebuildOutcome = { defaultProxyAppRebuildSuccess() }
			manager.onHostForegrounded()
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(3)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `deferred retries do not lift the bound on real foreground retries`() =
		runTest {
			// The give-back must not turn into an unbounded retry loop: attempts that really
			// run a Gradle build still cap at MAX_INSTALL_AUTO_RETRIES.
			var slotBusy = true
			proxyAppRebuildOutcome = {
				if (slotBusy) {
					ProxyAppRebuildOutcome.BuildSlotBusy
				} else {
					ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("not confirmed"))
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			slotBusy = false
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(1)

			// A deferred foreground retry costs nothing.
			slotBusy = true
			manager.onHostForegrounded()
			advanceUntilIdle()
			assertThat(proxyAppRebuildCount).isEqualTo(2)

			// The real ones then still bound at MAX.
			slotBusy = false
			repeat(SessionReducer.MAX_INSTALL_AUTO_RETRIES + 1) {
				manager.onHostForegrounded()
				advanceUntilIdle()
			}
			assertThat(proxyAppRebuildCount).isEqualTo(2 + SessionReducer.MAX_INSTALL_AUTO_RETRIES)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						0,
						awaitingRetry = true,
						installAutoRetries = SessionReducer.MAX_INSTALL_AUTO_RETRIES,
					),
				)
		}

	@Test
	fun `a first proxy app rebuild that cannot get the Gradle slot is reported, not parked`() =
		runTest {
			// Only a parked RETRY has somewhere to defer to. A first proxy app rebuild colliding
			// with another build keeps the existing behaviour: surface it and go Idle, where
			// the next tap re-provisions.
			proxyAppRebuildOutcome = { ProxyAppRebuildOutcome.BuildSlotBusy }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
			assertThat(userMessages).contains(QuickBuildMessage.RebuildFailed)
			// Surfaced to the user as a failed proxy app rebuild, so it books like one - only a
			// DEFERRED retry (slot busy while parked) skips the metrics sink.
			assertThat(metricsEvents.filter { it.startsWith("proxyAppRebuild:") })
				.containsExactly("proxyAppRebuild:false")
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
			assertThat(proxyAppRebuildCount).isEqualTo(0)
		}

	@Test
	fun `saves while parked for retry accumulate for the retried proxy app rebuild - no dead-daemon build`() =
		runTest {
			var confirmed = false
			proxyAppRebuildOutcome = {
				if (confirmed) {
					defaultProxyAppRebuildSuccess()
				} else {
					ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("not confirmed"))
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()

			// A source save while parked must NOT start a quick build: the daemon is
			// down, and the orchestrator still holds the proxy app rebuild's absorbed batch.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()

			// The retried proxy app rebuild absorbs the parked save (the file is on disk for
			// its Gradle build); the session comes back Ready without a quick build.
			confirmed = true
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a proxy app rebuild rebuilds the executor from the re-read proxyApp`() =
		runTest {
			// The proxy app rebuild regenerates setup.json; here it comes back schema v2 (e.g.
			// a manifest edit added a service the new baseline proxies).
			proxyAppRebuildOutcome = {
				val base = defaultProxyAppRebuildSuccess()
				base.copy(proxyApp = base.proxyApp.copy(schema = 2))
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(factoryProxyApps).hasSize(1)

			manager.save(gradleFile)
			advanceUntilIdle()

			// The live session's executor was rebuilt from the RE-READ proxy app info, not left
			// on the provisioning-time snapshot - otherwise the deploy policy would
			// keep routing on stale component facts for the rest of the session.
			assertThat(factoryProxyApps).hasSize(2)
			assertThat(factoryProxyApps.last().schema).isEqualTo(2)
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

			// A killed-and-relaunched proxy app that lost the deployed payload boots and
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
	fun `a gen-0 reconnect after a proxy app rebuild does not trigger a catch-up build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			manager.save(gradleFile)
			advanceUntilIdle()
			val buildsBefore = executed.size

			// The proxy app rebuild reinstalled a fresh baseline; its gen-0 IS current code,
			// so a reconnect at 0 must not be mistaken for staleness.
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			assertThat(executed).hasSize(buildsBefore)
		}

	@Test
	fun `a stamped provision adopts the baseline generation, so a reconnect at the stamp is in sync`() =
		runTest {
			// The provisioner allocated 5 from the persistent counter and stamped it into
			// the APK; the installed app boots (and reconnects) at 5, never at 0.
			provisionOutcome = {
				(defaultProvisionOutcome() as ProvisionOutcome.Success).copy(baselineGeneration = 5L)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(5))

			connections.onConnected(connectedAt(5))
			advanceUntilIdle()
			// In sync by construction: no catch-up build for a freshly provisioned app.
			assertThat(executed).isEmpty()

			// The session's allocator adopted the stamp, so the first deploy is strictly
			// newer than the installed baseline.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(6, 5))
		}

	@Test
	fun `a rebaseline's stamp becomes the deployed generation, so the post-rebaseline reconnect forces no build`() =
		runTest {
			// concurrency.md rule 2, the bug this exists for: before stamping, a rebaselined
			// app booted 0 while the session's tally held the old epoch's number, and every
			// reconnect forced a pointless catch-up build.
			provisionOutcome = {
				(defaultProvisionOutcome() as ProvisionOutcome.Success).copy(baselineGeneration = 1L)
			}
			proxyAppRebuildOutcome = {
				// The rebaseline allocated the next number (3: the deploy below burned 2)
				// from the same counter and stamped it into the reinstalled APK.
				defaultProxyAppRebuildSuccess().copy(baselineGeneration = 3L)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(2, 5))

			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(3))
			val buildsBefore = executed.size

			// The reinstalled app boots at its stamp and reconnects there: in sync, no
			// catch-up build.
			connections.onConnected(connectedAt(3))
			advanceUntilIdle()
			assertThat(executed).hasSize(buildsBefore)

			// And the next deploy stays strictly above the stamped baseline, so the
			// runtime cannot reject it as stale.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(4, 5))
		}

	@Test
	fun `a below-deployed reconnect re-sends the retained payload instead of rebuilding`() =
		runTest {
			// concurrency.md rules 3-4: the session still holds the bytes it last deployed,
			// so a proxy app that lost its persisted payload is repaired by re-sending them
			// at their original generation - not by a forced blind rebuild of a module that
			// did not change.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)

			seedRetainedPayload(generation = 1L)
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			// The retained bytes went straight through the deploy channel at their original
			// generation, and no build ran.
			val resent = deploy.calls.single()
			assertThat(resent.generation).isEqualTo(1L)
			assertThat(resent.dexFile!!.readText()).isEqualTo("retained-dex")
			assertThat(resent.metadataJson).contains("entryActivity")
			assertThat(executed).hasSize(1)
		}

	@Test
	fun `a failed re-send falls back to the forced catch-up build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)

			seedRetainedPayload(generation = 1L)
			// The relaunched app dropped its binding again before the re-send landed.
			deploy.result = DeployResult.NotConnected
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			// Re-send attempted once, then the last-resort repair: a forced rebuild of
			// current sources.
			assertThat(deploy.calls).hasSize(1)
			assertThat(executed).hasSize(2)
			assertThat(executed.last().forced).isTrue()
		}

	@Test
	fun `retention from an older deploy is never replayed - the forced build repairs instead`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(2)

			// Retention stuck at generation 1 while the session deployed 2 (the later
			// retention write failed). Replaying 1 would leave the app still behind the
			// deploy tally with nothing left to notice it.
			seedRetainedPayload(generation = 1L)
			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			assertThat(deploy.calls).isEmpty()
			assertThat(executed).hasSize(3)
			assertThat(executed.last().forced).isTrue()
		}

	/**
	 * Writes a retained last-deployed payload where the live session's store reads it, as
	 * the real executor would have after a confirmed deploy - the scripted executor in these
	 * tests deploys (and so retains) nothing.
	 */
	private fun seedRetainedPayload(generation: Long) {
		val dex = File(projectRoot, "retained.dex").apply { writeText("retained-dex") }
		RetainedPayloadStore
			.forWorkDir(QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot).workDirFor(projectRoot))
			.retain(generation, dex, null, null, """{"entryActivity":"com.example.MainActivity"}""")
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
	fun `a clean tap while Ready builds nothing - the deployed app is already current`() =
		runTest {
			// The F7 do-nothing tap: the old behavior forced a blind NoOp rebuild that
			// recompiled a whole module to redeploy identical bytes.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(launches).hasSize(launchesBefore + 1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
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
	fun `daemon death with nothing pending respawns and re-warms via a deploy-nothing warm compile`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			daemon.die(exitCode = 137)
			advanceUntilIdle()

			// Respawned: configure ran twice (provision + respawn)...
			assertThat(daemon.startConfigs).hasSize(2)
			// ...and with nothing pending the re-warm is a WARM COMPILE (one per daemon life:
			// provisioning's + the respawn's) - no user build, no deploy, the proxy app
			// keeps running its current generation untouched.
			assertThat(executed).isEmpty()
			assertThat(warmCompiles).hasSize(2)
			assertThat(warmCompiles.last().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	/**
	 * The bench seam turns off PROVISIONING's warm-up only. A respawn's re-seed is not a
	 * warm-up: with work pending it is what tells the fresh daemon its incremental universe
	 * is gone, so gating the whole re-seed would trade a benchmark arm's tidiness for a
	 * build that recompiles only the changed files against a daemon holding nothing. Pinned
	 * so a future gate cannot land silently.
	 */
	@Test
	fun `the daemon-respawn re-seed runs even with the warm compile bench seam off`() =
		runTest {
			val manager = createManager(warmCompileEnabled = { false })
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(warmCompiles).isEmpty()

			daemon.die(exitCode = 137)
			advanceUntilIdle()

			assertThat(daemon.startConfigs).hasSize(2)
			// Exactly one warm compile: the respawn's, the one provisioning skipped.
			assertThat(warmCompiles.single().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `proxy app crash reported by the host service surfaces as a session failure`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			connections.report(TargetReport.Crashed(0, "NullPointerException in onCreate"))
			advanceUntilIdle()

			val state = manager.state.value
			assertThat(state).isInstanceOf(QuickBuildSessionState.Ready::class.java)
			assertThat((state as QuickBuildSessionState.Ready).lastFailure)
				.isEqualTo(SessionFailure.ProxyAppCrash("NullPointerException in onCreate"))
		}

	@Test
	fun `prebuild runs the proxy app build only - no install, no daemon, back to Idle`() =
		runTest {
			val manager = createManager()

			manager.prebuild()
			advanceUntilIdle()

			assertThat(prebuildCount).isEqualTo(1)
			// Nothing provisioned: no install path, no daemon, no watcher, no session.
			assertThat(provisionCount).isEqualTo(0)
			assertThat(daemon.startConfigs).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden())
		}

	@Test
	fun `tap during prebuild queues and provisions once the warm build finishes`() =
		runTest {
			prebuildGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()

			manager.prebuild()
			advanceUntilIdle()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The tap does not race the warm Gradle build.
			assertThat(provisionCount).isEqualTo(0)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = true))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Provisioning())

			prebuildGate!!.complete(Unit)
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `prebuild failure is silent and leaves the session Idle`() =
		runTest {
			prebuildError = RuntimeException("proxy app build failed")
			val manager = createManager()

			manager.prebuild()
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			// The user never asked for the warm build; no error surfaces.
			assertThat(userMessages).isEmpty()
		}

	@Test
	fun `prebuild while a session is live does not disturb it`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.prebuild()
			advanceUntilIdle()

			assertThat(prebuildCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a sync that did not change the build variant leaves a live session alone`() =
		runTest {
			provisionOutcome = { defaultProvisionOutcome("demoDebug") }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onProjectSynced("demoDebug")
			advanceUntilIdle()

			// Same no-op as a bare prebuild: an ordinary sync must not cost a reprovision.
			assertThat(provisionCount).isEqualTo(1)
			assertThat(prebuildCount).isEqualTo(0)
			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a sync that changed the build variant reprovisions the live session`() =
		runTest {
			// Applying a new Build Variants selection re-syncs the project. Left alone, the
			// live session keeps hot-reloading into the OLD variant's proxy app - a different
			// applicationId as soon as a flavor carries a suffix, so the user edits one app and
			// watches another.
			provisionOutcome = { defaultProvisionOutcome("demoDebug") }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			provisionOutcome = { defaultProvisionOutcome("fullDebug") }
			manager.onProjectSynced("fullDebug")
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(2)
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a sync that cannot name the selected variant leaves a live session alone`() =
		runTest {
			// The project model has no module to ask during a sync, and an unknown selection is
			// not evidence of a change - tearing a healthy session down on it would make an
			// ordinary sync a coin flip.
			provisionOutcome = { defaultProvisionOutcome("demoDebug") }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onProjectSynced(null)
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a sync with no live session warms the proxy app build`() =
		runTest {
			val manager = createManager()

			manager.onProjectSynced("demoDebug")
			advanceUntilIdle()

			// Nothing to compare against, so the sync hook is exactly the eager prebuild.
			assertThat(prebuildCount).isEqualTo(1)
			assertThat(provisionCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `prebuild runs even on a project that has never used Quick Build`() =
		runTest {
			// Skipping the warm-up until Quick Build has been tapped once on the project would
			// make the FIRST tap on every new project pay the whole cold proxy app build cost
			// (~97 s on an a56 for a small app). If the feature is enabled, warm it -- the flag
			// is the only gate.
			historyStore.setHasUsedQuickBuild(false)
			val manager = createManager()

			manager.prebuild()
			advanceUntilIdle()

			assertThat(prebuildCount).isEqualTo(1)
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
	fun `the tap reaches the reducer before the history write, not after it`() =
		runTest {
			// The reducer must see the tap without waiting on the history write, which is a
			// side effect that can be slow. prebuild() dispatches immediately, so a tap
			// sequenced behind that write can be reduced after PrebuildFinished has already
			// settled the session back to Idle - which is what a "dead" first press on the
			// primary control looks like.
			var stateAtWrite: QuickBuildSessionState? = null
			val manager = createManager()
			historyStore.onWrite = { stateAtWrite = manager.state.value }

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(stateAtWrite).isNotNull()
			assertThat(stateAtWrite).isNotEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a tap still starts the session when recording history fails`() =
		runTest {
			// A throwing store must not kill the coroutine before the dispatch: that loses
			// the tap outright - the one press the parked-session banner tells the user to
			// make.
			historyStore.writeError = IllegalStateException("no project open")
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `after a failed provisioning the first tap starts a session even mid-prebuild`() =
		runTest {
			// End to end: a proxy app rebuild retry failed, the session is Idle,
			// CoGo's project sync then finishes and fires the project-open prebuild - and the
			// user's FIRST tap has to start the session, not be absorbed by the warm-up.
			provisionOutcome = { ProvisionOutcome.Failure(QuickBuildMessage.Literal("Proxy app rebuild failed")) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))

			prebuildGate = kotlinx.coroutines.CompletableDeferred()
			manager.prebuild()
			advanceUntilIdle()
			// The warm build still runs; the failed-start flag rides along uncleared (Q8).
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Prebuilding(lastStartFailed = true))

			provisionOutcome = null
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			// Recorded on the warm-up rather than dropped: the queued tap is what turns
			// PrebuildFinished into provisioning instead of a return to Idle.
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = true))

			prebuildGate!!.complete(Unit)
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(provisionCount).isEqualTo(2)
		}

	@Test
	fun `standard run completion refreshes the baseline - the next save recompiles everything`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			// Deferred refresh: no build behind the user's back, state unchanged.
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
	fun `standard run completion with clobbered proxy app build artifacts forces a full proxy app rebuild`() =
		runTest {
			provisionOutcome = {
				val base = defaultProvisionOutcome() as ProvisionOutcome.Success
				base.copy(
					proxyApp =
						base.proxyApp.copy(
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
			assertThat(proxyAppRebuildCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `standard run completion with all proxy app build artifacts present refreshes the baseline incrementally`() =
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
					proxyApp =
						base.proxyApp.copy(
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

			assertThat(proxyAppRebuildCount).isEqualTo(0)
			assertThat(executed.single().changes).isEqualTo(ChangedFiles.Unknown)
		}

	@Test
	fun `standard run completion with a missing classpath jar forces a full proxy app rebuild`() =
		runTest {
			provisionOutcome = {
				val base = defaultProvisionOutcome() as ProvisionOutcome.Success
				base.copy(
					proxyApp =
						base.proxyApp.copy(
							classpath = listOf(File(projectRoot, "build/intermediates/r.jar")),
						),
				)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(1)
		}

	@Test
	fun `standard run completion without a session is a no-op`() =
		runTest {
			val manager = createManager()

			manager.onStandardRunCompleted()
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(proxyAppRebuildCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
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

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden())
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
	fun `restartSessionAndReprovision tears down and provisions again without a second tap`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)

			manager.restartSessionAndReprovision()
			advanceUntilIdle()

			// T15: the whole point. The old session is gone AND a new one is live, with no
			// second tap - resting at Idle is what read as "does restart session do anything?".
			assertThat(provisionCount).isEqualTo(2)
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `restartSessionAndReprovision starts the new daemon only after the old one is down`() =
		runTest {
			// The teardown's daemon shutdown is asynchronous, so chaining a provision straight
			// behind it would otherwise be safe only by timing - the shutdown happening to finish
			// inside the new session's Gradle build. Hold the shutdown open and the ordering has
			// to carry it: nothing of the new session may start meanwhile, or that in-flight
			// shutdown is handed the daemon the new session just spawned.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)

			val shutdownGate = CompletableDeferred<Unit>()
			daemon.shutdownGate = shutdownGate
			manager.restartSessionAndReprovision()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(1)

			shutdownGate.complete(Unit)
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `restartSessionAndReprovision from idle provisions a session`() =
		runTest {
			val manager = createManager()

			manager.restartSessionAndReprovision()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a restart mid-provision cancels the Gradle build, not just the coroutine awaiting it`() =
		runTest {
			// Cancelling the coroutine abandons the AWAIT; Gradle runs out of process behind a
			// future and keeps running. It holds the device's single build slot, so the
			// reprovision behind this teardown is refused SlotBusy - the user taps "Restart
			// session" and gets a setup failure. Only the stop tap emitted a cancel effect, and a
			// restart is not a stop tap.
			provisionGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))

			manager.restartSession()
			advanceUntilIdle()

			assertThat(proxyAppBuildCancelCount).isEqualTo(1)
		}

	@Test
	fun `an idle teardown cancels nothing - there is no build of ours to stop`() =
		runTest {
			// The provisioner refuses a cancel that would kill the user's own Standard Run, but
			// this must not lean on that: with no session work there is nothing of ours in
			// flight, so the request is not made at all.
			val manager = createManager()

			manager.restartSession()
			advanceUntilIdle()

			assertThat(proxyAppBuildCancelCount).isEqualTo(0)
		}

	@Test
	fun `restart during provisioning cancels the in-flight provision - no zombie session`() =
		runTest {
			provisionGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))

			manager.restartSession()
			advanceUntilIdle()
			provisionGate!!.complete(Unit)
			advanceUntilIdle()

			// The cancelled provision never went live: no daemon, no watcher, still Idle.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
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

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden())
			assertThat(daemon.startConfigs).isEmpty()
			assertThat(connections.expectedPackage).isNull()
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()
		}

	@Test
	fun `restart during prebuild cancels the warm wait and the next tap provisions fresh`() =
		runTest {
			prebuildGate = kotlinx.coroutines.CompletableDeferred()
			val manager = createManager()

			manager.prebuild()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Prebuilding())

			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(provisionCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `provisioning failure emits on the userMessages flow`() =
		runTest {
			provisionOutcome = { ProvisionOutcome.Failure(QuickBuildMessage.Literal("no build service")) }
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// The shared recorder is the one collector; the queue is single-consumer, so a
			// second one here would race it for the message.
			assertThat(userMessages).containsExactly(QuickBuildMessage.Literal("no build service"))
		}

	@Test
	fun `restartSession while idle is a no-op`() =
		runTest {
			val manager = createManager()

			manager.restartSession()
			advanceUntilIdle()

			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
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

			// The user switched to their running proxy app to look at the edit they just
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
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
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
			// executor reports for a torn-down daemon (LiveReloadExecutorImpl.compileAndDex
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

	// Bryan's button spec, behaviours 2-5. The governing principle: bringing the proxy app
	// forward answers the USER asking. A tap asks; a save does not; a cancelled tap withdraws
	// the ask. Each test below pins one of those clauses.

	@Test
	fun `the first tap brings the freshly installed proxy app to the foreground`() =
		runTest {
			// Behaviour 2 at its coldest: nothing else in the system ever launches the proxy
			// app after its install, so if the session going live did not do it the user would
			// tap, wait through the whole provisioning, and be left staring at the editor.
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(launches).containsExactly("com.example.quickbuild" to null)
		}

	@Test
	fun `a save-triggered build never brings the proxy app forward`() =
		runTest {
			// Behaviour 3: the user is typing. A save is not a request to leave the editor.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesAfterProvisioning = launches.size

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(launches).hasSize(launchesAfterProvisioning)
		}

	@Test
	fun `a tap landing on a save-triggered build switches when THAT build deploys, without rebuilding`() =
		runTest {
			// Behaviour 2's hard case: the tap has no build of its own to wait for, because
			// the in-flight one already deploys. It must neither vanish (no switch) nor force
			// a duplicate full rebuild behind a build that was about to satisfy it.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size
			val gate = CompletableDeferred<Unit>()
			executionGate = gate

			manager.save(sourceFile)
			advanceUntilIdle()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			// Still mid-build: no switch yet, and no second build queued behind this one.
			assertThat(launches).hasSize(launchesBefore)
			assertThat(executed).hasSize(1)

			gate.complete(Unit)
			advanceUntilIdle()

			assertThat(launches).hasSize(launchesBefore + 1)
			assertThat(executed).hasSize(1)
		}

	@Test
	fun `a tap with nothing to build switches immediately and runs no build at all`() =
		runTest {
			// Behaviour 4, sharpened by the F7 fix: with nothing written and nothing pending
			// the deployed app is current, so the tap is answered by the switch alone - the
			// forced redeploy that used to run behind it recompiled a whole module to deliver
			// identical bytes.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			// And exactly once.
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `a tap that wrote something waits for its batch and switches when that build deploys`() =
		runTest {
			// The F7 root fix: the tap's save-all wrote files whose watcher batch is still in
			// the coalescer window. The batch drives the one, correctly-routed build; the user
			// switches when IT deploys - not before, and with no forced NoOp echo pair.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.onQuickBuildTapped(wroteSomething = true)
			runCurrent()
			// Armed, not answered: no build yet, no switch yet.
			assertThat(executed).isEmpty()
			assertThat(launches).hasSize(launchesBefore)

			// The save-all's batch lands (well inside the fallback deadline).
			manager.save(sourceFile)
			advanceUntilIdle()

			val request = executed.single()
			assertThat(request.forced).isFalse()
			assertThat(request.userInitiated).isTrue()
			assertThat((request.changes as ChangedFiles.Known).files).containsExactly(sourceFile)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			// Exactly once, on the deploy - advanceUntilIdle already ran the deadline
			// fallback's timer past its 2 s, so this also proves it did not double-switch.
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `a tap whose saves were all watcher-irrelevant still switches after the fallback deadline`() =
		runTest {
			// The .md-save edge: the save-all wrote something, but nothing the watcher reports,
			// so no batch ever comes. The armed switch must fall back rather than leave the tap
			// unanswered forever.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.onQuickBuildTapped(wroteSomething = true)
			runCurrent()
			assertThat(launches).hasSize(launchesBefore)

			// No batch arrives; the deadline answers the tap - once, with no build.
			advanceTimeBy(2_001L)
			runCurrent()
			assertThat(launches).hasSize(launchesBefore + 1)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			// A save long after the expired tap is a plain save: builds, but never switches.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(executed.single().userInitiated).isFalse()
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `the tap fallback does not switch before its 2 s deadline`() =
		runTest {
			// The F7 lower bound: the deadline must outlast the watcher's debounce and its
			// mtime-poll emit window. Shortened under them, a slow batch gets the tap answered
			// twice - the fallback switches, then the batch's own deploy switches again.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.onQuickBuildTapped(wroteSomething = true)
			runCurrent()

			// Just under the deadline: still waiting on the batch, no switch yet.
			advanceTimeBy(1_999L)
			runCurrent()
			assertThat(launches).hasSize(launchesBefore)

			// At exactly 2 s the deadline answers the tap.
			advanceTimeBy(1L)
			runCurrent()
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `a tap that starts a rebaseline waits for it instead of handing back the stale app`() =
		runTest {
			// Behaviour 4's exception, and the T8 bug (manual QA, 2026-08-11): a tap that lands on
			// a full Gradle build cannot switch straight away. The app on the device is the one
			// the rebaseline is replacing, so switching hands the user the stale build for the
			// whole rebuild - and backgrounds CoGo, which is the only process that can raise the
			// install confirmation the rebuild ends in.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Park the session on a rebaseline the user has to retry, which is the one place a
			// tap is the thing that starts a full Gradle build.
			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.GRADLE_CONFIG_CHANGED,
						0,
						awaitingRetry = true,
					),
				)
			val launchesBefore = launches.size

			failProxyAppRebuild = false
			val rebGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = rebGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Mid-rebaseline: the ask is held, not answered and not dropped.
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Provisioning(
						rebaselineReason = InvalidationReason.GRADLE_CONFIG_CHANGED,
					),
				)
			assertThat(launches).hasSize(launchesBefore)

			rebGate.complete(Unit)
			advanceUntilIdle()

			// The rebaseline landed: its own relaunch brings the reinstalled app back
			// (ADFA-4128: the rebaseline shares the restart deploy's launch path), and the
			// deferred ask is answered exactly once on top of it.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 2)
		}

	@Test
	fun `a rebaseline that fails leaves the user in the editor where the error is`() =
		runTest {
			// The other half of T8: a deferred switch is dropped, not queued. The error lives in
			// the editor's build output, and the app on the device is still the stale one.
			proxyAppRebuildOutcome = {
				ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(2)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.GRADLE_CONFIG_CHANGED,
						0,
						awaitingRetry = true,
					),
				)
			assertThat(launches).hasSize(launchesBefore)
		}

	@Test
	fun `a deferred foreground ask that has gone stale expires instead of yanking the user out of the editor`() =
		runTest {
			// F5 (manual QA, 2026-08-13): a rebaseline settled a 34-second-old ask on top of a
			// user who had deliberately returned to the editor mid-typing. Past the age bound
			// the ask no longer says where the user wants to be, so the landing build drops it.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Park on a failed rebaseline, then tap: the tap starts the retry and defers
			// its foreground ask behind the full Gradle build.
			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val rebGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = rebGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore)

			// The rebaseline grinds on well past the point where the ask still means anything.
			fakeNowMillis += 34_000L
			rebGate.complete(Unit)
			advanceUntilIdle()

			// The build landed fine - the rebaseline's own relaunch brings the reinstalled
			// app back (one launch), but the stale ask expired rather than adding a second
			// deferred switch on top.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `a deferred foreground ask younger than the age bound is still answered when the build lands`() =
		runTest {
			// The boundary partner of the expiry test: a short rebaseline still owes the user
			// the switch they asked for, so the expiry must not fire early.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val rebGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = rebGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore)

			fakeNowMillis += 9_000L
			rebGate.complete(Unit)
			advanceUntilIdle()

			// The rebaseline's own relaunch plus the answered ask.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 2)
		}

	@Test
	fun `a deferred foreground ask at exactly the age bound is still answered`() =
		runTest {
			// The boundary itself (F5): expiry is age STRICTLY past the 10 s bound. With only
			// the 34 s / 9 s pair above, a `>` to `>=` flip - or the bound quietly changing -
			// keeps every test green.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val rebGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = rebGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore)

			fakeNowMillis += 10_000L
			rebGate.complete(Unit)
			advanceUntilIdle()

			// The rebaseline's own relaunch plus the answered ask.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 2)
		}

	@Test
	fun `a deferred foreground ask one millisecond past the age bound expires`() =
		runTest {
			// The expiry partner of the exact-bound test: together they pin the constant at
			// 10 s in both directions.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val rebGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = rebGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore)

			fakeNowMillis += 10_001L
			rebGate.complete(Unit)
			advanceUntilIdle()

			// Only the rebaseline's own relaunch; the expired ask adds no second switch.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `chained full builds settle the deferred ask exactly once, aged from the original tap`() =
		runTest {
			// The chained-build shape behind the re-defer question: a gradle edit mid-rebuild
			// chains a second full build onto the first landing. The landing's settle runs
			// before the chained invalidation can dispatch, so the ask is settled ONCE there,
			// against the original tap's stamp - answered here (6 s old), and never again by
			// the chained build's own landing.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val firstGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = firstGate

			// The tap defers its foreground ask behind the retry's full Gradle build.
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore)

			// A gradle edit mid-rebuild chains a second full build onto the landing. The mtime
			// bump keeps the orchestrator's echo split from absorbing it into the running
			// rebuild - this is a genuinely new edit, not the tap's own save echo.
			gradleFile.setLastModified(System.currentTimeMillis() + 3_600_000L)
			manager.save(gradleFile)
			advanceUntilIdle()

			val secondGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = secondGate
			fakeNowMillis += 6_000L
			firstGate.complete(Unit)
			advanceUntilIdle()
			// The first landing relaunches the reinstalled app, and the 6-second-old ask is
			// answered there, before the chained rebuild takes the session back to
			// Provisioning.
			assertThat(launches).hasSize(launchesBefore + 2)
			assertThat(proxyAppRebuildCount).isEqualTo(3)

			fakeNowMillis += 6_000L
			secondGate.complete(Unit)
			advanceUntilIdle()

			// The chained landing relaunches its own reinstall, but must not answer the
			// same tap twice.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 3)
		}

	@Test
	fun `a deferred ask stale at a chained landing expires and the chained build cannot revive it`() =
		runTest {
			// The audit's chained-build fear, pinned in its observable form: the first build
			// runs the ask past the 10 s bound, and a chained full build is already queued
			// when it lands. Expiry is judged against the ORIGINAL tap - so nothing may
			// switch at the stale first landing, and the chained landing moments later must
			// not resurrect the dead ask either.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val firstGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = firstGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore)

			gradleFile.setLastModified(System.currentTimeMillis() + 3_600_000L)
			manager.save(gradleFile)
			advanceUntilIdle()

			val secondGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = secondGate
			fakeNowMillis += 11_000L
			firstGate.complete(Unit)
			advanceUntilIdle()
			// Stale at the first landing: its own relaunch runs, but the expired ask adds
			// no deferred switch; chained rebuild under way.
			assertThat(launches).hasSize(launchesBefore + 1)
			assertThat(proxyAppRebuildCount).isEqualTo(3)

			fakeNowMillis += 2_000L
			secondGate.complete(Unit)
			advanceUntilIdle()

			// The chained landing is only moments after the expiry; it relaunches its own
			// reinstall, but the ask stays dead.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 2)
		}

	@Test
	fun `a new ask after an expiry stamps a fresh clock and is answered normally`() =
		runTest {
			// Guards the other direction of the preserve-on-re-defer fix: the expiry nulls the
			// stamp, so the next tap's ask must age from ITS OWN deferral, not the dead one's.
			var failProxyAppRebuild = true
			proxyAppRebuildOutcome = {
				if (failProxyAppRebuild) {
					ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal("manifest does not build"))
				} else {
					defaultProxyAppRebuildSuccess()
				}
			}
			val manager = createManager(nowMillis = { fakeNowMillis })
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(gradleFile)
			advanceUntilIdle()
			val launchesBefore = launches.size
			failProxyAppRebuild = false
			val firstGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = firstGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// First ask goes stale and expires; only the landing's own relaunch runs.
			fakeNowMillis += 34_000L
			firstGate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 1)

			// Park again, then a fresh tap: 9 s is young against the new ask's own clock
			// even though 43 s have passed since the expired one.
			failProxyAppRebuild = true
			manager.save(gradleFile)
			advanceUntilIdle()
			failProxyAppRebuild = false
			val secondGate = CompletableDeferred<Unit>()
			proxyAppRebuildGate = secondGate

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(launches).hasSize(launchesBefore + 1)

			fakeNowMillis += 9_000L
			secondGate.complete(Unit)
			advanceUntilIdle()

			// The second landing's relaunch plus the fresh ask, answered normally.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore + 3)
		}

	@Test
	fun `a stale reconnect catch-up build does not drag the user into the proxy app`() =
		runTest {
			// The catch-up build is forced, exactly like a tap - which is why "the user asked"
			// cannot be read off BuildRequest.forced. Nobody tapped anything here.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()
			val launchesBefore = launches.size

			connections.onConnected(connectedAt(0))
			advanceUntilIdle()

			assertThat(executed).hasSize(2)
			assertThat(launches).hasSize(launchesBefore)
		}

	@Test
	fun `stopping a build reports a cancellation, deploys nothing and keeps the pending edits`() =
		runTest {
			// Behaviour 5. Three claims: nothing deploys, the report is a NOTICE rather than an
			// error, and the never-lose-pending invariant survives - the cancelled edit is
			// rebuilt by the next save rather than dropped.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			val launchesBefore = launches.size
			val gate = CompletableDeferred<Unit>()
			executionGate = gate

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Building(0))

			manager.onCancelRequested()
			advanceUntilIdle()

			// Back to the bolt at the generation the app still runs, with no failure: the
			// user chose this, so it must not read as a broken build.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))
			assertThat(notices).containsExactly(QuickBuildNotice.BUILD_CANCELLED)
			assertThat(userMessages).isEmpty()

			// Releasing the abandoned build must not resurrect its deploy.
			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(launches).hasSize(launchesBefore)

			// The cancelled edit is still owed a build: the next save carries BOTH files.
			executionGate = null
			val other =
				File(projectRoot, "app/src/main/java/com/example/Bar.kt").apply { writeText("class Bar") }
			manager.save(other)
			advanceUntilIdle()
			assertThat((executed.last().changes as ChangedFiles.Known).files)
				.containsExactly(sourceFile, other)
		}

	@Test
	fun `stopping is a no-op during the background warm compile - the user never asked for it`() =
		runTest {
			// The warm compile deploys nothing and the button shows the bolt throughout, so there is
			// no build here for the user to cancel. Cancelling it would also throw away the
			// daemon warm-up the next real save is about to need.
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Building(0, warmingCompiler = true))

			manager.onCancelRequested()
			advanceUntilIdle()

			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Building(0, warmingCompiler = true))
			assertThat(notices).isEmpty()

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `stopping a queued tap during prebuild cancels the Gradle proxy app build and never provisions`() =
		runTest {
			// Behaviour 5 mid-PROVISIONING. The proxy app build runs out of process behind a future, so
			// abandoning the coroutine that awaits it would leave Gradle running while the
			// button went idle - the cancel has to reach the tooling server.
			val gate = CompletableDeferred<Unit>()
			prebuildGate = gate
			val manager = createManager()
			manager.prebuild()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = true))

			manager.onCancelRequested()
			advanceUntilIdle()

			assertThat(proxyAppBuildCancelCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(notices).containsExactly(QuickBuildNotice.BUILD_CANCELLED)

			// The queued tap went with the cancel: the warm build finishing must not now
			// provision something the user just stopped.
			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(provisionCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `stopping during provisioning cancels the proxy app build and tears the session down`() =
		runTest {
			val gate = CompletableDeferred<Unit>()
			provisionGate = gate
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))

			manager.onCancelRequested()
			advanceUntilIdle()

			assertThat(proxyAppBuildCancelCount).isEqualTo(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(notices).containsExactly(QuickBuildNotice.BUILD_CANCELLED)

			// A provision that outlives the stop must not install itself as a zombie session.
			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(watcher).isNull()
		}

	/**
	 * A provision whose baseline declares [components] - the only fact the stale-helper
	 * warning keys on, since whether such a component is currently INSTANTIATED is unknowable
	 * from here.
	 */
	private fun provisionWithComponents(vararg components: ComponentInfo): ProvisionOutcome {
		val base = defaultProvisionOutcome() as ProvisionOutcome.Success
		return base.copy(proxyApp = base.proxyApp.copy(components = components.toList()))
	}

	private val syncService = ComponentInfo(ComponentKind.SERVICE, "com.example.SyncService")

	private val logSenderService =
		ComponentInfo(ComponentKind.SERVICE, "com.itsaky.androidide.logsender.LogSenderService")
	private val logSenderInstaller =
		ComponentInfo(ComponentKind.PROVIDER, "com.itsaky.androidide.logsender.utils.LogSenderInstaller")

	@Test
	fun `a hot swap warns nothing when the only components are the ones CoGo injected`() =
		runTest {
			// Logsender is in every debuggable build, so warning on it would fire this notice on
			// every ordinary app - about code the user did not write and cannot go stale.
			provisionOutcome = { provisionWithComponents(logSenderInstaller, logSenderService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			manager.save(sourceFile)
			advanceUntilIdle()

			// The deploy really landed by hot swap - the silence below is a decision, not a no-op.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(notices).isEmpty()
		}

	@Test
	fun `a user service still warns when CoGo's own components are alongside it`() =
		runTest {
			provisionOutcome = { provisionWithComponents(logSenderInstaller, syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(notices).containsExactly(QuickBuildNotice.STALE_COMPONENT_HELPERS)
		}

	@Test
	fun `a crashing reload tells the user how to recover, every time it crashes`() =
		runTest {
			// The accepted limitation is that a crashing payload redeploys and crashes again
			// until the session is restarted. The bug was the SILENCE: the ATTENTION icon
			// alone never says that only a session restart clears it. Repeated deliberately -
			// each reload reproduces the crash, so each one has to say so.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			connections.report(TargetReport.Crashed(0, "NPE in onCreate"))
			advanceUntilIdle()

			assertThat(notices).containsExactly(QuickBuildNotice.RELOAD_CRASHED)
			// The failure itself still lands on the status surface; the notice is the remedy,
			// not a replacement for it.
			assertThat(manager.status.value)
				.isEqualTo(QuickBuildStatus.Failed(0, SessionFailure.ProxyAppCrash("NPE in onCreate")))
			// Not the error channel's business: userMessages is what the host flashes
			// verbatim, and this copy lives in the app's string resources.
			assertThat(userMessages).isEmpty()

			connections.report(TargetReport.Crashed(1, "NPE in onCreate"))
			advanceUntilIdle()
			assertThat(notices)
				.containsExactly(QuickBuildNotice.RELOAD_CRASHED, QuickBuildNotice.RELOAD_CRASHED)
		}

	@Test
	fun `a hot-swap deploy warns once per session that a live service still calls the old code`() =
		runTest {
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			manager.save(sourceFile)
			advanceUntilIdle()

			// The deploy landed by hot swap (restarted = false), so the running service keeps
			// calling the previous copies of whatever this build recompiled.
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(notices).containsExactly(QuickBuildNotice.STALE_COMPONENT_HELPERS)

			// Once per session: the gap holds for every later hot swap, and re-flashing it on
			// each save would bury the notices that report something happening.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).hasSize(2)
			assertThat(notices).containsExactly(QuickBuildNotice.STALE_COMPONENT_HELPERS)
		}

	@Test
	fun `a stale-helper warning nobody heard is still owed - the latch needs a listener`() =
		runTest {
			// This warning is raised by a hot-swap deploy, which lands while the user is in the
			// PROXY APP - so the editor's lifecycle-bound collector is usually gone. The queue
			// holds it for the collector that attaches next; what must not happen is the latch
			// being spent on a warning nobody will ever hear, precisely in the case it was most
			// needed. So: exactly one warning across both saves, and it arrives.
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// No collector: CoGo is backgrounded, which is the normal state for this deploy.
			manager.save(sourceFile)
			advanceUntilIdle()

			val notices = recordNotices(manager)
			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(executed).hasSize(2)
			assertThat(notices).containsExactly(QuickBuildNotice.STALE_COMPONENT_HELPERS)
		}

	@Test
	fun `a notice raised while CoGo is backgrounded is delivered when the editor returns`() =
		runTest {
			// The whole of C5: the only collector lives inside repeatOnLifecycle(STARTED), so it is
			// gone for every notice raised while the user is in their proxy app - which is where a
			// reload crash is raised by construction. The notice waits in the queue instead.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			connections.report(TargetReport.Crashed(0, "NPE in onCreate"))
			advanceUntilIdle()

			val notices = recordNotices(manager)
			advanceUntilIdle()

			assertThat(notices).containsExactly(QuickBuildNotice.RELOAD_CRASHED)
		}

	@Test
	fun `a notice already delivered is not repeated when the collector reattaches`() =
		runTest {
			// Why a queue and not replay = 1: the collector re-subscribes on EVERY transition back
			// to STARTED, so a replayed notice would re-flash on each return to the editor - the
			// same defect C22 reports for flashedFailure. Delivered exactly once, to one collector.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val heard = mutableListOf<QuickBuildNotice>()
			val collector =
				backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
					manager.notices.collect { heard += it }
				}
			connections.report(TargetReport.Crashed(0, "NPE in onCreate"))
			advanceUntilIdle()
			assertThat(heard).containsExactly(QuickBuildNotice.RELOAD_CRASHED)

			// CoGo goes to the background and comes back: a fresh collector on the same queue.
			collector.cancelAndJoin()
			val afterReturn = recordNotices(manager)
			advanceUntilIdle()

			assertThat(afterReturn).isEmpty()
		}

	@Test
	fun `a failure raised while CoGo is backgrounded is flashed when the editor returns`() =
		runTest {
			// userMessages carries the same fix as notices, and needs it more:
			// ReinstallReturnToCoGo asks the user to come back to CoGo, so by construction it is
			// raised while they are not in CoGo.
			provisionOutcome = { ProvisionOutcome.Failure(QuickBuildMessage.Literal("no build service")) }
			val manager = createManager(collectUserMessages = false)

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			val heard = mutableListOf<QuickBuildMessage>()
			backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				manager.userMessages.collect { heard += it }
			}
			advanceUntilIdle()

			assertThat(heard).containsExactly(QuickBuildMessage.Literal("no build service"))
		}

	@Test
	fun `a queued warning evicted before anyone hears it is owed again`() =
		runTest {
			// The queue is bounded and drops the oldest, so "queued" is not yet "heard". A latch
			// that stayed set for an evicted warning would spend the one warning per session on
			// nobody - the bug this fix exists to remove, moved one step later.
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Backgrounded hot swap: the warning is queued and the latch is set.
			manager.save(sourceFile)
			advanceUntilIdle()

			// Still backgrounded, the reload now crashes on every redeploy. Four newer notices
			// fill the queue and push the warning out of it.
			repeat(NOTICE_QUEUE_DEPTH) { generation ->
				connections.report(TargetReport.Crashed(generation.toLong(), "NPE in onCreate"))
				advanceUntilIdle()
			}

			val notices = recordNotices(manager)
			advanceUntilIdle()
			assertThat(notices).doesNotContain(QuickBuildNotice.STALE_COMPONENT_HELPERS)
			assertThat(notices).hasSize(NOTICE_QUEUE_DEPTH)

			// The eviction re-armed the latch, so the next hot swap warns.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(notices.last()).isEqualTo(QuickBuildNotice.STALE_COMPONENT_HELPERS)
		}

	@Test
	fun `a repeating aapt2 rejection tells the user it is now blocking every save`() =
		runTest {
			// The relink links the whole res/ tree from disk, so an unlinkable resource fails
			// every later build - including a pure-code save, whose own edit is fine. The status
			// surface only ever shows the diagnostics, never that they are now stopping
			// everything, and the case no edit can fix (a reference missing from the proxy app
			// build's resource snapshot) then looks like the feature simply died.
			val strings = File(projectRoot, "app/src/main/res/values/strings.xml")
			val aapt2Error =
				BuildOutcome.CompileError(
					listOf(
						BuildDiagnostic(
							BuildDiagnostic.Severity.ERROR,
							"resource style/Theme.Library not found",
							strings.path,
							4,
							9,
						),
					),
				)
			scriptedOutcomes += aapt2Error
			scriptedOutcomes += aapt2Error
			scriptedOutcomes += aapt2Error
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			strings.parentFile!!.mkdirs()
			strings.writeText("<resources/>")
			manager.save(strings)
			advanceUntilIdle()
			// One rejection is an ordinary compile error; the user is looking at the file.
			assertThat(notices).isEmpty()

			// A pure-code save drags the still-pending resource back in and re-fails identically.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed.last().route).isEqualTo(BuildRoute.CodeAndResources)
			assertThat(notices).containsExactly(QuickBuildNotice.RELINK_STUCK)

			// Once per streak: the message asks the user to act, so repeating it on every save
			// would train them to dismiss it. Nothing escalated - the session stays live at the
			// old generation with the diagnostics on screen, never-stale intact.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(notices).containsExactly(QuickBuildNotice.RELINK_STUCK)
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Ready(0, SessionFailure.CompileError(aapt2Error.diagnostics)),
				)
		}

	@Test
	fun `a restarting deploy does not warn about stale helpers - the process was relaunched`() =
		runTest {
			// The restart closure hit, so the whole process came back on the new payload.
			// Warning here would be a lie about the one path that has no gap.
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			scriptedOutcomes += BuildOutcome.Success(1, 5, restarted = true)

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Deployed(1, 5, restarted = true))
			assertThat(notices).isEmpty()
		}

	@Test
	fun `a resource-only deploy does not warn about stale helpers - no class was recompiled`() =
		runTest {
			// Nothing a component calls moved, so there is no stale copy to warn about.
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			val strings =
				File(projectRoot, "app/src/main/res/values/strings.xml").apply {
					parentFile!!.mkdirs()
					writeText("<resources/>")
				}

			manager.save(strings)
			advanceUntilIdle()

			assertThat(executed.single().route).isEqualTo(BuildRoute.ResourcesOnly)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(notices).isEmpty()
		}

	@Test
	fun `an app with no restart-sensitive component never warns about stale helpers`() =
		runTest {
			// Activities and receivers are outside the restart closure because recreate and
			// per-delivery instantiation already refresh them - nothing survives to go stale.
			provisionOutcome = {
				provisionWithComponents(
					ComponentInfo(ComponentKind.ACTIVITY, "com.example.MainActivity", launcher = true),
					ComponentInfo(ComponentKind.RECEIVER, "com.example.BootReceiver"),
				)
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			assertThat(notices).isEmpty()
		}

	@Test
	fun `the stale-helper warning is owed again after a session restart`() =
		runTest {
			// Once per SESSION, not once per process: the next session may be a different
			// project, and the user has to hear it there too.
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(notices).containsExactly(QuickBuildNotice.STALE_COMPONENT_HELPERS)

			manager.restartSession()
			advanceUntilIdle()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(notices)
				.containsExactly(
					QuickBuildNotice.STALE_COMPONENT_HELPERS,
					QuickBuildNotice.STALE_COMPONENT_HELPERS,
				)
		}

	@Test
	fun `only the launcher activity is the relaunch target, not the first activity declared`() =
		runTest {
			// The manifest order is arbitrary, so picking the first ACTIVITY would foreground a
			// splash/settings screen instead of the app's entry point. Only the MAIN/LAUNCHER
			// one is a legitimate explicit target.
			provisionOutcome = {
				provisionWithComponents(
					ComponentInfo(ComponentKind.ACTIVITY, "com.example.Splash", proxyClass = "com.example.QbSplash"),
					ComponentInfo(
						ComponentKind.ACTIVITY,
						"com.example.Main",
						proxyClass = "com.example.QbMain",
						launcher = true,
					),
				)
			}
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(launches).containsExactly("com.example.quickbuild" to "com.example.QbMain")
		}

	@Test
	fun `a refused foreground request is best-effort - no error surfaces and the session stays Ready`() =
		runTest {
			// The default launcher refuses (the app wires an intent-based one), and a refusal is
			// not a build failure: the deploy already landed and the user can open the app
			// themselves. Surfacing it would flash red for something that worked.
			launchResult = false
			val manager = createManager()
			val notices = recordNotices(manager)

			manager.onQuickBuildTapped()
			advanceUntilIdle()

			assertThat(launches).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.UpToDate(0, null))
			assertThat(userMessages).isEmpty()
			assertThat(notices).isEmpty()
		}

	@Test
	fun `a second not-connected deploy tells the user the proxy app will not stay up`() =
		runTest {
			// A baseline that crashes at startup: the payload compiles and dexes fine and then
			// has nowhere to land, and the deploy failure's own "relaunch to reconnect" advice
			// just restarts the crash. Only a fresh proxy app build clears it, so the session
			// has to say so rather than let the user loop.
			val notConnected = BuildOutcome.DeployFailure("proxy app is not connected", proxyAppNotConnected = true)
			scriptedOutcomes += notConnected
			scriptedOutcomes += notConnected
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			manager.save(sourceFile)
			advanceUntilIdle()
			// One failure is indistinguishable from an app the user happened to have closed.
			assertThat(notices).isEmpty()

			manager.save(sourceFile)
			advanceUntilIdle()

			assertThat(executed).hasSize(2)
			assertThat(notices).containsExactly(QuickBuildNotice.PROXY_APP_WONT_STAY_UP)
			// Nothing escalated: the session stays live at the generation the app last ran.
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Ready(0, SessionFailure.DeployError("proxy app is not connected")),
				)
			assertThat(proxyAppRebuildCount).isEqualTo(0)
		}

	@Test
	fun `a failed daemon respawn surfaces the error and parks Degraded instead of auto-retrying`() =
		runTest {
			// Auto-retrying a hard-broken daemon would spin forever, so the session stays
			// Degraded and waits for an explicit tap or a session restart.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			daemon.startReply = DaemonReply.Failed("daemon JVM would not start")
			daemon.die(exitCode = 137)
			advanceUntilIdle()

			// restartFailed is what makes the status stop claiming a restart is under way; the
			// state is otherwise unchanged, and nothing is scheduled.
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Degraded(0, restartFailed = true))
			assertThat(QuickBuildStatus.from(manager.state.value))
				.isEqualTo(QuickBuildStatus.Reconnecting(0, restartFailed = true))
			assertThat(userMessages)
				.containsExactly(QuickBuildMessage.DaemonRestartFailed("daemon JVM would not start"))
			assertThat(daemon.isRunning).isFalse()
			// One respawn attempt, not a retry loop.
			assertThat(daemon.startConfigs).hasSize(2)

			// A save while Degraded must not silently re-arm the respawn - still true, and this is
			// the assertion that says so: no third daemon start.
			//
			// What the save DOES do is get narrated. The watcher never stopped, so the save really
			// does start a quick build, and Degraded must follow that build's whole lifecycle -
			// dropping it would leave the status on "restarting the compiler" while save after save
			// produced nothing the user could see.
			//
			// It lands as Deployed here because this harness's executor is scripted independently of
			// the daemon fake, so the build succeeds against a daemon that is down. On a device it
			// fails with daemonDied, which arrives as DaemonDied from Building and parks back in
			// Degraded with one more respawn - one per user save, which is not the auto-retry spin
			// this test guards against.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(2)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Deployed(1, buildDurationMillis = 5))
		}

	@Test
	fun `a respawned daemon that dies during its own start does not leave the session Ready`() =
		runTest {
			// The second-death race, which cannot be driven by hand on a device: the fresh child
			// dies in the window between start() returning Ok and DaemonRespawned landing. The
			// death arrives while the session is still Degraded, where it schedules nothing by
			// design - so a DaemonRespawned taken at face value would announce a live compiler
			// that is already gone, and the outage would stay hidden until the next save.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			daemon.onStart = {
				daemon.onStart = {}
				// Fire the death, then yield so its dispatch lands before start returns - the
				// ordering a real spawn produces, and the one the bug needs.
				daemon.die(exitCode = 1)
				yield()
			}
			daemon.die(exitCode = 137)
			advanceUntilIdle()

			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Degraded(0, restartFailed = true))
			assertThat(daemon.isRunning).isFalse()
			// One respawn attempt for the first death and one for the second-death report is not
			// what happens: Degraded schedules nothing on DaemonDied, so the count stays at the
			// provision start plus the single respawn. That is the no-spin property.
			assertThat(daemon.startConfigs).hasSize(2)

			// And the gesture the status now names really does retry.
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(3)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a stop that lost the race to the build's own completion reports no cancellation`() =
		runTest {
			// The stop reached the reducer while the build was still in flight, but the build
			// finished before the effect ran. Nothing was cancelled, so saying "cancelled"
			// would be a lie about a build that actually landed.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			val gate = CompletableDeferred<Unit>()
			executionGate = gate

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Building(0))

			// The stop is queued first; the build's completion runs between it and its effect.
			manager.onCancelRequested()
			gate.complete(Unit)
			advanceUntilIdle()

			assertThat(executed).hasSize(1)
			assertThat(notices).isEmpty()
		}

	@Test
	fun `a tap that lost the race to its build's completion is still answered - by the switch`() =
		runTest {
			// The reducer decided to hang the ask on the in-flight build, but that build
			// finished before the effect ran. Falling back to a real request is what keeps the
			// tap from vanishing - and with nothing pending, that request now answers the tap
			// by switching, instead of paying a forced NoOp rebuild of identical bytes.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size
			val gate = CompletableDeferred<Unit>()
			executionGate = gate

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Building(0))

			manager.onQuickBuildTapped()
			gate.complete(Unit)
			advanceUntilIdle()

			// No second build ran, and the tap got its answer exactly once.
			assertThat(executed).hasSize(1)
			assertThat(launches).hasSize(launchesBefore + 1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	@Test
	fun `a tap during the warm compile with a pending save waits for that build's deploy`() =
		runTest {
			// Unlike a tap with nothing pending, this one has a real build to wait for, so
			// foregrounding now would put the user in front of the OLD code and then reload it
			// under them. The switch belongs on the deploy.
			val manager = createManager()
			val gate = CompletableDeferred<Unit>()
			warmCompileGate = gate
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			manager.save(sourceFile)
			advanceUntilIdle()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			// Still queued behind the warm compile, and the user is still in the editor.
			assertThat(executed).isEmpty()
			assertThat(launches).hasSize(launchesBefore)

			gate.complete(Unit)
			advanceUntilIdle()

			val request = executed.single()
			// The tap no longer forces: the pending save routes the build like any other.
			assertThat(request.forced).isFalse()
			assertThat((request.changes as ChangedFiles.Known).files).containsExactly(sourceFile)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
			// Exactly once, when the deploy landed.
			assertThat(launches).hasSize(launchesBefore + 1)
		}

	@Test
	fun `a stop with no Gradle build left to cancel still reports it and tears the session down`() =
		runTest {
			// The Gradle build had already finished and the session is in its install or
			// daemon-spawn tail. The user pressed stop and the session does stop, so the
			// report is owed whether or not the cancellation reached Gradle.
			provisionGate = CompletableDeferred()
			proxyAppBuildCancelResult = false
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			assertThat(manager.state.value)
				.isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))

			manager.onCancelRequested()
			advanceUntilIdle()

			assertThat(proxyAppBuildCancelCount).isEqualTo(1)
			assertThat(notices).containsExactly(QuickBuildNotice.BUILD_CANCELLED)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			// The provision that outlived the stop must not install itself behind an Idle UI.
			provisionGate!!.complete(Unit)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(daemon.isRunning).isFalse()
		}

	@Test
	fun `an unconfirmed reinstall parks at the generation the app runs, not the allocator's`() =
		runTest {
			// The two genuinely differ: the allocator persists across sessions and burns
			// numbers on builds that never deployed, while the park has to name what the proxy
			// app is actually running so the banner does not claim a generation nobody has.
			scriptedOutcomes += BuildOutcome.Success(2, 5)
			proxyAppRebuildOutcome = {
				ProxyAppRebuildOutcome.InstallNotConfirmed(QuickBuildMessage.Literal("install was not confirmed"))
			}
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(2, 5))
			// The allocator never moved, so it and the deploy tally now disagree - which is the
			// whole point of reading the tally here.
			assertThat(store.value).isNull()

			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						2,
						awaitingRetry = true,
					),
				)
		}

	@Test
	fun `a messageless throw during the rebuild's re-baseline surfaces the exception class name`() =
		runTest {
			// A bare `checkNotNull` / NPE carries no message; surfacing an empty string would
			// flash a blank banner and tell the user nothing at all.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			executorFactoryError = { IllegalStateException() }
			manager.save(gradleFile)
			advanceUntilIdle()

			assertThat(userMessages)
				.contains(QuickBuildMessage.Literal("java.lang.IllegalStateException"))
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
		}

	@Test
	fun `the proxy app disconnecting is not a crash and triggers no catch-up build`() =
		runTest {
			// The user swiped the app away, or it was killed for memory. Nothing is running to
			// be behind, so treating the disconnect as a stale reconnect would rebuild and
			// redeploy into thin air, and treating the report as a crash would flash red.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)

			manager.save(sourceFile)
			advanceUntilIdle()
			connections.onConnected(connectedAt(1))
			advanceUntilIdle()
			assertThat(executed).hasSize(1)

			connections.onDisconnected()
			advanceUntilIdle()

			assertThat(executed).hasSize(1)
			assertThat(notices).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}

	@Test
	fun `a proxy app rebuild that outlives a session restart never re-baselines the dead session`() =
		runTest {
			// The Gradle build runs out of process, so "Restart session" cannot un-run it. Its
			// late success must not restart a daemon or move a session that is already gone.
			proxyAppRebuildGate = CompletableDeferred()
			proxyAppRebuildSurvivesCancel = true
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(factoryProxyApps).hasSize(1)

			manager.save(gradleFile)
			advanceUntilIdle()
			assertThat(manager.state.value)
				.isEqualTo(
					QuickBuildSessionState.Provisioning(
						rebaselineReason = InvalidationReason.GRADLE_CONFIG_CHANGED,
					),
				)

			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			proxyAppRebuildGate!!.complete(Unit)
			advanceUntilIdle()

			// Discarded before the daemon restart and before any executor was rebuilt.
			assertThat(daemon.startConfigs).hasSize(1)
			assertThat(factoryProxyApps).hasSize(1)
			assertThat(daemon.isRunning).isFalse()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(executed).isEmpty()
		}

	@Test
	fun `a session restart during the daemon start stops the daemon that start brings up`() =
		runTest {
			// Cancellation is cooperative, so a daemon spawn already under way still finishes
			// and leaves a JVM holding ~0.5GB behind an Idle UI. Nothing else owns it.
			val startGate = CompletableDeferred<Unit>()
			daemon.startGate = startGate
			daemon.startSurvivesCancel = true
			val manager = createManager()

			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)
			assertThat(daemon.isRunning).isFalse()

			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			// The start finally completes into a session that no longer exists.
			startGate.complete(Unit)
			advanceUntilIdle()

			assertThat(daemon.isRunning).isFalse()
			assertThat(daemon.startConfigs).hasSize(1)
			assertThat(connections.expectedPackage).isNull()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(warmCompiles).isEmpty()
		}

	@Test
	fun `a session restart racing a build in flight leaves nothing to report`() =
		runTest {
			// The restart lands between the build starting and its events being applied, so the
			// events arrive with no session behind them: no tally to advance, no status to push
			// to a proxy app this session no longer owns, and no hot-swap warning to give.
			provisionOutcome = { provisionWithComponents(syncService) }
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			deploy.statusCalls.clear()

			// Both are queued before either runs: the save starts a build, the restart tears
			// the session down while that build's events are still in the queue behind it.
			manager.save(sourceFile)
			manager.restartSession()
			advanceUntilIdle()

			assertThat(executed).hasSize(1)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
			assertThat(manager.status.value).isEqualTo(QuickBuildStatus.Hidden())
			assertThat(deploy.statusCalls).isEmpty()
			assertThat(notices).isEmpty()
		}

	@Test
	fun `a restart landing on the heels of provisioning skips the background warm compile`() =
		runTest {
			// The warm compile is launched, not run inline, precisely so a teardown queued
			// behind the provision wins: warming a daemon for a session nobody can use burns
			// 12-50s of CPU on a device that just asked for everything to stop.
			provisionGate = CompletableDeferred()
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			provisionGate!!.complete(Unit)
			manager.restartSession()
			advanceUntilIdle()

			assertThat(warmCompiles).isEmpty()
			assertThat(executed).isEmpty()
			assertThat(daemon.isRunning).isFalse()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a tap withdrawn by a restart never reaches the orchestrator`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size

			// The user taps, then immediately long-presses Restart session.
			manager.onQuickBuildTapped()
			manager.restartSession()
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(launches).hasSize(launchesBefore)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a tap withdrawn by a restart mid-build does not promote the abandoned build`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val launchesBefore = launches.size
			val gate = CompletableDeferred<Unit>()
			executionGate = gate

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Building(0))

			manager.onQuickBuildTapped()
			manager.restartSession()
			advanceUntilIdle()

			// Neither a second build for the tap nor a foregrounding of a torn-down session.
			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(executed).hasSize(1)
			assertThat(launches).hasSize(launchesBefore)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a stop withdrawn by a restart reports no cancellation - the restart already said it`() =
		runTest {
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val notices = recordNotices(manager)
			val gate = CompletableDeferred<Unit>()
			executionGate = gate

			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Building(0))

			manager.onCancelRequested()
			manager.restartSession()
			advanceUntilIdle()

			gate.complete(Unit)
			advanceUntilIdle()
			assertThat(notices).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a Standard Run finishing after a session restart refreshes nothing`() =
		runTest {
			// The Run button's build-finished hook fires whether or not Quick Build is still
			// alive; with the session gone there is no baseline to mark dirty.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()

			manager.onStandardRunCompleted()
			manager.restartSession()
			advanceUntilIdle()

			assertThat(proxyAppRebuildCount).isEqualTo(0)
			assertThat(executed).isEmpty()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			// And the next tap still provisions a healthy session.
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))
		}

	@Test
	fun `a daemon death answered by a restart never respawns the daemon`() =
		runTest {
			// The user hit Restart session because the daemon died. Respawning one for the dead
			// session would leave a JVM up with nothing to compile for.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(daemon.startConfigs).hasSize(1)

			daemon.die(exitCode = 137)
			manager.restartSession()
			advanceUntilIdle()

			assertThat(daemon.startConfigs).hasSize(1)
			assertThat(daemon.isRunning).isFalse()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a batch already in flight when the watcher stopped builds nothing`() =
		runTest {
			// inotify cannot unwind a callback that is mid-delivery, so a batch can reach the
			// manager after the teardown that stopped its watcher.
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			val stoppedWatcher = watcher!!

			manager.restartSession()
			advanceUntilIdle()

			stoppedWatcher.emitRacingStop(setOf(sourceFile))
			advanceUntilIdle()

			assertThat(executed).isEmpty()
			assertThat(proxyAppRebuildCount).isEqualTo(0)
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())
		}

	@Test
	fun `a teardown finishing after a new session went live keeps that session's scratch tree`() =
		runTest {
			// The teardown's tree removal waits on the daemon shutdown, which can outlast a
			// re-tap. Removing then would delete the live session's compile outputs out from
			// under it - the tree belongs to whoever is live now, not to whoever queued it.
			val scratchRoot = FakePaths(projectRoot).projectScratchRoot
			val tree = QuickBuildScratch(scratchRoot).treeFor(projectRoot)
			val manager = createManager()
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(tree.isDirectory).isTrue()

			// Hold the teardown inside the daemon shutdown it waits on.
			val shutdownGate = CompletableDeferred<Unit>()
			daemon.shutdownGate = shutdownGate
			manager.restartSession()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Idle())

			// A new session for the SAME project goes live while that teardown is parked.
			manager.onQuickBuildTapped()
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Ready(0))

			shutdownGate.complete(Unit)
			advanceUntilIdle()

			assertThat(tree.isDirectory).isTrue()
			// And the new session is still usable, not compiling into a deleted tree.
			manager.save(sourceFile)
			advanceUntilIdle()
			assertThat(manager.state.value).isEqualTo(QuickBuildSessionState.Deployed(1, 5))
		}
}
