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
import org.appdevforall.cotg.quickbuild.data.DaemonConfig
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.FileGenerationStore
import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.domain.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.QuickBuildNotice
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.SessionEffect
import org.appdevforall.cotg.quickbuild.domain.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.SessionReducer
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.WatcherBatchReconciler
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationBaseline
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpactAnalyzer
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationProcessorProfile
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact
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
	 * Counts intentional daemon lifecycle transitions: every `daemon.start`/`daemon.shutdown`
	 * this manager initiates outside the respawn path (provisioning start + its undo,
	 * proxy-app-rebuild teardown + restart, session teardown, low-memory shrink).
	 *
	 * [respawnDaemon] captures it at effect time and re-checks after its `daemon.start`
	 * returns: any change means an intentional shutdown superseded the respawn mid-flight
	 * (2026-07-26 review finding 2 - a proxy app rebuild's `daemon.shutdown()` racing an in-flight
	 * respawn), so the respawn discards its result instead of dispatching
	 * [SessionEvent.DaemonRespawned] and poking the orchestrator. EXACTLY one transition
	 * since capture is the superseding shutdown itself, so a daemon the stale start brought
	 * up is a zombie only the respawn knows about - it stops it (the daemon must not coexist
	 * with the proxy app rebuild's Gradle build, nor outlive a teardown). More than one means a
	 * successor flow already started a fresh daemon the stale respawn must not touch.
	 * Only touched on [dispatcher].
	 */
	private var daemonEpoch = 0L

	private class LiveSession(
		/** Mutable: a proxy app rebuild regenerates setup.json and must move this snapshot. */
		var proxyApp: ProxyAppInfo,
		var layout: QuickBuildProjectLayout,
		val tracker: GenerationTracker,
		val filter: WatchFilter,
		val orchestrator: LiveReloadOrchestrator,
		val watcher: ProjectWatcher,
		/** Seam the proxy app rebuild swaps a fresh ProxyAppInfo-derived executor into. */
		val executor: SwitchableExecutor,
		/** Seam the proxy app rebuild swaps a fresh annotation baseline into. */
		val annotationImpact: SwitchableAnnotationImpact,
	) {
		/**
		 * Newest generation a deploy verifiably landed in this session, or -1 before the
		 * first one. The reconnect catch-up compares against THIS (not the allocation
		 * counter, which persists across sessions and burns numbers on failed builds):
		 * a proxy app reconnecting below it is running code this session already
		 * superseded.
		 */
		var lastDeployedGeneration = -1L
	}

	/**
	 * Executor indirection for [LiveSession]: the orchestrator holds one executor for
	 * its lifetime, but a proxy app rebuild must rebuild the executor from the re-read
	 * setup.json (new deploy-policy components, launcher/entry targets). Swapping the
	 * delegate keeps the orchestrator - and its pending-changes bookkeeping - intact.
	 */
	private class SwitchableExecutor(
		@Volatile var delegate: LiveReloadExecutor,
	) : LiveReloadExecutor {
		override suspend fun execute(request: BuildRequest): BuildOutcome = delegate.execute(request)
	}

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
			// Retries a low-memory teardown that [shrinkDaemonForMemory] deferred while a
			// build was in flight, the moment that build's own transition lands (success,
			// failure, or a real daemon death all move the state away from Building).
			_state.collect {
				if (pendingLowMemoryTeardown) shrinkDaemonForMemory()
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
		scope.launch { handleTrimMemory(level) }
	}

	/** Set only on [dispatcher]; a build in flight defers the real teardown to here. */
	private var pendingLowMemoryTeardown = false

	private suspend fun handleTrimMemory(level: Int) {
		if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
			log.debug("Quick Build: onTrimMemory({}) below the shrink threshold; no-op", level)
			return
		}
		if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
			// Not memory pressure - the user just switched away (typically to their own
			// proxy app, mid-loop). Keep the daemon warm; see this function's KDoc.
			log.debug("Quick Build: onTrimMemory(UI_HIDDEN); keeping the daemon warm")
			return
		}
		pendingLowMemoryTeardown = true
		shrinkDaemonForMemory()
	}

	/**
	 * Tears the daemon down for memory pressure, unless a build is in flight - then this
	 * is a no-op that leaves [pendingLowMemoryTeardown] set for the `init` state collector
	 * to retry once that build's own transition lands. Idempotent: a daemon already down,
	 * or no pending request at all, is a silent no-op either way - safe to call from a
	 * repeated `onTrimMemory(CRITICAL)` or from the retry collector alike.
	 */
	private suspend fun shrinkDaemonForMemory() {
		if (_state.value is QuickBuildSessionState.Building) return
		if (!pendingLowMemoryTeardown) return
		pendingLowMemoryTeardown = false
		if (!daemon.isRunning) return
		log.info("Quick Build: tearing down the compile daemon for low memory; the next build re-warms it")
		daemonEpoch++
		daemon.shutdown()
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
				val epoch = daemonEpoch
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
		// Disk-space guard (ADFA-4930): fail in seconds with a clear message rather than
		// let a full private volume ENOSPC minutes into the proxy app build or mid-quick-build.
		scratch.freeSpaceShortfall()?.let { message ->
			dispatch(SessionEvent.ProvisioningFailed(message))
			return
		}

		val outcome =
			try {
				provisioner.provision()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Throwable) {
				log.error("Provisioner threw instead of reporting an outcome", e)
				ProvisionOutcome.Failure(e.message ?: e.javaClass.name)
			}

		if (startEpoch != sessionEpoch) {
			// "Restart session" landed while the proxy app build ran; the user asked for a
			// fresh start, so a late success must not resurrect (and a late failure must
			// not surface) - see the zombie-session scenario in the teardown KDoc.
			log.info("Quick-build provisioning outlived a session restart; discarding")
			return
		}

		when (outcome) {
			is ProvisionOutcome.Failure -> {
				dispatch(SessionEvent.ProvisioningFailed(outcome.message))
			}

			is ProvisionOutcome.Success -> {
				// Scratch tree on app-private storage (ADFA-4930): the executor and daemon
				// dirs below live here, never under the FUSE-backed project root.
				when (val prepared = scratch.prepare(outcome.layout.projectRoot)) {
					is QuickBuildScratch.Preparation.Failed -> {
						dispatch(SessionEvent.ProvisioningFailed(prepared.message))
						return
					}

					is QuickBuildScratch.Preparation.Ready -> {
						Unit
					}
				}

				connections.beginSession(outcome.proxyApp.proxyAppPackage, outcome.proxyAppUid)

				daemonEpoch++
				when (val started = daemon.start(daemonConfig(outcome.layout, outcome.proxyApp))) {
					is DaemonReply.Ok -> {
						if (startEpoch != sessionEpoch) {
							// Restart raced the daemon start: undo, don't go live.
							log.info("Session restarted during daemon start; shutting down")
							connections.endSession()
							daemonEpoch++
							scope.launch { daemon.shutdown() }
							return
						}
						val tracker =
							GenerationTracker(generationStoreFactory(outcome.layout.projectRoot))
						val session = createSession(outcome, tracker)
						live = session
						// Build ids restart per session; give the sink its session boundary.
						report { metrics.onSessionStarted() }
						// Trigger on file change from any source (editor, Termux, plugin,
						// git pull) - the reload path is change-driven, not save-driven.
						session.watcher.start(::onWatcherBatch)
						dispatch(SessionEvent.ProvisioningSucceeded(tracker.current))
					}

					is DaemonReply.BuildFailed -> {
						dispatch(SessionEvent.ProvisioningFailed("Daemon rejected configuration"))
					}

					is DaemonReply.Failed -> {
						dispatch(SessionEvent.ProvisioningFailed(started.message))
					}
				}
			}
		}
	}

	private fun createSession(
		outcome: ProvisionOutcome.Success,
		tracker: GenerationTracker,
	): LiveSession {
		val layout = outcome.layout
		val proxyApp = outcome.proxyApp
		val executor = SwitchableExecutor(buildExecutor(proxyApp, layout, tracker))
		val annotationImpact = SwitchableAnnotationImpact(annotationImpact(proxyApp, layout))
		val orchestrator =
			LiveReloadOrchestrator(
				executor = executor,
				classifier = ChangeClassifier(annotationImpact, layout.fastPathScope()),
				scope = scope,
				// Same monotonic timebase the executor stamps t1-t3 with, so the e2e
				// timeline's t0 (trigger) is comparable to the rest (see E2eTimeline).
				now = nowMillis,
				onEvent = ::onOrchestratorEvent,
			)
		val filter = WatchFilter(layout.watchedRoots(), layout.watchedFiles())
		return LiveSession(
			proxyApp = outcome.proxyApp,
			layout = layout,
			tracker = tracker,
			filter = filter,
			orchestrator = orchestrator,
			watcher = watcherFactory.create(layout.watchedRoots(), layout.watchedFiles(), filter, scope),
			executor = executor,
			annotationImpact = annotationImpact,
		)
	}

	/**
	 * Annotation-processor awareness for this session's baseline. A project with no
	 * `ksp`/`kapt`/`annotationProcessor` dependency gets [AnnotationImpact.Inactive] and
	 * behaves exactly as before; otherwise the classifier compares each edit against the
	 * annotation input the proxy app build actually ran against, and only edits that could
	 * have moved generated code pay a proxy app rebuild.
	 *
	 * Rebuilt on every proxy app rebuild too (see [SwitchableAnnotationImpact]): the Gradle build
	 * that just ran IS the new reference point.
	 */
	private fun annotationImpact(
		proxyApp: ProxyAppInfo,
		layout: QuickBuildProjectLayout,
	): AnnotationImpact {
		val profile = AnnotationProcessorProfile.of(proxyApp.annotationProcessors)
		if (!profile.hasProcessors) return AnnotationImpact.Inactive
		log.info(
			"Quick build: annotation-aware classification on for processors {}",
			profile.processorCoordinates,
		)
		return AnnotationImpactAnalyzer(profile, AnnotationBaseline.capture(layout.allSources(), profile))
	}

	/** ProxyAppInfo-derived executor; rebuilt (and swapped in) on every proxy app rebuild. */
	private fun buildExecutor(
		proxyApp: ProxyAppInfo,
		layout: QuickBuildProjectLayout,
		tracker: GenerationTracker,
	): LiveReloadExecutor =
		executorFactory?.create(proxyApp, layout, tracker)
			?: LiveReloadExecutorImpl(
				daemon = daemon,
				deploy = deploy,
				layout = layout,
				// A session only reaches here off ProvisionOutcome.Success, which the
				// provisioner never produces for a null entryActivity (ADFA-4128 Bug 10) -
				// it refuses with a friendly message first. See ProxyAppInfo.entryActivity.
				entryActivity =
					checkNotNull(proxyApp.entryActivity) {
						"Quick Build session started without an entry activity"
					},
				generations = tracker,
				// App-private scratch (ADFA-4930), NOT under the FUSE-backed project root.
				workDir = scratch.workDirFor(layout.projectRoot),
				proxyClassesDir = proxyApp.proxyClassesDir,
				proxyAppManifest = proxyApp.transformedManifest,
				deployPolicy =
					DeployPolicy(
						components = proxyApp.components,
						// Pre-v2 setup.json (no schema/components) = a baseline whose
						// runtime ignores restart deploys; the policy then routes
						// restart-requiring builds to a proxy app rebuild (skew guard).
						componentInfoAvailable = proxyApp.supportsComponentInfo,
					),
				proxyAppPackage = proxyApp.proxyAppPackage,
				launcherActivity =
					proxyApp.components
						.firstOrNull { it.kind == ComponentKind.ACTIVITY && it.launcher }
						?.proxyClass,
				launcher = launcher,
				// Monotonic device clock for durationMillis + the e2e timeline stamps; the
				// orchestrator's `now` above shares it so all four stamps are comparable.
				clock = nowMillis,
				// The e2e reload timing is an analytics deliverable (ADFA-4128): the executor
				// reports each completed timeline to the same sink the lifecycle events use.
				metrics = metrics,
			)

	private fun daemonConfig(
		layout: QuickBuildProjectLayout,
		proxyApp: ProxyAppInfo,
	): DaemonConfig =
		DaemonConfig(
			projectRoot = layout.projectRoot,
			classpath = layout.compileClasspath(),
			// App-private scratch (ADFA-4930): the daemon's per-file-heavy output tree is
			// the single biggest FUSE payer, and its scratchFsType reply (the bench-event
			// field) reports whatever filesystem THIS dir lands on.
			outDir = scratch.outDirFor(layout.projectRoot),
			aapt2 = paths.aapt2,
			d8Jar = paths.d8Jar,
			androidJar = paths.androidJar,
			compilerPlugins =
				if (proxyApp.composeEnabled) listOf(paths.composeCompilerPlugin) else emptyList(),
		)

	/** Delivered synchronously on [dispatcher] by the orchestrator; hop to a launch. */
	private fun onOrchestratorEvent(event: OrchestratorEvent) {
		scope.launch {
			when (event) {
				is OrchestratorEvent.BuildStarted -> {
					report { metrics.onBuildStarted(event.buildId, event.route, event.changes) }
					if (event.route is BuildRoute.WarmCompile) {
						// A warm compile compiles the sources the proxy app ALREADY runs and
						// deploys nothing; telling either surface "one generation
						// behind, building" would be a lie. WarmCompileStarted keeps the IDE
						// status on "up to date" (Building(warmingCompiler = true)).
						dispatch(SessionEvent.WarmCompileStarted)
					} else {
						dispatch(SessionEvent.BuildStarted)
						notifyBuilding()
					}
				}

				is OrchestratorEvent.BuildSucceeded -> {
					report { metrics.onBuildFinished(event.buildId, event.result) }
					if (event.route is BuildRoute.WarmCompile) {
						// Nothing deployed, generation unmoved: no Deployed state, no
						// lastDeployedGeneration bump.
						dispatch(SessionEvent.WarmCompileFinished)
						return@launch
					}
					live?.let {
						it.lastDeployedGeneration = maxOf(it.lastDeployedGeneration, event.result.generation)
					}
					dispatch(
						SessionEvent.BuildSucceeded(
							event.result.generation,
							event.result.durationMillis,
							event.result.restarted,
							userInitiated = event.userInitiated,
						),
					)
				}

				is OrchestratorEvent.BuildFailed -> {
					report { metrics.onBuildFinished(event.buildId, event.outcome) }
					val outcome = event.outcome
					if (outcome is BuildOutcome.RequiresProxyAppRebuild) {
						// The build was fine but the baseline cannot take the deploy (a
						// restart-requiring change on a pre-restart baseline). Route into
						// the existing proxy-app-rebuild fallback; the orchestrator already put
						// the changed set back into pending, so the proxy app rebuild absorbs it.
						log.info("Quick build routed to a proxy app rebuild: {}", outcome.detail)
						report { metrics.onInvalidation(outcome.reason) }
						dispatch(SessionEvent.InvalidationDetected(outcome.reason))
					} else if (outcome is BuildOutcome.InfrastructureFailure && outcome.daemonDied) {
						// Includes a daemon death mid-warm-compile: the normal respawn recovery
						// re-seeds with ChangedFiles.Unknown, so no warm-compile-specific path.
						dispatch(SessionEvent.DaemonDied)
					} else if (event.route is BuildRoute.WarmCompile) {
						// A failed warm compile is invisible by design: the proxy app build just
						// compiled these sources green, and the next real save compiles
						// the full source set anyway. Log for diagnosis, surface nothing.
						log.warn("Background warm compile failed (not surfaced): {}", outcome)
						dispatch(SessionEvent.WarmCompileFinished)
					} else {
						dispatch(SessionEvent.BuildFailed(outcome.toSessionFailure()))
					}
				}

				is OrchestratorEvent.InvalidationRequired -> {
					report { metrics.onInvalidation(event.reason) }
					dispatch(SessionEvent.InvalidationDetected(event.reason))
				}
			}
		}
	}

	/**
	 * Honesty line while a build is in flight (WS-G): tells the proxy app it is one
	 * generation behind while the new one compiles, so a slow build never reads as
	 * silence. Prefers [LiveSession.lastDeployedGeneration] - the session's own tally,
	 * kept current across every hot-swap deploy - over the connected target's
	 * self-reported generation, which is only fresh at connect time and goes stale the
	 * moment a hot swap lands without a rebind. Falls back to the connection's value
	 * before this session has deployed anything (a fresh proxy-app install has no tally
	 * yet, but it did tell us its baseline generation at connect). No connection and no
	 * tally means nothing truthful to say - skip silently, like every other best-effort
	 * status push.
	 */
	private fun notifyBuilding() {
		val session = live ?: return
		val runningGeneration =
			session.lastDeployedGeneration.takeIf { it >= 0 }
				?: connections.target.value?.runningGeneration
				?: return
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

		// Free the daemon's ~0.5GB for the Gradle build that is about to peak - on the
		// 3-4GB target class the two must not coexist. Costless: the daemon's IC state
		// is untrustworthy after a proxy app rebuild anyway (regenerated inputs it never saw),
		// and it was going to be re-seeded from scratch regardless; on success it
		// restarts below with the NEW proxy app info's config (the survivor used to keep serving
		// the OLD configure's classpath - correct only via BTA's full-recompile
		// fallback). The epoch bump discards a daemon respawn still in flight (a
		// proxy app rebuild can start from Degraded); see [daemonEpoch].
		daemonEpoch++
		daemon.shutdown()

		val startedAtNanos = System.nanoTime()
		val outcome =
			try {
				provisioner.rebuildProxyApp()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Throwable) {
				log.error("Proxy app rebuild threw instead of reporting an outcome", e)
				ProxyAppRebuildOutcome.Failure(e.message ?: e.javaClass.name)
			}
		if (outcome !is ProxyAppRebuildOutcome.BuildSlotBusy || installRetryPark == null) {
			// Only a DEFERRED retry (slot busy while parked) skips metrics: it never ran,
			// so reporting it would book a 0 ms failed proxy app rebuild against the success rate
			// for work that never happened. A FIRST proxy app rebuild losing the slot IS surfaced
			// to the user as a failed proxy app rebuild, so it books like one.
			report {
				metrics.onProxyAppRebuild(
					isSuccess = outcome is ProxyAppRebuildOutcome.Success,
					durationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000,
				)
			}
		}

		if (startEpoch != sessionEpoch) {
			// The session this proxy app rebuild was for is gone; don't poke its orchestrator.
			log.info("Quick-build proxy app rebuild outlived a session restart; discarding")
			return
		}

		when (outcome) {
			is ProxyAppRebuildOutcome.BuildSlotBusy -> {
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

			is ProxyAppRebuildOutcome.Success -> {
				// The proxy app rebuild regenerated setup.json and reinstalled the proxy app:
				// every ProxyAppInfo-derived piece of the session (deploy-policy components,
				// componentInfoAvailable, launcher/entry targets, classpath) must move to
				// the new baseline, or the policy keeps routing on provisioning-time
				// facts - e.g. a service the proxy app rebuild just proxied would hot-swap and
				// silently leave its live instance stale.
				session.proxyApp = outcome.proxyApp
				session.layout = outcome.layout
				session.executor.delegate = buildExecutor(outcome.proxyApp, outcome.layout, session.tracker)
				session.annotationImpact.delegate = annotationImpact(outcome.proxyApp, outcome.layout)
				// Restart the daemon torn down above, against the NEW proxy app info's config.
				daemonEpoch++
				when (val started = daemon.start(daemonConfig(outcome.layout, outcome.proxyApp))) {
					is DaemonReply.Ok -> {
						Unit
					}

					else -> {
						val message = (started as? DaemonReply.Failed)?.message ?: "daemon rejected configuration"
						log.error("Daemon restart after a proxy app rebuild failed: {}", message)
						session.orchestrator.onProxyAppRebuildFailed()
						dispatch(SessionEvent.ProvisioningFailed(message))
						return
					}
				}
				// The freshly installed baseline boots gen 0 again; the fingerprint gate
				// in its runtime discarded any older persisted payload.
				session.lastDeployedGeneration = -1L
				session.orchestrator.onBaselineReset()
				dispatch(SessionEvent.ProvisioningSucceeded(session.tracker.current))
			}

			is ProxyAppRebuildOutcome.Failure -> {
				session.orchestrator.onProxyAppRebuildFailed()
				dispatch(SessionEvent.ProvisioningFailed(outcome.message))
			}

			is ProxyAppRebuildOutcome.InstallNotConfirmed -> {
				// The Gradle build was fine; only the reinstall confirmation is missing
				// (no dialog shown / cancelled / left untapped - the stranded-session
				// finding from the multi-module device verify). Park recoverable instead
				// of dying to Idle; the message already says how to recover for its
				// specific case. Deliberately NOT onProxyAppRebuildFailed(): the orchestrator
				// keeps holding the absorbed batch, so quick builds stay suspended while
				// the daemon is down (a live-reload save here would only fail against the
				// dead daemon); the retry's onProxyAppRebuildStarted re-holds pending on
				// top, and every held file is on disk for its Gradle build to absorb.
				log.warn("Proxy app rebuild reinstall not confirmed; awaiting a retry: {}", outcome.message)
				surfaceUserMessage(outcome.message)
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
		if (proxyAppArtifactsIntact(session.proxyApp)) {
			session.orchestrator.onBaselineUntrusted()
		} else {
			log.warn("Proxy app build artifacts missing after an external build; forcing a proxy app rebuild")
			dispatch(SessionEvent.InvalidationDetected(InvalidationReason.EXTERNAL_FULL_BUILD))
		}
	}

	private fun proxyAppArtifactsIntact(proxyApp: ProxyAppInfo): Boolean =
		proxyApp.classpath.all { it.exists() } &&
			proxyApp.proxyClassesDir?.isDirectory != false &&
			proxyApp.transformedManifest?.isFile != false

	private suspend fun respawnDaemon(startEpoch: Long) {
		val session = live ?: return
		if (startEpoch != daemonEpoch) {
			// An intentional daemon transition already superseded this respawn before it
			// even started; the successor flow owns the daemon lifecycle.
			log.info("Quick-build daemon respawn superseded before start; discarding")
			return
		}
		val started = daemon.start(daemonConfig(session.layout, session.proxyApp))
		if (startEpoch != daemonEpoch) {
			// An intentional shutdown (proxy-app-rebuild teardown, session teardown, low-memory
			// shrink) landed while this respawn's start was in flight (2026-07-26 review
			// finding 2). The superseding flow owns the daemon lifecycle now: dispatching
			// DaemonRespawned or poking the orchestrator here would corrupt it. See
			// [daemonEpoch] for the exactly-one-transition cleanup rule.
			if (started is DaemonReply.Ok && daemonEpoch == startEpoch + 1) {
				log.info("Quick-build daemon respawn outlived an intentional shutdown; stopping its daemon")
				daemon.shutdown()
			} else {
				log.info("Quick-build daemon respawn outlived a daemon restart; discarding")
			}
			return
		}
		when (started) {
			is DaemonReply.Ok -> {
				dispatch(SessionEvent.DaemonRespawned)
				// A fresh daemon has no trustworthy IC state. With nothing pending this
				// re-warms via a deploy-nothing WarmCompile (the proxy app keeps running its
				// current generation untouched); with pending work it marks the baseline
				// dirty so the next build recompiles everything and deploys.
				session.orchestrator.onDaemonReplaced()
			}

			else -> {
				val message = (started as? DaemonReply.Failed)?.message ?: "unknown failure"
				log.error("Daemon respawn failed: {}", message)
				// Stay Degraded (honest); the next explicit tap or session restart
				// retries. Auto-retry loops on a hard-broken daemon would spin.
				surfaceUserMessage("Quick Build daemon could not be restarted: $message")
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
		daemonEpoch++
		sessionWork?.cancel()
		sessionWork = null
		live?.watcher?.stop()
		val scratchOwner = live?.layout?.projectRoot
		live = null
		connections.endSession()
		scope.launch {
			daemon.shutdown()
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

	private fun BuildOutcome.toSessionFailure(): SessionFailure =
		when (this) {
			is BuildOutcome.CompileError -> SessionFailure.CompileError(diagnostics)

			is BuildOutcome.DeployFailure -> SessionFailure.DeployError(message)

			is BuildOutcome.InfrastructureFailure -> SessionFailure.DeployError(message)

			// Handled as an invalidation before this mapping; keep it total anyway.
			is BuildOutcome.RequiresProxyAppRebuild -> SessionFailure.DeployError(detail)

			// Success never reaches BuildFailed; keep the mapping total anyway.
			is BuildOutcome.Success -> SessionFailure.DeployError("unexpected success in failure path")
		}

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildSessionManager::class.java)
	}
}
