package org.appdevforall.cotg.quickbuild.service

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.data.AndroidProjectWatcher
import org.appdevforall.cotg.quickbuild.data.FileGenerationStore
import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.QuickBuildNotice
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.SessionEffect
import org.appdevforall.cotg.quickbuild.domain.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.SessionReducer
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.WatcherBatchReconciler
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The shell around the domain session machine: owns the [SessionReducer] and the live
 * session, and turns reducer effects into real work such as provisioning, daemon respawn,
 * and Gradle proxy app rebuilds.
 *
 * Everything stateful runs on [dispatcher], which must be single-threaded because the
 * orchestrator's event-ordering guarantee requires it. Effects are launched rather than
 * run inline so a reducer dispatch never re-enters itself, and the single thread keeps
 * the launched work ordered. Change events arrive from the on-device [ProjectWatcher] -
 * file changes from any source, not just the editor - and are hopped onto [dispatcher]
 * before reaching the orchestrator.
 */
class QuickBuildSessionManager(
	private val daemon: QuickBuildDaemon,
	private val deploy: DeploySender,
	private val provisioner: QuickBuildProvisioner,
	private val connections: ProxyAppConnections,
	private val paths: QuickBuildPaths,
	/** Gates eager prebuild on project history and records first use. */
	private val historyStore: QuickBuildHistoryStore,
	dispatcher: CoroutineDispatcher,
	private val generationStoreFactory: (File) -> GenerationStore = {
		FileGenerationStore.forProject(it)
	},
	private val executorFactory: ExecutorFactory? = null,
	/** Direct hook for provisioning/daemon error text; the app UI collects [userMessages]. */
	private val onUserMessage: (String) -> Unit = {},
	/** Test seam: the default builds the real on-device [AndroidProjectWatcher]. */
	private val watcherFactory: WatcherFactory =
		WatcherFactory { roots, files, filter, scope ->
			AndroidProjectWatcher(roots, files, filter, scope)
		},
	/** Run-statistics port; the app wires an analytics sink. */
	private val metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
	/**
	 * Relaunches the proxy app after a restart deploy; the app wires an intent-based
	 * implementation. The default refuses, which the executor surfaces as a deploy failure
	 * telling the user to open the app, rather than claiming a relaunch it cannot do.
	 */
	private val launcher: ProxyAppLauncher = ProxyAppLauncher { _, _ -> false },
	/**
	 * Bench seam gating the background warm compile fired when provisioning succeeds, so a
	 * warm-compile-off arm of an A/B run needs a flag file rather than a rebuild. Read at
	 * effect time, per session; always true outside bench runs. The daemon-respawn re-warm
	 * is deliberately not gated, since it repairs a dead daemon rather than a cold one.
	 */
	private val warmCompileEnabled: () -> Boolean = { true },
	/**
	 * Monotonic clock shared by the e2e timeline's orchestrator and executor stamps, so
	 * they are comparable (see [org.appdevforall.cotg.quickbuild.domain.E2eTimeline]).
	 *
	 * Defaults to `System.currentTimeMillis` so this module's unit tests run without an
	 * Android runtime; the app's Koin graph injects `SystemClock.elapsedRealtime`.
	 */
	private val nowMillis: () -> Long = System::currentTimeMillis,
	/**
	 * Per-project scratch trees on app-private storage, keeping intermediates off FUSE.
	 * Overridable so tests can shrink or inflate the disk-space floor.
	 */
	private val scratch: QuickBuildScratch = QuickBuildScratch(paths.projectScratchRoot),
) {
	/** Builds the project watcher for a live session; overridden with a fake in tests. */
	fun interface WatcherFactory {
		fun create(
			roots: List<File>,
			files: List<File>,
			filter: WatchFilter,
			scope: CoroutineScope,
		): ProjectWatcher
	}

	/** Test seam: build the executor for a freshly provisioned session. */
	fun interface ExecutorFactory {
		fun create(
			proxyApp: ProxyAppInfo,
			layout: QuickBuildProjectLayout,
			tracker: GenerationTracker,
		): LiveReloadExecutor
	}

	private val scope = CoroutineScope(SupervisorJob() + dispatcher)
	private val reducer = SessionReducer()

	private val _state =
		MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Idle)

	val state: StateFlow<QuickBuildSessionState> = _state

	/**
	 * What the toolbar shows. Derived from [state] and never set imperatively, so a banner
	 * cannot get stuck out of step with the session.
	 */
	val status: StateFlow<QuickBuildStatus> =
		_state
			.map(QuickBuildStatus.Companion::from)
			.stateIn(scope, SharingStarted.Eagerly, QuickBuildStatus.Hidden)

	/**
	 * Provisioning and daemon failure text for the host UI to flash. Carries the same
	 * messages as the injected [onUserMessage] callback; this flow is what the editor
	 * activity collects, since the Koin graph cannot reach an Activity's flash helpers.
	 */
	val userMessages: SharedFlow<String>
		get() = _userMessages

	private val _userMessages =
		MutableSharedFlow<String>(
			extraBufferCapacity = 8,
			onBufferOverflow = BufferOverflow.DROP_OLDEST,
		)

	/**
	 * Neutral notices for the host UI: things that are not failures and must not be
	 * flashed as errors.
	 *
	 * Separate from [userMessages] because that flow is the error channel, and a
	 * cancellation the user asked for should not read as a red banner.
	 */
	val notices: SharedFlow<QuickBuildNotice>
		get() = _notices

	private val _notices =
		MutableSharedFlow<QuickBuildNotice>(
			extraBufferCapacity = 4,
			onBufferOverflow = BufferOverflow.DROP_OLDEST,
		)

	private var live: LiveSession? = null

	/**
	 * Bumped by every [teardown], so in-flight work can tell it was outlived.
	 *
	 * Provisioning and rebuild work captures the epoch at launch and discards its result
	 * when they differ: a provision completing after "Restart session" must never install
	 * itself as a zombie session with a live watcher and daemon behind an Idle UI. Only
	 * touched on [dispatcher].
	 */
	private var sessionEpoch = 0L

	/** The in-flight provision, prebuild, or proxy app rebuild; cancelled by [teardown]. */
	private var sessionWork: Job? = null

	/** Owns the daemon lifecycle protocol; see [QuickBuildDaemonController]. */
	private val daemonController = QuickBuildDaemonController(daemon, scratch, paths)

	/** Assembles live sessions and the rebuild pieces derived from a proxy app baseline. */
	private val sessionFactory =
		LiveSessionFactory(
			daemon = daemon,
			deploy = deploy,
			scratch = scratch,
			launcher = launcher,
			metrics = metrics,
			nowMillis = nowMillis,
			executorFactory = executorFactory,
			watcherFactory = watcherFactory,
			scope = scope,
			onOrchestratorEvent = ::onOrchestratorEvent,
		)

	/**
	 * Runs the Gradle proxy app builds and returns verdicts. This manager keeps the epoch
	 * guards, installs sessions, and dispatches.
	 */
	private val buildRunner =
		ProxyAppBuildRunner(
			provisioner = provisioner,
			daemonController = daemonController,
			connections = connections,
			scratch = scratch,
			sessionFactory = sessionFactory,
			generationStoreFactory = generationStoreFactory,
			metrics = metrics,
		)

	/** Translates orchestrator facts into session events; see [onOrchestratorEvent]. */
	private val eventRouter = OrchestratorEventRouter(metrics)

	init {
		daemon.setDeathListener { exitCode ->
			log.warn("Quick-build daemon death observed (exit {})", exitCode)
			scope.launch { dispatch(SessionEvent.DaemonDied) }
		}
		scope.launch {
			connections.reports.collect { report ->
				if (report is TargetReport.Crashed) {
					dispatch(SessionEvent.ProxyAppCrashed(report.stackSummary))
				}
			}
		}
		scope.launch {
			// Reconnect catch-up: a relaunched proxy app reports the generation it
			// booted, and one below what this session deployed means its persisted
			// payload was lost or stale. Force a rebuild of current sources at a fresh
			// generation, the same path an explicit tap takes; otherwise the app runs
			// old code silently until the next edit.
			connections.target.collect { target ->
				val session = live ?: return@collect
				if (target != null && target.runningGeneration < session.lastDeployedGeneration) {
					log.info(
						"Proxy app reconnected at generation {} but the session deployed {}; forcing a catch-up build",
						target.runningGeneration,
						session.lastDeployedGeneration,
					)
					// Not user-initiated: nobody tapped anything, and saying otherwise
					// would foreground the proxy app off a stale reconnect.
					session.orchestrator.onLiveReloadRequested(userInitiated = false)
				}
			}
		}
		scope.launch {
			// Retries a low-memory teardown the controller deferred while a build was in
			// flight, the moment that build's own transition lands (success, failure, or
			// a real daemon death all move the state away from Building).
			_state.collect {
				daemonController.shrinkIfPending(buildInFlight = it is QuickBuildSessionState.Building)
			}
		}
		scope.launch {
			// Stale-tree sweep. Nothing can be live yet - this manager is the process's
			// only session owner and no tap has dispatched - so every tree under the
			// scratch root belongs to a dead session or a deleted project. Runs on
			// dispatcher, strictly before any tap.
			scratch.sweep(liveProjectRoots = emptyList())
		}
	}

	/**
	 * Handles the Quick Build tap: starts a session from Idle, forces a build when live,
	 * and queues onto an in-flight prebuild.
	 *
	 * The tap must dispatch before the history write, never after. A tap sequenced behind
	 * a disk write can be reduced after `PrebuildFinished` already settled the session
	 * back to Idle, and a write that throws would kill this coroutine before the dispatch,
	 * losing the tap on the primary control. Nothing depends on the other ordering, since
	 * prebuild does not gate on this history.
	 */
	fun onQuickBuildTapped() {
		scope.launch {
			dispatch(SessionEvent.QuickBuildTapped)
			try {
				historyStore.setHasUsedQuickBuild(true)
			} catch (e: Throwable) {
				log.warn("Could not record Quick Build history for this project", e)
			}
		}
	}

	/**
	 * Handles the stop button, the same toolbar button showing its stop icon.
	 *
	 * Safe to call from any state: the reducer only acts on states that own a build the
	 * user asked for, so a tap that raced the build's completion does nothing.
	 */
	fun onCancelRequested() {
		scope.launch { dispatch(SessionEvent.CancelRequested) }
	}

	/**
	 * Retries a reinstall whose confirm dialog never appeared, now that CoGo is
	 * foreground again. Call from the editor's onResume.
	 *
	 * A rebuild reinstall that ran while CoGo was backgrounded shows the user nothing:
	 * Android defers the PENDING_USER_ACTION broadcast until the app is foreground, and
	 * the dialog-owning subscriber is lifecycle-bound, so the deferred delivery can land
	 * before it re-registers. This is a no-op in every state except an Invalidated session
	 * awaiting retry, and the reducer bounds the auto-retries.
	 */
	fun onHostForegrounded() {
		scope.launch { dispatch(SessionEvent.HostForegrounded) }
	}

	/**
	 * Runs the proxy app build in the background so the first tap pays only install and
	 * bind. Call at project open, after the normal Gradle sync completes.
	 *
	 * Installs nothing, and is a no-op unless Idle; a tap landing mid-warm queues and
	 * provisions when the warm build finishes. Deliberately not gated on project history:
	 * gating would save battery on projects that never use Quick Build, at the cost of
	 * making the first tap on every new project pay a whole cold proxy app build -
	 * about 97 s on an a56 for a small app [measured on a56].
	 */
	fun prebuild() {
		scope.launch { dispatch(SessionEvent.PrebuildRequested) }
	}

	/**
	 * Moves a live session back onto current disk after a Standard Run's Gradle build, so
	 * the next quick build is not stale. Call from the Run button's build-finished hook.
	 *
	 * A build that clobbered the proxy app artifacts forces a full rebuild; anything less
	 * only marks the baseline dirty. No-op with no live session.
	 */
	fun onStandardRunCompleted() {
		scope.launch { dispatch(SessionEvent.ExternalBuildCompleted) }
	}

	/**
	 * Tears down the live session and daemon and returns to Idle from any state, so the
	 * next tap re-provisions from scratch.
	 *
	 * The escape hatch for a daemon or proxy app stuck past what a quick build or a
	 * rebuild can recover.
	 */
	fun restartSession() {
		scope.launch { dispatch(SessionEvent.SessionRestartRequested) }
	}

	/**
	 * Gives the compile daemon's memory back under system pressure. The host forwards
	 * `ComponentCallbacks2.onTrimMemory`'s level here.
	 *
	 * The daemon is a separate child JVM whose heap is pure overhead between builds, so it
	 * is the first thing worth releasing. Which levels tear it down, and why a build in
	 * flight defers, is [QuickBuildDaemonController.onTrimMemory].
	 */
	fun onTrimMemory(level: Int) {
		scope.launch {
			daemonController.onTrimMemory(
				level,
				buildInFlight = _state.value is QuickBuildSessionState.Building,
			)
		}
	}

	/**
	 * Hands one coalesced batch of watcher changes to the orchestrator, which picks the
	 * route and handles any in-flight build.
	 *
	 * Reconciling modified against removed is domain logic in [WatcherBatchReconciler];
	 * this shell only supplies the `File.isFile` probe.
	 */
	private fun onWatcherBatch(batch: ChangedFiles.Known) {
		val reconciled = WatcherBatchReconciler.reconcile(batch, File::isFile)
		if (reconciled.isEmpty) return
		scope.launch {
			live?.orchestrator?.onFilesChanged(reconciled)
		}
	}

	/** Reduces one event into the new state and runs its effects. On [dispatcher] only. */
	private suspend fun dispatch(event: SessionEvent) {
		val transition = reducer.reduce(_state.value, event)
		if (transition.state != _state.value) {
			log.info("Quick-build session: {} -> {} on {}", _state.value, transition.state, event)
		}
		_state.value = transition.state
		transition.effects.forEach(::runEffect)
	}

	/** Turns one reducer effect into real work, launched so a dispatch never re-enters itself. */
	private fun runEffect(effect: SessionEffect) {
		when (effect) {
			SessionEffect.StartProvisioning -> {
				val epoch = sessionEpoch
				sessionWork = scope.launch { provision(epoch) }
			}

			SessionEffect.StartProxyAppPrebuild -> {
				sessionWork = scope.launch { runPrebuild() }
			}

			is SessionEffect.TriggerLiveReload -> {
				scope.launch { triggerLiveReload(effect.userInitiated) }
			}

			SessionEffect.MarkBuildUserInitiated -> {
				scope.launch {
					val orchestrator = live?.orchestrator ?: return@launch
					// The build can finish between the reducer's decision and this
					// effect; fall back to a real request rather than let the tap
					// vanish.
					if (!orchestrator.markInFlightUserInitiated()) triggerLiveReload(userInitiated = true)
				}
			}

			SessionEffect.SwitchToProxyApp -> {
				switchToProxyApp()
			}

			SessionEffect.CancelLiveReload -> {
				scope.launch {
					// Only report a cancellation that really happened: a stop that lost the
					// race to the build's own completion cancelled nothing.
					if (live?.orchestrator?.onCancelRequested() == true) {
						surfaceNotice(QuickBuildNotice.BUILD_CANCELLED)
					}
				}
			}

			SessionEffect.CancelProxyAppBuild -> {
				// Emitted only from states where this session owns the device's single
				// Gradle slot; see QuickBuildProvisioner.cancelProxyAppBuild for why
				// issuing it otherwise would be dangerous.
				if (provisioner.cancelProxyAppBuild()) {
					log.info("Quick Build proxy app build cancelled by the user")
				} else {
					// The Gradle build had already finished and the session is in its
					// install or daemon-spawn tail. The TeardownSession effect that
					// follows still stops the session.
					log.info("No Quick Build proxy app build to cancel; tearing the session down instead")
				}
				surfaceNotice(QuickBuildNotice.BUILD_CANCELLED)
			}

			SessionEffect.StartWarmCompile -> {
				if (warmCompileEnabled()) {
					// live is assigned before ProvisioningSucceeded is dispatched, so
					// the orchestrator is always there to take this.
					scope.launch { live?.orchestrator?.onWarmCompileRequested() }
				} else {
					log.info("Background warm compile disabled (bench seam); session stays Ready unwarmed")
				}
			}

			SessionEffect.RunProxyAppRebuild -> {
				val epoch = sessionEpoch
				sessionWork = scope.launch { rebuildProxyApp(epoch) }
			}

			SessionEffect.RefreshBaseline -> {
				scope.launch { refreshBaseline() }
			}

			SessionEffect.RespawnDaemon -> {
				val epoch = daemonController.epochSnapshot()
				scope.launch { respawnDaemon(epoch) }
			}

			is SessionEffect.SurfaceProvisioningError -> {
				log.error("Quick-build provisioning failed: {}", effect.message)
				surfaceUserMessage(effect.message)
				teardown()
			}

			SessionEffect.TeardownSession -> {
				log.info("Quick-build session restarted by user request")
				teardown()
			}
		}
	}

	/** Asks the orchestrator for a build, and foregrounds the app when a tap has nothing to wait for. */
	private suspend fun triggerLiveReload(userInitiated: Boolean) {
		val orchestrator = live?.orchestrator ?: return
		val awaitsDeploy = orchestrator.onLiveReloadRequested(userInitiated)
		// A tap with nothing pending still costs a full recompile, relink and deploy,
		// because the runtime only accepts strictly newer generations. Answer the tap now
		// and let the redeploy land behind the user. The decision lives here rather than
		// in the reducer because only the orchestrator knows what is pending.
		if (userInitiated && !awaitsDeploy) switchToProxyApp()
	}

	/**
	 * Brings the proxy app to the foreground because the user asked.
	 *
	 * Best-effort: a refusal is logged rather than surfaced, since the build already
	 * landed and the user can open the app themselves.
	 */
	private fun switchToProxyApp() {
		val session = live ?: return
		// Same target the restart-deploy relaunch uses: the proxied launcher activity
		// when one carries MAIN/LAUNCHER, else null so the launcher falls back to the
		// default launch intent, which resolves an <activity-alias> launcher.
		val launcherActivity =
			session.proxyApp.components
				.firstOrNull { it.kind == ComponentKind.ACTIVITY && it.launcher }
				?.proxyClass
		if (!launcher.launch(session.proxyApp.proxyAppPackage, launcherActivity)) {
			log.warn("Could not bring the proxy app {} to the foreground", session.proxyApp.proxyAppPackage)
		}
	}

	/** Runs the eager warm-up build. Silent on failure, and always reports finished. */
	private suspend fun runPrebuild() {
		try {
			provisioner.prebuildProxyApp()
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.warn("Eager quick-build proxy app build failed; first tap will retry", e)
		}
		dispatch(SessionEvent.PrebuildFinished)
	}

	/** Provisions a session and installs it as [live], unless a teardown outlived it. */
	private suspend fun provision(startEpoch: Long) {
		when (val result = buildRunner.provision(superseded = { startEpoch != sessionEpoch })) {
			is ProxyAppBuildRunner.ProvisionResult.DiskSpaceShort -> {
				dispatch(SessionEvent.ProvisioningFailed(result.message))
			}

			is ProxyAppBuildRunner.ProvisionResult.Failed -> {
				dispatch(SessionEvent.ProvisioningFailed(result.message))
			}

			is ProxyAppBuildRunner.ProvisionResult.Superseded -> {
				// The user asked for a fresh start while the proxy app build ran, so a
				// late success must not resurrect and a late failure must not surface.
				log.info("Quick-build provisioning outlived a session restart; discarding")
			}

			is ProxyAppBuildRunner.ProvisionResult.SupersededDuringDaemonStart -> {
				// A restart raced the daemon start. The runner already undid its side;
				// stop the zombie daemon on a fresh coroutine, since this one is
				// already cancelled.
				log.info("Session restarted during daemon start; shutting down")
				daemonController.markIntentionalTransition()
				scope.launch { daemonController.shutdown() }
			}

			is ProxyAppBuildRunner.ProvisionResult.Succeeded -> {
				live = result.session
				// Build ids restart per session; give the sink its session boundary.
				report { metrics.onSessionStarted() }
				// The reload path is change-driven, not save-driven: any source of a
				// file change triggers it, including Termux, plugins and git.
				result.session.watcher.start(::onWatcherBatch)
				dispatch(SessionEvent.ProvisioningSucceeded(result.tracker.current))
			}
		}
	}

	/**
	 * Applies what [eventRouter] made of one orchestrator event. The orchestrator
	 * delivers synchronously on [dispatcher], so this hops to a launch.
	 */
	private fun onOrchestratorEvent(event: OrchestratorEvent) {
		scope.launch {
			val session = live
			val routing =
				eventRouter.route(
					event,
					lastDeployedGeneration = session?.lastDeployedGeneration ?: -1L,
					connectedGeneration = connections.target.value?.runningGeneration,
				)
			// Tally first, because the dispatched BuildSucceeded's consumers may read
			// it; events second; the best-effort building notification last.
			routing.newLastDeployedGeneration?.let { generation ->
				session?.lastDeployedGeneration = generation
			}
			routing.sessionEvents.forEach { dispatch(it) }
			routing.notifyBuildingAt?.let { generation ->
				// With no live session there is nothing truthful to say, so skip
				// silently like every other best-effort status push.
				if (session != null) notifyBuilding(generation)
			}
		}
	}

	/**
	 * Tells the proxy app a newer build is compiling while it keeps running
	 * [runningGeneration], so a slow build does not read as silence.
	 *
	 * Which generation that is comes from
	 * [OrchestratorEventRouter.Routing.notifyBuildingAt].
	 */
	private fun notifyBuilding(runningGeneration: Long) {
		try {
			deploy.notifyBuildStatus(BuildStatusJson.building(runningGeneration))
		} catch (e: Exception) {
			log.warn("Build-starting notification failed", e)
		}
	}

	/** Rebuilds the proxy app and moves the live session onto the new baseline. */
	private suspend fun rebuildProxyApp(startEpoch: Long) {
		val session = live ?: return
		// Captured before ProxyAppRebuildStarted moves the session to Provisioning, which
		// carries neither the reason nor the deployed generation: a retry that never gets
		// the Gradle slot has to park back exactly where it came from.
		val installRetryPark =
			(_state.value as? QuickBuildSessionState.Invalidated)
				?.takeIf { it.reason == InvalidationReason.INSTALL_NOT_CONFIRMED }
		session.orchestrator.onProxyAppRebuildStarted()
		dispatch(SessionEvent.ProxyAppRebuildStarted)

		val result =
			buildRunner.rebuildProxyApp(
				parkedRetry = installRetryPark != null,
				superseded = { startEpoch != sessionEpoch },
			)

		when (result) {
			is ProxyAppBuildRunner.ProxyAppRebuildResult.Superseded -> {
				// The session this rebuild was for is gone; do not poke its orchestrator.
				log.info("Quick-build proxy app rebuild outlived a session restart; discarding")
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.BuildSlotBusy -> {
				if (installRetryPark != null) {
					// The retry never got the Gradle slot, usually to CoGo's own project
					// sync that the invalidating gradle edit triggered. Park back
					// without spending the auto-retry budget, and say what is actually
					// happening: the park's own text tells the user to return to CoGo,
					// which is exactly what triggered this retry.
					log.info("Gradle slot busy; deferring the proxy app rebuild retry without spending an auto-retry")
					surfaceUserMessage(
						"Waiting for the current Gradle build to finish - your app still " +
							"needs a reinstall. Tap Quick Build to retry.",
					)
					dispatch(SessionEvent.ProxyAppRebuildDeferred(installRetryPark.deployedGeneration))
				} else {
					// A first rebuild has no park to return to and no budget to
					// protect, so report it like any other proxy-app-build failure.
					session.orchestrator.onProxyAppRebuildFailed()
					dispatch(SessionEvent.ProvisioningFailed("Proxy app rebuild failed"))
				}
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded -> {
				try {
					// Both delegates are built before adoptBaseline moves anything:
					// executorFor can throw on a null entryActivity, which the rebuild
					// contract does not rule out, and a throw must leave the old
					// baseline intact rather than escape with the session half-updated.
					val executorDelegate =
						sessionFactory.executorFor(result.proxyApp, result.layout, session.tracker)
					val annotationImpactDelegate =
						sessionFactory.annotationImpactFor(result.proxyApp, result.layout)
					session.adoptBaseline(
						result.proxyApp,
						result.layout,
						executorDelegate,
						annotationImpactDelegate,
					)
					dispatch(SessionEvent.ProvisioningSucceeded(session.tracker.current))
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: Throwable) {
					log.error("Re-baselining after a successful proxy app rebuild threw", e)
					session.orchestrator.onProxyAppRebuildFailed()
					dispatch(SessionEvent.ProvisioningFailed(e.message ?: e.javaClass.name))
				}
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.DaemonRestartFailed -> {
				log.error("Daemon restart after a proxy app rebuild failed: {}", result.message)
				session.orchestrator.onProxyAppRebuildFailed()
				dispatch(SessionEvent.ProvisioningFailed(result.message))
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.Failed -> {
				session.orchestrator.onProxyAppRebuildFailed()
				dispatch(SessionEvent.ProvisioningFailed(result.message))
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.InstallNotConfirmed -> {
				// The Gradle build was fine and only the reinstall confirmation is
				// missing, so park recoverable instead of dying to Idle; the message
				// already says how to recover. Deliberately not onProxyAppRebuildFailed:
				// the orchestrator keeps holding the absorbed batch, which suspends
				// quick builds while the daemon is down, and every held file is on disk
				// for the retry's Gradle build to absorb.
				log.warn("Proxy app rebuild reinstall not confirmed; awaiting a retry: {}", result.message)
				surfaceUserMessage(result.message)
				dispatch(
					SessionEvent.ProxyAppRebuildInstallNotConfirmed(
						session.lastDeployedGeneration.takeIf { it >= 0 } ?: session.tracker.current,
					),
				)
			}
		}
	}

	/**
	 * Brings the session back in step after an external full build.
	 *
	 * With the daemon's proxy app artifacts still on disk, marking the baseline dirty is
	 * enough: the next build recompiles everything and reinstalls nothing. If the external
	 * build removed them, only a full rebuild helps.
	 */
	private suspend fun refreshBaseline() {
		val session = live ?: return
		if (buildRunner.proxyAppArtifactsIntact(session.proxyApp)) {
			session.orchestrator.onBaselineUntrusted()
		} else {
			log.warn("Proxy app build artifacts missing after an external build; forcing a proxy app rebuild")
			dispatch(SessionEvent.InvalidationDetected(InvalidationReason.EXTERNAL_FULL_BUILD))
		}
	}

	/** Restarts a dead daemon and re-seeds the orchestrator against it. */
	private suspend fun respawnDaemon(startEpoch: Long) {
		val session = live ?: return
		when (val outcome = daemonController.respawn(session.layout, session.proxyApp, startEpoch)) {
			is QuickBuildDaemonController.RespawnOutcome.Respawned -> {
				dispatch(SessionEvent.DaemonRespawned)
				// A fresh daemon has no trustworthy incremental state. With nothing
				// pending this re-warms via a deploy-nothing warm compile, leaving the
				// proxy app on its current generation; with pending work it marks the
				// baseline dirty so the next build recompiles everything and deploys.
				session.orchestrator.onDaemonReplaced()
			}

			// The controller already stopped any zombie daemon per its
			// exactly-one-transition rule; the successor flow owns the lifecycle.
			is QuickBuildDaemonController.RespawnOutcome.Superseded -> {
				Unit
			}

			is QuickBuildDaemonController.RespawnOutcome.Failed -> {
				log.error("Daemon respawn failed: {}", outcome.message)
				// Stay Degraded and let the next explicit tap or session restart retry;
				// auto-retrying a hard-broken daemon would just spin.
				surfaceUserMessage("Quick Build daemon could not be restarted: ${outcome.message}")
			}
		}
	}

	/**
	 * Tears down the live session and any in-flight provision, prebuild, or rebuild.
	 *
	 * The epoch bump and cancel pair is what makes "Restart session" safe
	 * mid-provisioning: without it a provision resuming after the restart would set
	 * [live], start its watcher, and deploy invisibly behind an Idle UI, and the next
	 * tap's provision would overwrite [live] leaving that watcher orphaned. Cancelling
	 * [sessionWork] from within that coroutine is safe, since nothing suspends after the
	 * dispatch.
	 */
	private fun teardown() {
		sessionEpoch++
		daemonController.markIntentionalTransition()
		sessionWork?.cancel()
		sessionWork = null
		live?.watcher?.stop()
		val scratchOwner = live?.layout?.projectRoot
		live = null
		connections.endSession()
		scope.launch {
			daemonController.shutdown()
			// Only after the daemon is down, since it writes into this tree until
			// then. A teardown with no live session has nothing to remove, and the
			// init-time sweep reclaims any half-made tree. Skip when a new session for
			// the same project went live while shutdown suspended: the tree is that
			// session's now.
			scratchOwner
				?.takeIf { live?.layout?.projectRoot != it }
				?.let(scratch::remove)
		}
	}

	/** Sends failure text to both the injected callback and [userMessages]. */
	private fun surfaceUserMessage(message: String) {
		onUserMessage(message)
		_userMessages.tryEmit(message)
	}

	/** Sends a non-failure notice to [notices]. */
	private fun surfaceNotice(notice: QuickBuildNotice) {
		_notices.tryEmit(notice)
	}

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildSessionManager::class.java)
	}
}
