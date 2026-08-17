package org.appdevforall.cotg.quickbuild.service.session

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.classify.recompilesCode
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationStore
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadRequestOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.reload.RESTART_SENSITIVE_KINDS
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildNotice
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionEffect
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionReducer
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.watch.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.watch.WatcherBatchReconciler
import org.appdevforall.cotg.quickbuild.service.deploy.BuildStatusJson
import org.appdevforall.cotg.quickbuild.service.deploy.DeployResult
import org.appdevforall.cotg.quickbuild.service.deploy.DeploySender
import org.appdevforall.cotg.quickbuild.service.deploy.ProxyAppConnections
import org.appdevforall.cotg.quickbuild.service.deploy.TargetReport
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppBuildRunner
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.telemetry.report
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The shell around the domain session machine: owns the [SessionReducer] and the live session,
 * and turns reducer effects into provisioning, daemon respawn, and Gradle proxy app rebuilds.
 *
 * Everything stateful runs on [dispatcher]. Effects are launched rather than run inline so a
 * reducer dispatch never re-enters itself, and that dispatcher's single thread is what keeps
 * the launched work ordered.
 */
class QuickBuildSessionManager(
	/** Warm compile server; its death listener is wired here, in [init]. */
	private val daemon: QuickBuildDaemon,
	/**
	 * Deploy channel, used directly only for the best-effort build-status pushes and for
	 * re-sending the retained payload on a stale reconnect (see [resendRetainedPayload]).
	 */
	private val deploy: DeploySender,
	/** The door to Gradle for the proxy app build, rebuild, and prebuild. */
	private val provisioner: QuickBuildProvisioner,
	/** Deploy-channel registry; also the source of crash and reconnect signals. */
	private val connections: ProxyAppConnections,
	/** Bundled toolchain locations, passed straight through to the daemon controller. */
	private val paths: QuickBuildPaths,
	/** Gates eager prebuild on project history and records first use. */
	private val historyStore: QuickBuildHistoryStore,
	/**
	 * Confines everything stateful. Must be single-threaded: the orchestrator's
	 * event-ordering guarantee depends on it.
	 */
	dispatcher: CoroutineDispatcher,
	/** Opens the project's persisted generation counter, keyed by its root directory. */
	private val generationStoreFactory: (File) -> GenerationStore = {
		FileGenerationStore.forProject(it)
	},
	/** Test seam; null builds the real executor. */
	private val executorFactory: ExecutorFactory? = null,
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
	 * they are comparable (see [org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline]).
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
	/**
	 * Whether this device can serve a deployed asset payload - the runtime's asset overlay
	 * needs the API 30+ `ResourcesLoader`. False routes asset-bearing edits to Gradle instead
	 * of acking a reload the app cannot see; the app's Koin graph reads the device's SDK level.
	 * Defaults to the capable path so this module's unit tests need no Android runtime.
	 */
	private val assetsLiveReloadable: Boolean = true,
) {
	/** Builds the project watcher for a live session; overridden with a fake in tests. */
	fun interface WatcherFactory {
		/**
		 * Builds a watcher over one session's watch set.
		 *
		 * @param roots directories to watch recursively
		 * @param files individual files to watch that lie outside [roots]
		 * @param filter decides which raw events are worth reporting
		 * @param scope the manager's scope, so its cancellation stops the watcher too
		 * @return a watcher that observes nothing until it is started
		 */
		fun create(
			roots: List<File>,
			files: List<File>,
			filter: WatchFilter,
			scope: CoroutineScope,
		): ProjectWatcher
	}

	/** Test seam: build the executor for a freshly provisioned session. */
	fun interface ExecutorFactory {
		/**
		 * Builds the executor for one proxy app baseline.
		 *
		 * @param proxyApp the baseline just built and installed
		 * @param layout the layout derived from that same baseline
		 * @param tracker the session's generation allocator, shared across rebuilds
		 * @return the executor the session's switchable delegate will point at
		 */
		fun create(
			proxyApp: ProxyAppInfo,
			layout: QuickBuildProjectLayout,
			tracker: GenerationTracker,
		): LiveReloadExecutor
	}

	private val scope = CoroutineScope(SupervisorJob() + dispatcher)
	private val reducer = SessionReducer()

	private val _state =
		MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Idle())

	/** Raw session-machine state; UI should prefer the derived [status]. */
	val state: StateFlow<QuickBuildSessionState> = _state

	/**
	 * What the toolbar shows. Derived from [state] and never set imperatively, so a banner
	 * cannot get stuck out of step with the session.
	 */
	val status: StateFlow<QuickBuildStatus> =
		_state
			.map(QuickBuildStatus.Companion::from)
			.stateIn(scope, SharingStarted.Eagerly, QuickBuildStatus.Hidden())

	/**
	 * Provisioning and daemon failure text for the host UI to flash. The editor activity
	 * collects it, since the Koin graph cannot reach an Activity's flash helpers.
	 */
	val userMessages: SharedFlow<QuickBuildMessage>
		get() = _userMessages

	private val _userMessages =
		MutableSharedFlow<QuickBuildMessage>(
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

	/**
	 * [teardown]'s asynchronous tail - the daemon shutdown and the scratch-tree removal.
	 *
	 * Awaited by [SessionEffect.TeardownAndProvision] so a user-requested restart cannot start a
	 * daemon into a shutdown still in flight. Deliberately not awaited by an ordinary
	 * [SessionEffect.StartProvisioning]: a tap after a teardown may go live while that tail runs,
	 * which the scratch-tree check makes safe. Only touched on [dispatcher].
	 */
	private var teardownWork: Job? = null

	/**
	 * True once [QuickBuildNotice.STALE_COMPONENT_HELPERS] has been shown for this session.
	 *
	 * The gap holds for every hot-swap deploy, so re-flashing it on each save would bury the
	 * notices that report something happening. Cleared by [provision], the one path that can owe
	 * it again - the next session may be a different project - while a proxy app rebuild does not
	 * re-arm it, since the fact is about the app being edited. Only touched on [dispatcher].
	 */
	private var staleComponentHelpersNoticed = false

	/**
	 * When ([nowMillis]) a request to bring the proxy app forward arrived while a full Gradle
	 * build held the screen; null when no ask is waiting. The ask waits for that build instead
	 * of stranding the user in a stale app. A re-defer behind a chained build preserves the
	 * stamp, so the expiry ages the ask from the original request.
	 *
	 * See [switchToProxyApp] for why leaving mid-build is worse than making the user wait, and
	 * [settleDeferredForegroundAsk] for when it is answered, expired or dropped. Only touched
	 * on [dispatcher].
	 */
	private var foregroundAskDeferredAtMillis: Long? = null

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
			assetsLiveReloadable = assetsLiveReloadable,
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
					// Accepted limitation, but not a silent one: the ATTENTION icon alone
					// would leave the user watching a crash with no idea that only a
					// session restart clears it. Told on every crash, not once: each
					// reload reproduces it, and the report cannot tell a bad payload from
					// a bug in the code the user just wrote.
					surfaceNotice(QuickBuildNotice.RELOAD_CRASHED)
					dispatch(SessionEvent.ProxyAppCrashed(report.stackSummary))
				}
			}
		}
		scope.launch {
			// Reconnect catch-up: a relaunched proxy app reports the generation it
			// booted, and one below what this session deployed means its persisted
			// payload was lost or stale; left alone it runs old code silently until the
			// next edit. First choice is re-sending the retained last-deployed payload
			// at its original generation (concurrency.md rules 3-4): the stamped
			// baseline makes any below-deployed reconnect same-baseline, and the app
			// runs something strictly older than the retained generation, so the
			// runtime's newer-only gate accepts the replay. Only when retention is
			// missing or the re-send fails does the forced rebuild of current sources
			// run, as last-resort repair.
			connections.target.collect { target ->
				val session = live ?: return@collect
				if (target == null || target.runningGeneration >= session.lastDeployedGeneration) {
					return@collect
				}
				if (resendRetainedPayload(session, target.runningGeneration)) return@collect
				log.info(
					"Proxy app reconnected at generation {} but the session deployed {}; " +
						"no retained payload to re-send, forcing a catch-up build",
					target.runningGeneration,
					session.lastDeployedGeneration,
				)
				// Not user-initiated: nobody tapped anything, and saying otherwise
				// would foreground the proxy app off a stale reconnect.
				session.orchestrator.onLiveReloadRequested(userInitiated = false)
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
			scratch.sweep()
		}
	}

	/**
	 * Handles the Quick Build tap: starts a session from Idle, triggers a build when live,
	 * and queues onto an in-flight prebuild.
	 *
	 * The tap must dispatch before the history write, never after: behind a disk write it
	 * could be reduced after `PrebuildFinished` already settled back to Idle, and a write
	 * that throws would lose the tap outright. Nothing depends on the other ordering.
	 *
	 * @param wroteSomething whether the tap's save-all wrote at least one file - the one bit
	 *   the tap carries; the watcher stays the single changeset source, so no filenames cross
	 *   this boundary. True routes the tap through the watcher batch those writes produce;
	 *   false with nothing pending switches to the proxy app without building, since the
	 *   deployed app is already current.
	 */
	fun onQuickBuildTapped(wroteSomething: Boolean = false) {
		scope.launch {
			dispatch(SessionEvent.QuickBuildTapped(wroteSomething))
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
	 * An editor save reached the host's save path. Call from the editor's save funnel, on
	 * every save.
	 *
	 * Only a failed-start Idle acts on it, clearing the stale error tone; the save never
	 * retries the start (a retry stays a tap). Every other state ignores it - a live session
	 * learns about saves from its own watcher, so this must never trigger a build.
	 */
	fun onFileSaved() {
		scope.launch { dispatch(SessionEvent.FileSaved) }
	}

	/**
	 * Retries a reinstall whose confirm dialog never appeared, now that CoGo is
	 * foreground again. Call from the editor's onResume.
	 *
	 * A reinstall that ran with CoGo backgrounded shows nothing: Android defers
	 * PENDING_USER_ACTION until foreground, and the lifecycle-bound dialog subscriber may
	 * not have re-registered when it lands. A no-op outside an Invalidated session.
	 */
	fun onHostForegrounded() {
		scope.launch { dispatch(SessionEvent.HostForegrounded) }
	}

	/**
	 * Runs the proxy app build in the background so the first tap pays only install and
	 * bind. Call at project open, after the normal Gradle sync completes.
	 *
	 * Installs nothing, and is a no-op unless Idle; a tap landing mid-warm queues and
	 * provisions when the warm build finishes. Not gated on project history, which would make
	 * a new project's first tap pay a cold build - about 97 s for a small app [measured on a56].
	 */
	fun prebuild() {
		scope.launch { dispatch(SessionEvent.PrebuildRequested) }
	}

	/**
	 * The editor's project-sync-completed hook: warms an idle session, reprovisions a variant switch.
	 *
	 * Applying a Build Variants selection re-syncs the project, and a live session provisioned
	 * for the old variant would keep hot-reloading into it - a different application id once
	 * flavors carry a suffix, so the user edits one app and watches another. A plain sync
	 * compares equal and behaves exactly like [prebuild].
	 *
	 * @param selectedVariant the Build Variants selection now in effect, or null when the
	 *   project model cannot name one - which never restarts, since an unknown variant is not
	 *   evidence of a change
	 */
	fun onProjectSynced(selectedVariant: String? = null) {
		scope.launch {
			val provisioned = live?.provisionedVariant
			if (provisioned != null && selectedVariant != null && provisioned != selectedVariant) {
				log.info(
					"Build variant changed from {} to {}; reprovisioning the Quick Build session",
					provisioned,
					selectedVariant,
				)
				dispatch(SessionEvent.SessionRestartAndReprovisionRequested)
			} else {
				dispatch(SessionEvent.PrebuildRequested)
			}
		}
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
	 * The internal half of the escape hatch: for callers that want the session gone and nothing
	 * started in its place - a project closing, or a Standard Run about to install over the proxy
	 * app. A user who asked to restart wants [restartSessionAndReprovision].
	 */
	fun restartSession() {
		scope.launch { dispatch(SessionEvent.SessionRestartRequested) }
	}

	/**
	 * Tears the session and daemon down from any state and immediately provisions a fresh one.
	 *
	 * The escape hatch as the user meets it - the long-press menu's "Restart session" and the
	 * won't-stay-up dialog. Both mean a fresh proxy app build, the only thing that clears a
	 * baked-in startup crash or an unresolvable resource reference, and what the dialog's copy
	 * already promises.
	 */
	fun restartSessionAndReprovision() {
		scope.launch { dispatch(SessionEvent.SessionRestartAndReprovisionRequested) }
	}

	/**
	 * Gives the compile daemon's memory back under system pressure. The host forwards
	 * `ComponentCallbacks2.onTrimMemory`'s level here.
	 *
	 * The daemon is a separate child JVM whose heap is pure overhead between builds, so it
	 * is the first thing worth releasing. Which levels tear it down, and why a build in
	 * flight defers, is [QuickBuildDaemonController.onTrimMemory].
	 *
	 * @param level the raw `ComponentCallbacks2` level, forwarded unfiltered - the
	 *   threshold rules live in the controller, not in the host
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
	 *
	 * @param batch one coalesced watcher batch, before reconciliation; a batch that
	 *   reconciles to empty is dropped rather than passed on as a no-change build
	 */
	private fun onWatcherBatch(batch: ChangedFiles.Known) {
		val reconciled = WatcherBatchReconciler.reconcile(batch, File::isFile)
		if (reconciled.isEmpty) return
		log.debug(
			"Watcher batch: {} modified [{}], {} removed [{}]",
			reconciled.files.size,
			describePaths(reconciled.files),
			reconciled.removed.size,
			describePaths(reconciled.removed),
		)
		scope.launch {
			live?.orchestrator?.onFilesChanged(reconciled)
		}
	}

	/**
	 * Renders a path set for a log line, capped so a large batch (save-all, `git pull`) does
	 * not flood logcat with one line per file.
	 *
	 * @param paths the set to render; order is whatever the set iterates in
	 * @return up to 20 paths, comma-separated, with a "+N more" tail when truncated
	 */
	private fun describePaths(paths: Set<File>): String {
		val shown = paths.take(20)
		val remainder = paths.size - shown.size
		val listing = shown.joinToString(", ") { it.path }
		return if (remainder > 0) "$listing, +$remainder more" else listing
	}

	/**
	 * Reduces one event into the new state and runs its effects. On [dispatcher] only.
	 *
	 * @param event the event to reduce; the reducer is total, so an event the current
	 *   state does not care about is a silent no-op rather than an error
	 */
	private suspend fun dispatch(event: SessionEvent) {
		val transition = reducer.reduce(_state.value, event)
		if (transition.state != _state.value) {
			log.info("Quick-build session: {} -> {} on {}", _state.value, transition.state, event)
		}
		_state.value = transition.state
		transition.effects.forEach(::runEffect)
		settleDeferredForegroundAsk(transition.state)
	}

	/**
	 * Turns one reducer effect into real work, launched so a dispatch never re-enters itself.
	 *
	 * @param effect the effect to carry out; the launches land in order because
	 *   [dispatcher] is single-threaded
	 */
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
				scope.launch { triggerLiveReload(effect.userInitiated, effect.expectChanges) }
			}

			SessionEffect.MarkBuildUserInitiated -> {
				scope.launch {
					val orchestrator = live?.orchestrator ?: return@launch
					// The build can finish between the reducer's decision and this
					// effect; fall back to a real request rather than let the tap
					// vanish. expectChanges is false because the tap's saves either
					// rode along in the build that just finished or are pending already.
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

			is SessionEffect.SurfaceMessage -> {
				// Deliberately no teardown: this is the recoverable counterpart to
				// SurfaceProvisioningError, for a session that stays up.
				surfaceUserMessage(effect.message)
			}

			SessionEffect.TeardownSession -> {
				log.info("Quick-build session restarted by user request")
				teardown()
			}

			SessionEffect.TeardownAndProvision -> {
				log.info("Quick-build session restarted by user request; provisioning a fresh one")
				teardown()
				// After teardown, so it reads the epoch the teardown just bumped and any late
				// completion of the OLD session is discarded rather than adopted.
				val epoch = sessionEpoch
				val pendingTeardown = teardownWork
				sessionWork =
					scope.launch {
						// The daemon shutdown teardown launched is still in flight; starting the
						// new daemon into it would hand that shutdown the new daemon to kill.
						pendingTeardown?.join()
						provision(epoch)
					}
			}
		}
	}

	/**
	 * Asks the orchestrator for a build, and foregrounds the app when a tap has nothing to
	 * wait for.
	 *
	 * The decision lives here rather than in the reducer because only the orchestrator knows
	 * what is pending.
	 *
	 * @param userInitiated true only for a real tap; a reconnect catch-up must pass false,
	 *   since foregrounding the app off a stale reconnect would steal the screen
	 * @param expectChanges the tap's save-all wrote at least one file, so the answer should
	 *   ride the watcher batch those writes produce (see [SessionEffect.TriggerLiveReload])
	 */
	private suspend fun triggerLiveReload(
		userInitiated: Boolean,
		expectChanges: Boolean = false,
	) {
		val orchestrator = live?.orchestrator ?: return
		when (orchestrator.onLiveReloadRequested(userInitiated, expectChanges)) {
			// Nothing written and nothing pending: the deployed app is current, so the tap
			// is answered right now and no build runs. If the proxy app's process is dead
			// the switch relaunches it, and payload persistence plus the reconnect catch-up
			// bring it back in sync.
			LiveReloadRequestOutcome.SWITCH_NOW -> if (userInitiated) switchToProxyApp()

			// A build owns the ask; its deploy brings the app forward (or its failure
			// answers the tap with the error).
			LiveReloadRequestOutcome.AWAITS_DEPLOY -> Unit

			LiveReloadRequestOutcome.AWAITS_CHANGES -> scheduleTapSwitchFallback()
		}
	}

	/**
	 * Backstop for a tap armed on a watcher batch that never comes: the save-all wrote only
	 * watcher-irrelevant files (a `.md`, say), so nothing will consume the armed tap and no
	 * deploy would ever answer it. After the deadline, whoever still holds the unanswered tap
	 * switches; a batch that arrived first already consumed it and this is a no-op - either
	 * way the tap is answered exactly once.
	 */
	private fun scheduleTapSwitchFallback() {
		scope.launch {
			delay(TAP_SWITCH_FALLBACK_MILLIS)
			if (live?.orchestrator?.consumeUnansweredTap() == true) {
				log.info(
					"Quick Build tap saw no watcher batch within {} ms; switching to the proxy app anyway",
					TAP_SWITCH_FALLBACK_MILLIS,
				)
				switchToProxyApp()
			}
		}
	}

	/**
	 * Brings the proxy app to the foreground because the user asked.
	 *
	 * Best-effort: a refusal is logged rather than surfaced, since the build already
	 * landed and the user can open the app themselves.
	 *
	 * Held back while a full Gradle build is in flight - see [settleDeferredForegroundAsk].
	 */
	private fun switchToProxyApp() {
		val session = live ?: return
		if (fullGradleBuildInFlight()) {
			// Leaving now shows the user the app they already had, for as long as the Gradle
			// build takes, and it breaks the build's own install: the confirmation is a dialog
			// only CoGo can raise, and Android does not deliver PENDING_USER_ACTION to a
			// backgrounded app. The ask is answered when the rebaseline lands, dropped if it
			// does not, and expired if landing takes so long the ask has gone stale.
			log.info("Quick Build asked for the proxy app mid-full-build; deferring until it lands")
			// A re-defer keeps the original stamp: the expiry ages the ask from the user's
			// tap, and re-stamping here would let N chained sub-bound builds keep an
			// arbitrarily old ask alive. Only a genuinely new ask starts a fresh clock.
			foregroundAskDeferredAtMillis = foregroundAskDeferredAtMillis ?: nowMillis()
			return
		}
		foregroundAskDeferredAtMillis = null
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

	/**
	 * Whether the session is inside a full Gradle build - a first provision or a rebaseline.
	 *
	 * Read off the state rather than from the route that asked for the switch, so no caller can
	 * bring the app forward mid-build. Only the states that own a Gradle build count -
	 * [QuickBuildSessionState.Invalidated] does when a rebuild is running, and does not once it
	 * has parked awaiting a retry, since then nothing is coming for the ask to wait on.
	 *
	 * @return true when the proxy app must not be brought forward yet.
	 */
	private fun fullGradleBuildInFlight(): Boolean =
		when (val state = _state.value) {
			is QuickBuildSessionState.Provisioning -> true
			is QuickBuildSessionState.Prebuilding -> state.tapQueued
			is QuickBuildSessionState.Invalidated -> !state.awaitingRetry
			else -> false
		}

	/**
	 * Answers, expires or drops a foreground request that waited for a full Gradle build.
	 *
	 * Answered the moment the session is live again, which is what "not until the rebaseline is
	 * done" means - unless the ask has aged past [DEFERRED_FOREGROUND_ASK_MAX_AGE_MILLIS], in
	 * which case it expires: a stale ask must not beat where the user is now. Dropped when the
	 * build did not get there - a dead session or a park - because the app the user would land
	 * in is the stale one they asked to be taken away from, and showing it would read as the
	 * rebuild having worked.
	 *
	 * @param state the state just adopted.
	 */
	private fun settleDeferredForegroundAsk(state: QuickBuildSessionState) {
		val askedAtMillis = foregroundAskDeferredAtMillis ?: return
		when {
			state is QuickBuildSessionState.Ready || state is QuickBuildSessionState.Deployed -> {
				val ageMillis = nowMillis() - askedAtMillis
				if (ageMillis > DEFERRED_FOREGROUND_ASK_MAX_AGE_MILLIS) {
					log.info(
						"Quick Build's deferred proxy app switch expired after {} ms: " +
							"the user has moved on since asking",
						ageMillis,
					)
					foregroundAskDeferredAtMillis = null
					return
				}
				// switchToProxyApp clears the ask itself, and re-checks the guard - a
				// rebaseline that lands straight into another full build has to keep the
				// ask waiting on its ORIGINAL stamp, so chained builds cannot keep an
				// aging ask alive past the bound.
				switchToProxyApp()
			}

			state is QuickBuildSessionState.Idle ||
				(state is QuickBuildSessionState.Invalidated && state.awaitingRetry) -> {
				log.info("Quick Build's deferred proxy app switch dropped: the full build did not land")
				foregroundAskDeferredAtMillis = null
			}

			else -> {
				// Still building, installing or spawning the daemon; keep waiting.
			}
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

	/**
	 * Provisions a session and installs it as [live], unless a teardown outlived it.
	 *
	 * @param startEpoch the session epoch read when this effect fired; a later mismatch is
	 *   what tells a completing provision that the user already restarted the session
	 */
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
				staleComponentHelpersNoticed = false
				// A same-project predecessor's scratch tree can survive its teardown (see
				// [teardown]'s skip when a new session went live mid-shutdown); whatever it
				// retained belongs to another baseline and must not answer this session's
				// reconnects.
				result.session.retainedPayloads.clear()
				// The installed APK boots at the stamped baseline generation (concurrency.md
				// rule 2): the allocator must stay strictly above it, and adopting it as the
				// deploy tally makes a reconnect at the stamp read in-sync by construction.
				result.tracker.adoptAtLeast(result.baselineGeneration)
				result.session.lastDeployedGeneration = result.baselineGeneration
				// Build ids restart per session; give the sink its session boundary.
				report { metrics.onSessionStarted() }
				// The reload path is change-driven, not save-driven: any source of a
				// file change triggers it, including Termux, plugins and git.
				result.session.watcher.start(::onWatcherBatch)
				dispatch(SessionEvent.ProvisioningSucceeded(result.baselineGeneration))
			}
		}
	}

	/**
	 * Applies what [eventRouter] made of one orchestrator event. The orchestrator
	 * delivers synchronously on [dispatcher], so this hops to a launch.
	 *
	 * @param event the orchestrator fact; routing decides, this applies, and the order of
	 *   the three steps below is part of the contract
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
			if (event is OrchestratorEvent.BuildSucceeded) noticeStaleComponentHelpers(event, session)
			// The orchestrator decides when a repeating aapt2 rejection has become blocking; all
			// that is owed here is saying it, since the status surface only ever shows the
			// diagnostics and never that they are now stopping every save.
			if (event is OrchestratorEvent.BuildFailed && event.relinkStuck) {
				surfaceNotice(QuickBuildNotice.RELINK_STUCK)
			}
			// Same deal for the deploy half: the orchestrator decides when "not connected" has
			// stopped being transient, and all that is owed here is saying it - loudly, because
			// the status surface's own advice ("relaunch to reconnect") is the one action that
			// cannot work.
			if (event is OrchestratorEvent.BuildFailed && event.proxyAppWontStayUp) {
				surfaceNotice(QuickBuildNotice.PROXY_APP_WONT_STAY_UP)
			}
		}
	}

	/**
	 * Warns, once per session, that a landed hot swap left a live service, provider or custom
	 * `Application` calling the previous copies of the classes it just replaced.
	 *
	 * The restart closure ([org.appdevforall.cotg.quickbuild.domain.reload.DeployPolicy]) covers a
	 * component's own code and its supertypes, but not a helper class the component merely calls.
	 * That gap is accepted, so all that is owed to the user is saying it out loud.
	 *
	 * @param event the deploy that landed; a warm compile deployed nothing, a restart deploy
	 *   already relaunched the process, and a route that moved no class file cannot have
	 *   staled anything
	 * @param session the live session, read for the baseline's component list; null means the
	 *   session went away and there is nothing truthful to say
	 */
	private fun noticeStaleComponentHelpers(
		event: OrchestratorEvent.BuildSucceeded,
		session: LiveSession?,
	) {
		if (staleComponentHelpersNoticed || session == null) return
		if (event.route is BuildRoute.WarmCompile || !event.route.recompilesCode) return
		if (event.result.restarted) return
		if (session.proxyApp.components.none { it.kind in RESTART_SENSITIVE_KINDS }) return
		staleComponentHelpersNoticed = true
		surfaceNotice(QuickBuildNotice.STALE_COMPONENT_HELPERS)
	}

	/**
	 * Tells the proxy app a newer build is compiling while it keeps running
	 * [runningGeneration], so a slow build does not read as silence.
	 *
	 * Which generation that is comes from
	 * [OrchestratorEventRouter.Routing.notifyBuildingAt].
	 *
	 * @param runningGeneration what the app is still running, never the one being built
	 */
	private fun notifyBuilding(runningGeneration: Long) {
		try {
			deploy.notifyBuildStatus(BuildStatusJson.building(runningGeneration))
		} catch (e: Exception) {
			log.warn("Build-starting notification failed", e)
		}
	}

	/**
	 * Tells the proxy app its update is waiting on an install confirmation only CoGo can
	 * show, so the user watching the stale app knows to switch back.
	 *
	 * Without this the park is invisible from the proxy app: every other recovery signal
	 * (snackbar, Build Output, toolbar tone) is in CoGo, which is exactly the app the user
	 * is not looking at while Android defers the confirm dialog.
	 */
	private fun notifyReinstallPending() {
		try {
			deploy.notifyBuildStatus(BuildStatusJson.reinstallPending())
		} catch (e: Exception) {
			log.warn("Reinstall-pending notification failed", e)
		}
	}

	/**
	 * Rebuilds the proxy app and moves the live session onto the new baseline.
	 *
	 * @param startEpoch the session epoch read when this effect fired; a mismatch means
	 *   the session this rebuild was for is gone and its orchestrator must not be poked
	 */
	private suspend fun rebuildProxyApp(startEpoch: Long) {
		val session = live ?: return
		// Captured before ProxyAppRebuildStarted moves the session to Provisioning, which
		// carries neither the reason nor the deployed generation: a retry that never gets
		// the Gradle slot has to park back exactly where it came from.
		val rebuildPark = _state.value as? QuickBuildSessionState.Invalidated
		val installRetryPark =
			rebuildPark?.takeIf { it.reason == InvalidationReason.INSTALL_NOT_CONFIRMED }
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
					surfaceUserMessage(QuickBuildMessage.ReinstallWaitingForGradle)
					notifyReinstallPending()
					dispatch(SessionEvent.ProxyAppRebuildDeferred(installRetryPark.deployedGeneration))
				} else {
					// A first rebuild has no park to return to and no budget to
					// protect, so report it like any other proxy-app-build failure.
					session.orchestrator.onProxyAppRebuildFailed()
					dispatch(SessionEvent.ProvisioningFailed(QuickBuildMessage.RebuildFailed))
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
					// The reinstalled APK boots at its stamp; the session's allocator must
					// stay strictly above it or the runtime rejects every later deploy.
					session.tracker.adoptAtLeast(result.baselineGeneration)
					session.adoptBaseline(
						result.proxyApp,
						result.layout,
						executorDelegate,
						annotationImpactDelegate,
						result.baselineGeneration,
					)
					// A rebuild that skipped the reinstall (bytes already matched, e.g. the
					// deferred confirm completed while parked) leaves the old process - and
					// any reinstall-pending banner - running; clear it explicitly. After a
					// real reinstall the send just misses the dead connection, harmlessly.
					try {
						deploy.notifyBuildStatus(BuildStatusJson.buildOk())
					} catch (e: Exception) {
						log.warn("Post-rebuild status clear failed", e)
					}
					dispatch(SessionEvent.ProvisioningSucceeded(result.baselineGeneration))
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: Throwable) {
					log.error("Re-baselining after a successful proxy app rebuild threw", e)
					session.orchestrator.onProxyAppRebuildFailed()
					dispatch(SessionEvent.ProvisioningFailed(QuickBuildMessage.Literal(e.message ?: e.javaClass.name)))
				}
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.DaemonRestartFailed -> {
				log.error("Daemon restart after a proxy app rebuild failed: {}", result.message)
				session.orchestrator.onProxyAppRebuildFailed()
				dispatch(SessionEvent.ProvisioningFailed(QuickBuildMessage.DaemonRestartFailed(result.message)))
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.Failed -> {
				// Nothing was absorbed - the Gradle build never produced a baseline - so the held
				// batch goes back to pending and invalidation re-arms. That re-arming is what lets
				// the save of the FIX re-report the invalidation and retry; without it the park
				// below would wait forever for a tap the user has no reason to make. It emits no
				// event, so a still-broken build file does not loop.
				session.orchestrator.onProxyAppRebuildFailed()
				val park = rebuildPark
				if (park != null) {
					// Park recoverable instead of dying to Idle: the session and the running proxy
					// app are both fine; what failed is the user's build files. The message is
					// surfaced here rather than through SurfaceProvisioningError, whose effect
					// tears the session down.
					surfaceUserMessage(result.message)
					dispatch(SessionEvent.ProxyAppRebuildFailed(park.reason, park.deployedGeneration))
				} else {
					// No invalidation to park back into (a rebuild from an unexpected state):
					// fail provisioning rather than invent a park with no reason.
					dispatch(SessionEvent.ProvisioningFailed(result.message))
				}
			}

			is ProxyAppBuildRunner.ProxyAppRebuildResult.InstallNotConfirmed -> {
				// The Gradle build was fine and only the reinstall confirmation is
				// missing, so park recoverable instead of dying to Idle; the message
				// already says how to recover. Deliberately not onProxyAppRebuildFailed:
				// the orchestrator keeps holding the absorbed batch, and every held file
				// is on disk for the retry's Gradle build to absorb.
				log.warn("Proxy app rebuild reinstall not confirmed; awaiting a retry: {}", result.message)
				surfaceUserMessage(result.message)
				notifyReinstallPending()
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

	/**
	 * Answers a below-deployed reconnect by re-sending the retained last-deployed payload at
	 * its original generation, instead of rebuilding bytes the session already holds.
	 *
	 * Replayable only when the retained generation IS the deploy tally: an older retained set
	 * (a later deploy whose retention write failed) would leave the app still behind with
	 * nothing left to notice it. Every failure just reports false and the caller falls back
	 * to the forced catch-up build, so this path can never make recovery worse - only cheaper.
	 *
	 * @param session the live session whose retention to read
	 * @param runningGeneration what the reconnected app reports running
	 * @return true when the app confirmed the re-sent payload and no build is needed
	 */
	private suspend fun resendRetainedPayload(
		session: LiveSession,
		runningGeneration: Long,
	): Boolean {
		val retained = session.retainedPayloads.load() ?: return false
		if (retained.generation != session.lastDeployedGeneration) return false
		log.info(
			"Proxy app reconnected at generation {} but the session deployed {}; re-sending the retained payload",
			runningGeneration,
			retained.generation,
		)
		val result =
			deploy.deploy(
				retained.generation,
				retained.dexFile,
				retained.arscFile,
				retained.assetsZip,
				retained.metadataJson,
			)
		if (result is DeployResult.Reloaded) return true
		log.warn(
			"Re-send of retained generation {} failed ({}); falling back to a catch-up build",
			retained.generation,
			result,
		)
		return false
	}

	/**
	 * Restarts a dead daemon and re-seeds the orchestrator against it.
	 *
	 * @param startEpoch the daemon epoch read when this effect fired, not the session
	 *   epoch; the controller's exactly-one-transition rule is stated against it
	 */
	private suspend fun respawnDaemon(startEpoch: Long) {
		val session = live ?: return
		when (val outcome = daemonController.respawn(session.layout, session.proxyApp, startEpoch)) {
			is QuickBuildDaemonController.RespawnOutcome.Respawned -> {
				dispatch(SessionEvent.DaemonRespawned)
				// A fresh daemon has no trustworthy incremental state. With nothing
				// pending this re-warms via a deploy-nothing warm compile, leaving the
				// proxy app on its current generation; with pending work it marks the
				// baseline dirty so the next build recompiles everything and deploys. Not
				// gated on [warmCompileEnabled]: this repairs a daemon that lost its state.
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
				// auto-retrying a hard-broken daemon would just spin. The event schedules
				// nothing either - it stops the status claiming a restart is still under way,
				// which is the half the snackbar cannot fix.
				dispatch(SessionEvent.DaemonRestartFailed)
				surfaceUserMessage(QuickBuildMessage.DaemonRestartFailed(outcome.message))
			}
		}
	}

	/**
	 * Tears down the live session and any in-flight provision, prebuild, or rebuild.
	 *
	 * The epoch bump and cancel pair is what makes "Restart session" safe mid-provisioning:
	 * without it a provision resuming after the restart would set [live], start its
	 * watcher, and deploy invisibly behind an Idle UI, and the next tap would overwrite
	 * [live] leaving that watcher orphaned. Cancelling [sessionWork] from inside it is safe.
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
		teardownWork =
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

	/**
	 * Sends failure text to [userMessages].
	 *
	 * @param message user-facing failure text; this is the error channel, so anything that
	 *   is not a failure belongs in [surfaceNotice] instead
	 */
	private fun surfaceUserMessage(message: QuickBuildMessage) {
		_userMessages.tryEmit(message)
	}

	/**
	 * Sends a non-failure notice to [notices].
	 *
	 * @param notice the neutral notice; dropped silently when the buffer is full, since a
	 *   stale notice is worth less than the newest one
	 */
	private fun surfaceNotice(notice: QuickBuildNotice) {
		_notices.tryEmit(notice)
	}

	private companion object {
		private val log = LoggerFactory.getLogger("QB-SessionManager")

		/**
		 * Oldest a deferred foreground ask may be and still be answered when the full build
		 * lands. Manual QA (2026-08-13, F5) saw a rebaseline settle a 34-second-old ask on
		 * top of a user who had deliberately returned to the editor mid-typing; past ~10 s
		 * the ask no longer says anything about where the user wants to be.
		 */
		private const val DEFERRED_FOREGROUND_ASK_MAX_AGE_MILLIS = 10_000L

		/**
		 * How long a tap armed on its save-all's watcher batch waits before switching anyway.
		 *
		 * The coalescer emits at most 250 ms after the last file event (1 s cap from the
		 * first), so 2 s comfortably covers watcher, coalescer and dispatch latency; a batch
		 * still absent by then means the save-all wrote only watcher-irrelevant files and no
		 * batch is coming, and the tap must not go unanswered.
		 */
		private const val TAP_SWITCH_FALLBACK_MILLIS = 2_000L
	}
}
