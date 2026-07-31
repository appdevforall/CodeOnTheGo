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
 * The shell around the domain session machine (plan 2.1): owns the [SessionReducer],
 * the per-session [LiveReloadOrchestrator] + [GenerationTracker], and turns reducer effects
 * into real work (provisioning, daemon respawn, Gradle proxy app rebuild).
 *
 * Threading: EVERYTHING stateful runs on [dispatcher], which MUST be single-threaded -
 * the orchestrator's event-ordering guarantee requires it (see its KDoc). Effects are
 * `scope.launch`ed rather than run inline so a reducer dispatch never re-enters itself;
 * the single thread keeps the launched work strictly ordered.
 *
 * Change events arrive from the on-device [ProjectWatcher] (file changes from ANY source -
 * editor, Termux, plugin, git pull), coalesced into batches, and are hopped onto
 * [dispatcher] before touching the orchestrator.
 */
class QuickBuildSessionManager(
	private val daemon: QuickBuildDaemon,
	private val deploy: DeploySender,
	private val provisioner: QuickBuildProvisioner,
	private val connections: ProxyAppConnections,
	private val paths: QuickBuildPaths,
	/** Gates eager prebuild on project history (plan P7) and records first use. */
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
	/** Run-statistics port (David's tracking ask); the app wires an analytics sink. */
	private val metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
	/**
	 * Relaunches the proxy app after a restart deploy; the app wires an intent-based
	 * implementation. The default refuses, which the executor surfaces as a deploy
	 * failure ("open the app manually") instead of claiming a relaunch it cannot do.
	 */
	private val launcher: ProxyAppLauncher = ProxyAppLauncher { _, _ -> false },
	/**
	 * Bench seam (ADFA-4128): gates the background warm compile fired when provisioning
	 * succeeds, so a warm-compile-off arm of an A/B run needs a flag file, not a rebuild. Read
	 * at effect time, per session. Always true outside bench runs; the daemon-respawn
	 * re-warm compile is deliberately NOT gated (it repairs a dead daemon, not a cold one).
	 */
	private val warmCompileEnabled: () -> Boolean = { true },
	/**
	 * Monotonic clock shared by the e2e timeline's t0 (orchestrator trigger stamp) and its
	 * t1-t3 (executor stamps), so all four are comparable (see
	 * [org.appdevforall.cotg.quickbuild.domain.E2eTimeline]). Defaults to
	 * `System.currentTimeMillis` so this module's pure-JVM unit tests run without an Android
	 * runtime; the app's Koin graph injects `SystemClock.elapsedRealtime` for the real
	 * monotonic device clock.
	 */
	private val nowMillis: () -> Long = System::currentTimeMillis,
	/**
	 * Per-project scratch trees on app-private storage (ADFA-4930): intermediates
	 * off FUSE. Overridable so tests can shrink or inflate the disk-space floor.
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

	/** Derived, never set imperatively - the stuck-banner bug is unrepresentable. */
	val status: StateFlow<QuickBuildStatus> =
		_state
			.map(QuickBuildStatus.Companion::from)
			.stateIn(scope, SharingStarted.Eagerly, QuickBuildStatus.Hidden)

	/**
	 * Provisioning/daemon failure text for the host UI to flash. Same messages as the
	 * injected [onUserMessage] callback; this flow is the surface the editor activity
	 * collects, since the Koin graph can't reach an Activity's flash helpers.
	 */
	val userMessages: SharedFlow<String>
		get() = _userMessages

	private val _userMessages =
		MutableSharedFlow<String>(
			extraBufferCapacity = 8,
			onBufferOverflow = BufferOverflow.DROP_OLDEST,
		)

	/**
	 * Neutral notices for the host UI - things that are not failures and must not be
	 * flashed as errors. Separate from [userMessages] precisely because that flow IS the
	 * error channel: a cancellation the user asked for reading as a red error banner would
	 * be the same dishonesty as a silent one.
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
	 * Bumped by every [teardown]. In-flight provisioning/proxy-app-rebuild work captures the
	 * epoch at launch and discards its result when they differ: a provision completing
	 * after "Restart session" must never install itself as a zombie session (watcher +
	 * daemon live while the UI shows Idle). Only touched on [dispatcher].
	 */
	private var sessionEpoch = 0L

	/** The in-flight provision/prebuild/proxy app rebuild; cancelled by [teardown]. */
	private var sessionWork: Job? = null

	/**
	 * Owns the daemon-epoch protocol, the respawn-supersession cleanup and the
	 * low-memory shrink policy. The six intentional-transition bump sites (provisioning
	 * start + its undo, proxy-app-rebuild teardown + restart, session teardown,
	 * low-memory shrink) stay explicit via
	 * [QuickBuildDaemonController.markIntentionalTransition]; see the controller's
	 * KDoc for the exactly-one-transition rule.
	 */
	private val daemonController = QuickBuildDaemonController(daemon, scratch, paths)

	/**
	 * Assembles live sessions (and their ProxyAppInfo-derived rebuild pieces).
	 * Constructed here from deps this class already takes, so the manager's ctor, the
	 * Koin wiring, and the two test seams it passes through stay unchanged.
	 */
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
	 * Runs the Gradle proxy app builds (provision + rebuild fallback) and returns
	 * verdicts; this manager keeps the epoch guards, installs sessions, and dispatches.
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
			// Reconnect catch-up: a killed-and-relaunched proxy app reports the
			// generation it booted; below what this session already deployed means it
			// is verifiably running superseded code (persisted payload lost/stale), so
			// force a rebuild of current sources at a fresh generation - the same path
			// as an explicit tap. Without this, nothing reacts to a stale reconnect
			// and the app would run old code silently until the next edit.
			connections.target.collect { target ->
				val session = live ?: return@collect
				if (target != null && target.runningGeneration < session.lastDeployedGeneration) {
					log.info(
						"Proxy app reconnected at generation {} but the session deployed {}; forcing a catch-up build",
						target.runningGeneration,
						session.lastDeployedGeneration,
					)
					// NOT user-initiated: nobody tapped anything. Saying otherwise here would
					// foreground the proxy app off a stale reconnect (behaviour 2's ask must
					// come from a human).
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
			// Stale-tree sweep (ADFA-4930): nothing can be live yet - this manager is the
			// process's only session owner and no tap has dispatched - so every tree under
			// the scratch root is a dead session's leftover (process kill, crash) or a
			// since-deleted project's. Runs on [dispatcher], strictly before any tap.
			scratch.sweep(liveProjectRoots = emptyList())
		}
	}

	/**
	 * The lightning-bolt tap: starts a session from Idle, forces a build when live, and
	 * queues onto an in-flight prebuild ([QuickBuildSessionState.Prebuilding.tapQueued]).
	 *
	 * The tap is dispatched FIRST and the history write follows it. It used to be the other
	 * way round, which made the reducer see the tap only after a side effect that can be
	 * slow or throw: [prebuild] dispatches immediately, so a tap sequenced behind a disk
	 * write could be reduced after `PrebuildFinished` had already settled the session back to
	 * [QuickBuildSessionState.Idle] - and a write that threw killed this coroutine before
	 * the dispatch, losing the tap outright (a dead press on the primary control, which is
	 * also the press the parked-session banner instructs the user to make). Nothing depends
	 * on the ordering the other way: prebuild is no longer gated on this history (see
	 * [prebuild]), so it is bookkeeping.
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
	 * The stop button (Bryan's behaviour 5): the SAME toolbar button, tapped while it is
	 * showing the standard build's stop icon. Dispatching is safe from any state - the
	 * reducer only acts on the states that own a build the user asked for, so a tap that
	 * raced the build's completion is a no-op rather than a spurious cancellation.
	 */
	fun onCancelRequested() {
		scope.launch { dispatch(SessionEvent.CancelRequested) }
	}

	/**
	 * Call when CoGo's editor returns to the foreground. Recovers the one park that a
	 * tap cannot be expected to fix unprompted: a proxy app rebuild reinstall that ran while
	 * CoGo was BACKGROUNDED never showed a confirm dialog - Android DEFERS the
	 * PENDING_USER_ACTION broadcast until the app is foregrounded, and the dialog-owning
	 * subscriber (InstallationResultHandler) is EventBus lifecycle-bound (registered
	 * onStart, unregistered onStop), so the deferred delivery can land before it
	 * re-registers and nothing launches the dialog. The user saw nothing fail.
	 * Re-running the proxy app rebuild now - with CoGo foreground - makes the prompt actually
	 * appear. Dispatch is a no-op in every state except
	 * [QuickBuildSessionState.Invalidated] with `awaitingRetry = true` (and the reducer
	 * bounds the auto-retries), so calling this from every editor onResume is safe and
	 * cheap.
	 */
	fun onHostForegrounded() {
		scope.launch { dispatch(SessionEvent.HostForegrounded) }
	}

	/**
	 * Eager warm-up (plan B2): call at project open, AFTER the normal Gradle sync
	 * completes, with the experimental flag on. Runs the proxy app build in the background
	 * so the first tap pays only install + bind; installs nothing. No-op unless Idle.
	 * A tap landing mid-warm queues and provisions when the warm build finishes.
	 *
	 * NOT gated on project history. The previous behaviour (plan P7) skipped the warm-up
	 * until Quick Build had been tapped once on the project, to avoid spending battery on
	 * a feature that might never be used. In practice that made the first tap on every
	 * new project pay the whole cold proxy app build cost -- ~97 s on an a56 for a small app -- which
	 * is the one impression a user forms of the feature. If Quick Build is enabled, warm it.
	 */
	fun prebuild() {
		scope.launch { dispatch(SessionEvent.PrebuildRequested) }
	}

	/**
	 * Mode-switch hand-back (plan B3): call when a Standard Run's Gradle build completes
	 * (e.g. from the A2 dropdown's "Standard Run", or the Run button's build-finished
	 * hook). A live session refreshes its baseline from current disk - a full proxy app
	 * rebuild when the external build clobbered the proxy app build artifacts, otherwise a
	 * baseline refresh (the next build recompiles everything) - so the next quick build is
	 * never stale. No session: no-op.
	 */
	fun onStandardRunCompleted() {
		scope.launch { dispatch(SessionEvent.ExternalBuildCompleted) }
	}

	/**
	 * Restart action (plan A2 dropdown "Restart session"): tears down the current live
	 * session and daemon and returns to Idle from whatever state the session is in. The
	 * next tap re-provisions from scratch - the escape hatch for a daemon or proxy app
	 * stuck past what a plain quick build or proxy app rebuild can recover.
	 */
	fun restartSession() {
		scope.launch { dispatch(SessionEvent.SessionRestartRequested) }
	}

	/**
	 * Framework low-memory signal (P1a.1, low-spec device support): the host
	 * Activity/Service forwards `ComponentCallbacks2.onTrimMemory`'s level here. The
	 * compile daemon is a separate child JVM ([org.appdevforall.cotg.quickbuild.data.DaemonProcessClient])
	 * whose heap is pure overhead between builds - on a constrained device it is the
	 * first thing worth giving back under memory pressure.
	 *
	 * Decision (this ticket asked for one, not just an action): only
	 * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] and above tear the daemon down.
	 * `RUNNING_MODERATE`/`RUNNING_LOW` are no-ops - those fire on transient pressure the
	 * OS usually recovers from without ever killing the process, and tearing the daemon
	 * down pays a full proxy app rebuild's cost (every proxy app rebuild is a real Gradle build) for
	 * pressure that may pass in seconds. `RUNNING_CRITICAL` is documented as "about to be
	 * killed" - the point where giving the memory back is worth that cost.
	 *
	 * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is explicitly NOT a teardown, even
	 * though Android numbers it (20) above `RUNNING_CRITICAL` (15): it means "your UI
	 * went away", not "memory is short". Backgrounding CoGo is the MIDDLE of the Quick
	 * Build loop, not the end of it - the user switches to their running proxy app to look
	 * at the edit they just made, then comes back to edit again. Tearing the daemon down
	 * there costs a respawn plus a re-seed on the very next edit and breaks the flow the
	 * feature exists for. So Quick Build's daemon follows the same policy as CoGo's
	 * standard Gradle build daemon: it sticks around across backgrounding and is reclaimed
	 * by memory pressure (or its idle timeout), never by the user looking at their app.
	 * The cached-process levels above it (`BACKGROUND`=40, `MODERATE`=60, `COMPLETE`=80)
	 * DO tear down - those only arrive when the system is genuinely short on memory.
	 *
	 * A build already in flight is never interrupted: forcibly killing the daemon
	 * mid-compile would abort a request that may be seconds from finishing, only to redo
	 * the same work on the auto-respawn. The teardown defers instead, applied the moment
	 * the build's own completion event moves the state off [QuickBuildSessionState.Building]
	 * (see the `_state` collector in `init`).
	 *
	 * Re-warm is deliberately lazy, not immediate: nothing here calls `daemon.start`. The
	 * next build attempt (a tap or a watcher-triggered save) finds the daemon dead, which
	 * [org.appdevforall.cotg.quickbuild.service.LiveReloadExecutorImpl] already reports as
	 * an [org.appdevforall.cotg.quickbuild.domain.BuildOutcome.InfrastructureFailure] with
	 * `daemonDied = true` - the SAME signal a real daemon crash produces - so the existing
	 * [SessionEvent.DaemonDied] -> [QuickBuildSessionState.Degraded] ->
	 * [SessionEffect.RespawnDaemon] recovery re-seeds with [ChangedFiles.Unknown] and
	 * that build lands normally. No new recovery path needed: this reuses the one that
	 * already exists for an unplanned daemon death, on purpose.
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
	 * A coalesced batch of changes from the watcher (already filtered to relevant paths).
	 * Hopped onto [dispatcher]; the orchestrator + classifier decide the route (quick build
	 * vs. proxy app rebuild) and handle concurrency with any in-flight build.
	 *
	 * The modified-vs-removed reconciliation itself is domain logic
	 * ([WatcherBatchReconciler]); this shell only supplies the `File.isFile` probe and
	 * dispatches the reconciled batch.
	 */
	private fun onWatcherBatch(batch: ChangedFiles.Known) {
		val reconciled = WatcherBatchReconciler.reconcile(batch, File::isFile)
		if (reconciled.isEmpty) return
		scope.launch {
			live?.orchestrator?.onFilesChanged(reconciled)
		}
	}

	/** Runs on [dispatcher] only. */
	private suspend fun dispatch(event: SessionEvent) {
		val transition = reducer.reduce(_state.value, event)
		if (transition.state != _state.value) {
			log.info("Quick-build session: {} -> {} on {}", _state.value, transition.state, event)
		}
		_state.value = transition.state
		transition.effects.forEach(::runEffect)
	}

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
					// The build can finish between the reducer's decision and this effect. Fall
					// back to a real request rather than let the tap vanish - a tap that does
					// nothing at all is the failure mode this whole path exists to remove.
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
				// Emitted only from Prebuilding(tapQueued)/Provisioning, i.e. exactly when this
				// session owns the device's single Gradle build slot - see the port's KDoc for
				// why issuing it blind would be dangerous.
				if (provisioner.cancelProxyAppBuild()) {
					log.info("Quick Build proxy app build cancelled by the user")
				} else {
					// The Gradle build had already finished (the session is in its install or
					// daemon-spawn tail). The TeardownSession effect that follows still stops
					// the session, so the stop is honoured; nothing Gradle is doing is claimed.
					log.info("No Quick Build proxy app build to cancel; tearing the session down instead")
				}
				surfaceNotice(QuickBuildNotice.BUILD_CANCELLED)
			}

			SessionEffect.StartWarmCompile -> {
				if (warmCompileEnabled()) {
					// live is assigned before ProvisioningSucceeded is dispatched (see
					// startProvisioning), so the orchestrator is always there to take this.
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

	private suspend fun triggerLiveReload(userInitiated: Boolean) {
		val orchestrator = live?.orchestrator ?: return
		val awaitsDeploy = orchestrator.onLiveReloadRequested(userInitiated)
		// Behaviour 4: a tap with nothing pending still costs a full recompile + relink +
		// deploy (the runtime only accepts strictly-newer generations, so a metadata-only
		// replay cannot land), and the user must not stare at the editor through it. Answer
		// the tap NOW and let the redeploy land behind them. The decision lives here rather
		// than in the reducer because only the orchestrator knows what is pending.
		if (userInitiated && !awaitsDeploy) switchToProxyApp()
	}

	/**
	 * Bring the proxy app to the foreground because the USER asked (behaviours 2 and 4).
	 * Best-effort: a refusal is logged, never surfaced - the build itself already landed and
	 * the user can open the app themselves, so a second banner would only add noise.
	 */
	private fun switchToProxyApp() {
		val session = live ?: return
		// Same target the restart-deploy relaunch uses: the proxied launcher activity when
		// one carries MAIN/LAUNCHER, else null so the launcher falls back to the package's
		// default launch intent (which resolves an <activity-alias> launcher).
		val launcherActivity =
			session.proxyApp.components
				.firstOrNull { it.kind == ComponentKind.ACTIVITY && it.launcher }
				?.proxyClass
		if (!launcher.launch(session.proxyApp.proxyAppPackage, launcherActivity)) {
			log.warn("Could not bring the proxy app {} to the foreground", session.proxyApp.proxyAppPackage)
		}
	}

	/** B2 warm-up: best-effort, silent on failure; always reports finished. */
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

	private suspend fun provision(startEpoch: Long) {
		when (val result = buildRunner.provision(superseded = { startEpoch != sessionEpoch })) {
			is ProxyAppBuildRunner.ProvisionResult.DiskSpaceShort -> {
				dispatch(SessionEvent.ProvisioningFailed(result.message))
			}

			is ProxyAppBuildRunner.ProvisionResult.Failed -> {
				dispatch(SessionEvent.ProvisioningFailed(result.message))
			}

			is ProxyAppBuildRunner.ProvisionResult.Superseded -> {
				// "Restart session" landed while the proxy app build ran; the user asked for
				// a fresh start, so a late success must not resurrect (and a late failure
				// must not surface) - see the zombie-session scenario in the teardown KDoc.
				log.info("Quick-build provisioning outlived a session restart; discarding")
			}

			is ProxyAppBuildRunner.ProvisionResult.SupersededDuringDaemonStart -> {
				// Restart raced the daemon start: the runner already undid its side; stop
				// the zombie daemon on a fresh coroutine (this one is already cancelled).
				log.info("Session restarted during daemon start; shutting down")
				daemonController.markIntentionalTransition()
				scope.launch { daemonController.shutdown() }
			}

			is ProxyAppBuildRunner.ProvisionResult.Succeeded -> {
				live = result.session
				// Build ids restart per session; give the sink its session boundary.
				report { metrics.onSessionStarted() }
				// Trigger on file change from any source (editor, Termux, plugin,
				// git pull) - the reload path is change-driven, not save-driven.
				result.session.watcher.start(::onWatcherBatch)
				dispatch(SessionEvent.ProvisioningSucceeded(result.tracker.current))
			}
		}
	}

	/** Delivered synchronously on [dispatcher] by the orchestrator; hop to a launch. */
	private fun onOrchestratorEvent(event: OrchestratorEvent) {
		scope.launch {
			val session = live
			val routing =
				eventRouter.route(
					event,
					lastDeployedGeneration = session?.lastDeployedGeneration ?: -1L,
					connectedGeneration = connections.target.value?.runningGeneration,
				)
			// Tally first (the dispatched BuildSucceeded's consumers may read it),
			// events second, the best-effort building notification last - the same
			// order as before the router was extracted.
			routing.newLastDeployedGeneration?.let { generation ->
				session?.lastDeployedGeneration = generation
			}
			routing.sessionEvents.forEach { dispatch(it) }
			routing.notifyBuildingAt?.let { generation ->
				// No live session means nothing truthful to say - skip silently, like
				// every other best-effort status push.
				if (session != null) notifyBuilding(generation)
			}
		}
	}

	/**
	 * Honesty line while a build is in flight (WS-G): tells the proxy app it is one
	 * generation behind while the new one compiles, so a slow build never reads as
	 * silence. The generation choice (session tally vs the connected target's
	 * self-report) is [OrchestratorEventRouter]'s call - see
	 * [OrchestratorEventRouter.Routing.notifyBuildingAt].
	 */
	private fun notifyBuilding(runningGeneration: Long) {
		try {
			deploy.notifyBuildStatus(BuildStatusJson.building(runningGeneration))
		} catch (e: Exception) {
			log.warn("Build-starting notification failed", e)
		}
	}

	/** Metrics can never affect a build: a throwing sink degrades to a logged warning. */
	private inline fun report(block: () -> Unit) {
		try {
			block()
		} catch (e: Throwable) {
			log.warn("Quick Build metrics sink failed", e)
		}
	}

	private suspend fun rebuildProxyApp(startEpoch: Long) {
		val session = live ?: return
		// Captured BEFORE ProxyAppRebuildStarted moves the session to Provisioning, which carries
		// neither the reason nor the deployed generation: a retry that never gets the Gradle
		// slot has to park back exactly where it came from (see ProxyAppRebuildDeferred).
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
				// The session this proxy app rebuild was for is gone; don't poke its orchestrator.
				log.info("Quick-build proxy app rebuild outlived a session restart; discarding")
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.BuildSlotBusy -> {
				if (installRetryPark != null) {
					// The retry never got the Gradle slot (typically CoGo's own project sync,
					// which the invalidating gradle edit itself triggers). Park back WITHOUT
					// spending the auto-retry budget. Do NOT re-state the park's install
					// guidance here: its dominant text says "return to CoGo", and returning
					// to CoGo is exactly what triggered this retry - say what is actually
					// happening instead. "Proxy app rebuild failed" would be worse still, a
					// claim about a build that never ran.
					log.info("Gradle slot busy; deferring the proxy app rebuild retry without spending an auto-retry")
					surfaceUserMessage(
						"Waiting for the current Gradle build to finish - your app still " +
							"needs a reinstall. Tap Quick Build to retry.",
					)
					dispatch(SessionEvent.ProxyAppRebuildDeferred(installRetryPark.deployedGeneration))
				} else {
					// A first proxy app rebuild (not a parked retry) has no park to return to and no
					// budget to protect; report it like any other proxy-app-build failure.
					session.orchestrator.onProxyAppRebuildFailed()
					dispatch(SessionEvent.ProvisioningFailed("Proxy app rebuild failed"))
				}
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.Succeeded -> {
				// The proxy app rebuild regenerated setup.json and reinstalled the proxy app:
				// every ProxyAppInfo-derived piece of the session (deploy-policy components,
				// componentInfoAvailable, launcher/entry targets, classpath) must move to
				// the new baseline, or the policy keeps routing on provisioning-time
				// facts - e.g. a service the proxy app rebuild just proxied would hot-swap and
				// silently leave its live instance stale.
				session.proxyApp = result.proxyApp
				session.layout = result.layout
				session.executor.delegate =
					sessionFactory.executorFor(result.proxyApp, result.layout, session.tracker)
				session.annotationImpact.delegate =
					sessionFactory.annotationImpactFor(result.proxyApp, result.layout)
				// The freshly installed baseline boots gen 0 again; the fingerprint gate
				// in its runtime discarded any older persisted payload.
				session.lastDeployedGeneration = -1L
				session.orchestrator.onBaselineReset()
				dispatch(SessionEvent.ProvisioningSucceeded(session.tracker.current))
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
				// The Gradle build was fine; only the reinstall confirmation is missing
				// (no dialog shown / cancelled / left untapped - the stranded-session
				// finding from the multi-module device verify). Park recoverable instead
				// of dying to Idle; the message already says how to recover for its
				// specific case. Deliberately NOT onProxyAppRebuildFailed(): the orchestrator
				// keeps holding the absorbed batch, so quick builds stay suspended while
				// the daemon is down (a live-reload save here would only fail against the
				// dead daemon); the retry's onProxyAppRebuildStarted re-holds pending on
				// top, and every held file is on disk for its Gradle build to absorb.
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
	 * B3 hand-back: an external full build finished. When the proxy app build artifacts the daemon
	 * builds against are still on disk, marking the baseline dirty is enough - the next
	 * build recompiles everything from current disk, without reinstalling anything. When
	 * the external build removed them (a clean wiped build/), only a full proxy app rebuild
	 * helps: route to the existing invalidation machinery as EXTERNAL_FULL_BUILD.
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

	private suspend fun respawnDaemon(startEpoch: Long) {
		val session = live ?: return
		when (val outcome = daemonController.respawn(session.layout, session.proxyApp, startEpoch)) {
			is QuickBuildDaemonController.RespawnOutcome.Respawned -> {
				dispatch(SessionEvent.DaemonRespawned)
				// A fresh daemon has no trustworthy IC state. With nothing pending this
				// re-warms via a deploy-nothing WarmCompile (the proxy app keeps running its
				// current generation untouched); with pending work it marks the baseline
				// dirty so the next build recompiles everything and deploys.
				session.orchestrator.onDaemonReplaced()
			}

			// The controller already stopped any zombie daemon per its
			// exactly-one-transition rule; the successor flow owns the lifecycle.
			is QuickBuildDaemonController.RespawnOutcome.Superseded -> {
				Unit
			}

			is QuickBuildDaemonController.RespawnOutcome.Failed -> {
				log.error("Daemon respawn failed: {}", outcome.message)
				// Stay Degraded (honest); the next explicit tap or session restart
				// retries. Auto-retry loops on a hard-broken daemon would spin.
				surfaceUserMessage("Quick Build daemon could not be restarted: ${outcome.message}")
			}
		}
	}

	/**
	 * Tears down the live session AND any in-flight provision/prebuild/proxy app rebuild. The
	 * epoch bump + cancel pair is what makes "Restart session" safe mid-provisioning:
	 * without it, a provision resuming after the restart would set [live], start its
	 * watcher and build/deploy invisibly while the UI shows Idle - and the next tap's
	 * provision would overwrite [live] leaving the orphan watcher running forever.
	 * Cancelling [sessionWork] from within that very coroutine (the
	 * SurfaceProvisioningError path) is safe: nothing suspends after the dispatch.
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
			// Only after the daemon is down: it writes into this tree until then. A
			// teardown with no live session (provisioning failed before going live)
			// has nothing to remove; the init-time sweep reclaims any half-made tree.
			// Skip when a NEW session for the same project went live while shutdown
			// suspended - the tree is now that session's, not this one's to delete.
			scratchOwner
				?.takeIf { live?.layout?.projectRoot != it }
				?.let(scratch::remove)
		}
	}

	private fun surfaceUserMessage(message: String) {
		onUserMessage(message)
		_userMessages.tryEmit(message)
	}

	private fun surfaceNotice(notice: QuickBuildNotice) {
		_notices.tryEmit(notice)
	}

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildSessionManager::class.java)
	}
}
