package org.appdevforall.cotg.quickbuild.service

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
import org.appdevforall.cotg.quickbuild.data.SetupInfo
import org.appdevforall.cotg.quickbuild.domain.BuildOrchestrator
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.QuickBuildExecutor
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.SessionEffect
import org.appdevforall.cotg.quickbuild.domain.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.SessionReducer
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationBaseline
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpactAnalyzer
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationProcessorProfile
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The shell around the domain session machine (plan 2.1): owns the [SessionReducer],
 * the per-session [BuildOrchestrator] + [GenerationTracker], and turns reducer effects
 * into real work (provisioning, daemon respawn, Gradle re-baseline).
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
	private val connections: TestAppConnections,
	private val paths: QuickBuildPaths,
	/** Gates eager prewarm on project history (plan P7) and records first use. */
	private val modeStore: QuickBuildModeStore,
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
	 * Relaunches the test app after a restart deploy; the app wires an intent-based
	 * implementation. The default refuses, which the executor surfaces as a deploy
	 * failure ("open the app manually") instead of claiming a relaunch it cannot do.
	 */
	private val launcher: TestAppLauncher = TestAppLauncher { _, _ -> false },
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
			setup: SetupInfo,
			layout: QuickBuildProjectLayout,
			tracker: GenerationTracker,
		): QuickBuildExecutor
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

	private var live: LiveSession? = null

	/**
	 * Bumped by every [teardown]. In-flight provisioning/rebaseline work captures the
	 * epoch at launch and discards its result when they differ: a provision completing
	 * after "Restart session" must never install itself as a zombie session (watcher +
	 * daemon live while the UI shows Idle). Only touched on [dispatcher].
	 */
	private var sessionEpoch = 0L

	/** The in-flight provision/prewarm/rebaseline; cancelled by [teardown]. */
	private var sessionWork: Job? = null

	private class LiveSession(
		/** Mutable: a rebaseline regenerates setup.json and must move this snapshot. */
		var setup: SetupInfo,
		var layout: QuickBuildProjectLayout,
		val tracker: GenerationTracker,
		val filter: WatchFilter,
		val orchestrator: BuildOrchestrator,
		val watcher: ProjectWatcher,
		/** Seam the rebaseline swaps a fresh SetupInfo-derived executor into. */
		val executor: SwitchableExecutor,
		/** Seam the rebaseline swaps a fresh annotation baseline into. */
		val annotationImpact: SwitchableAnnotationImpact,
	) {
		/**
		 * Newest generation a deploy verifiably landed in this session, or -1 before the
		 * first one. The reconnect catch-up compares against THIS (not the allocation
		 * counter, which persists across sessions and burns numbers on failed builds):
		 * a test app reconnecting below it is running code this session already
		 * superseded.
		 */
		var lastDeployedGeneration = -1L
	}

	/**
	 * Executor indirection for [LiveSession]: the orchestrator holds one executor for
	 * its lifetime, but a rebaseline must rebuild the executor from the re-read
	 * setup.json (new deploy-policy components, launcher/entry targets). Swapping the
	 * delegate keeps the orchestrator - and its pending-changes bookkeeping - intact.
	 */
	private class SwitchableExecutor(
		@Volatile var delegate: QuickBuildExecutor,
	) : QuickBuildExecutor {
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
					dispatch(SessionEvent.TestAppCrashed(report.stackSummary))
				}
			}
		}
		scope.launch {
			// Reconnect catch-up: a killed-and-relaunched test app reports the
			// generation it booted; below what this session already deployed means it
			// is verifiably running superseded code (persisted payload lost/stale), so
			// force a rebuild of current sources at a fresh generation - the same path
			// as an explicit tap. Without this, nothing reacts to a stale reconnect
			// and the app would run old code silently until the next edit.
			connections.target.collect { target ->
				val session = live ?: return@collect
				if (target != null && target.runningGeneration < session.lastDeployedGeneration) {
					log.info(
						"Test app reconnected at generation {} but the session deployed {}; forcing a catch-up build",
						target.runningGeneration,
						session.lastDeployedGeneration,
					)
					session.orchestrator.onQuickBuildRequested()
				}
			}
		}
	}

	/**
	 * The lightning-bolt tap: starts a session from Idle, forces a build when live.
	 * Marks the project as a Quick Build user before dispatching - the signal
	 * [prewarm] checks on every later project open (plan P7).
	 */
	fun onQuickBuildTapped() {
		scope.launch {
			modeStore.setHasUsedQuickBuild(true)
			dispatch(SessionEvent.QuickBuildTapped)
		}
	}

	/**
	 * Eager warm-up (plan B2): call at project open, AFTER the normal Gradle sync
	 * completes, with the experimental flag on. Runs the setup build in the background
	 * so the first tap pays only install + bind; installs nothing. No-op unless Idle.
	 * A tap landing mid-warm queues and provisions when the warm build finishes.
	 *
	 * Gated on project history (plan P7): a project that has never tapped Quick Build
	 * gets no eager warm-up, since there's no signal it ever will (real battery cost on
	 * the low-end target hardware for a feature that's never used). The carve-out is
	 * same-app-id mode - enabling it is a strong signal the project is Quick-Build-first
	 * even before the very first tap, so it prewarms too. First-ever use per project
	 * pays the cold setup cost once; every later project open is warm.
	 */
	fun prewarm() {
		if (!modeStore.hasUsedQuickBuild() && !modeStore.isSameAppIdEnabled()) {
			return
		}
		scope.launch { dispatch(SessionEvent.PrewarmRequested) }
	}

	/**
	 * Mode-switch hand-back (plan B3): call when a Standard Run's Gradle build completes
	 * (e.g. from the A2 dropdown's "Standard Run", or the Run button's build-finished
	 * hook). A live session re-seeds its incremental snapshot from current disk - a full
	 * rebaseline when the external build clobbered the setup artifacts, otherwise a fresh
	 * incremental seed - so the next quick build is never stale. No session: no-op.
	 */
	fun onStandardRunCompleted() {
		scope.launch { dispatch(SessionEvent.ExternalBuildCompleted) }
	}

	/**
	 * Restart action (plan A2 dropdown "Restart session"): tears down the current live
	 * session and daemon and returns to Idle from whatever state the session is in. The
	 * next tap re-provisions from scratch - the escape hatch for a daemon or test app
	 * stuck past what a plain quick build or rebaseline can recover.
	 */
	fun restartSession() {
		scope.launch { dispatch(SessionEvent.SessionRestartRequested) }
	}

	/**
	 * A coalesced batch of changed files from the watcher (already filtered to relevant
	 * paths). Hopped onto [dispatcher]; the orchestrator + classifier decide the route
	 * (quick build vs. rebaseline) and handle concurrency with any in-flight build.
	 */
	private fun onWatcherBatch(files: Set<File>) {
		if (files.isEmpty()) return
		scope.launch {
			live?.orchestrator?.onFilesChanged(ChangedFiles.Known(files))
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
			SessionEffect.StartPrewarm -> sessionWork = scope.launch { runPrewarm() }
			SessionEffect.TriggerQuickBuild ->
				scope.launch { live?.orchestrator?.onQuickBuildRequested() }
			SessionEffect.RunFullGradleRebaseline -> {
				val epoch = sessionEpoch
				sessionWork = scope.launch { rebaseline(epoch) }
			}
			SessionEffect.ReseedBaseline -> scope.launch { reseedBaseline() }
			SessionEffect.RespawnDaemon -> scope.launch { respawnDaemon() }
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

	/** B2 warm-up: best-effort, silent on failure; always reports finished. */
	private suspend fun runPrewarm() {
		try {
			provisioner.warmSetupBuild()
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.warn("Eager quick-build setup build failed; first tap will retry", e)
		}
		dispatch(SessionEvent.PrewarmFinished)
	}

	private suspend fun provision(startEpoch: Long) {
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
			// "Restart session" landed while the setup build ran; the user asked for a
			// fresh start, so a late success must not resurrect (and a late failure must
			// not surface) - see the zombie-session scenario in the teardown KDoc.
			log.info("Quick-build provisioning outlived a session restart; discarding")
			return
		}

		when (outcome) {
			is ProvisionOutcome.Failure -> dispatch(SessionEvent.ProvisioningFailed(outcome.message))
			is ProvisionOutcome.Success -> {
				connections.beginSession(outcome.setup.testAppPackage, outcome.testAppUid)

				when (val started = daemon.start(daemonConfig(outcome.layout, outcome.setup))) {
					is DaemonReply.Ok -> {
						if (startEpoch != sessionEpoch) {
							// Restart raced the daemon start: undo, don't go live.
							log.info("Session restarted during daemon start; shutting down")
							connections.endSession()
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
					is DaemonReply.BuildFailed ->
						dispatch(SessionEvent.ProvisioningFailed("Daemon rejected configuration"))
					is DaemonReply.Failed ->
						dispatch(SessionEvent.ProvisioningFailed(started.message))
				}
			}
		}
	}

	private fun createSession(
		outcome: ProvisionOutcome.Success,
		tracker: GenerationTracker,
	): LiveSession {
		val layout = outcome.layout
		val setup = outcome.setup
		val executor = SwitchableExecutor(buildExecutor(setup, layout, tracker))
		val annotationImpact = SwitchableAnnotationImpact(annotationImpact(setup, layout))
		val orchestrator =
			BuildOrchestrator(
				executor = executor,
				classifier = ChangeClassifier(annotationImpact),
				scope = scope,
				onEvent = ::onOrchestratorEvent,
			)
		val filter = WatchFilter(layout.watchedRoots(), layout.watchedFiles())
		return LiveSession(
			setup = outcome.setup,
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
	 * annotation input the setup build actually ran against, and only edits that could
	 * have moved generated code pay a rebaseline.
	 *
	 * Rebuilt on every re-baseline too (see [SwitchableAnnotationImpact]): the Gradle build
	 * that just ran IS the new reference point.
	 */
	private fun annotationImpact(
		setup: SetupInfo,
		layout: QuickBuildProjectLayout,
	): AnnotationImpact {
		val profile = AnnotationProcessorProfile.of(setup.annotationProcessors)
		if (!profile.hasProcessors) return AnnotationImpact.Inactive
		log.info(
			"Quick build: annotation-aware classification on for processors {}",
			profile.processorCoordinates,
		)
		return AnnotationImpactAnalyzer(profile, AnnotationBaseline.capture(layout.allSources(), profile))
	}

	/** SetupInfo-derived executor; rebuilt (and swapped in) on every rebaseline. */
	private fun buildExecutor(
		setup: SetupInfo,
		layout: QuickBuildProjectLayout,
		tracker: GenerationTracker,
	): QuickBuildExecutor =
		executorFactory?.create(setup, layout, tracker)
			?: QuickBuildExecutorImpl(
				daemon = daemon,
				deploy = deploy,
				layout = layout,
				entryActivity = setup.entryActivity,
				generations = tracker,
				workDir = File(layout.projectRoot, ".androidide/quickbuild"),
				proxyClassesDir = setup.proxyClassesDir,
				testAppManifest = setup.transformedManifest,
				deployPolicy =
					DeployPolicy(
						components = setup.components,
						// Pre-v2 setup.json (no schema/components) = a baseline whose
						// runtime ignores restart deploys; the policy then routes
						// restart-requiring builds to a rebaseline (skew guard).
						componentInfoAvailable = setup.schema >= COMPONENT_SCHEMA_VERSION,
					),
				testAppPackage = setup.testAppPackage,
				launcherActivity =
					setup.components
						.firstOrNull { it.kind == ComponentKind.ACTIVITY && it.launcher }
						?.proxyClass,
				launcher = launcher,
			)

	private fun daemonConfig(
		layout: QuickBuildProjectLayout,
		setup: SetupInfo,
	): DaemonConfig =
		DaemonConfig(
			projectRoot = layout.projectRoot,
			classpath = layout.compileClasspath(),
			outDir = File(layout.projectRoot, ".androidide/quickbuild/out"),
			aapt2 = paths.aapt2,
			d8Jar = paths.d8Jar,
			androidJar = paths.androidJar,
			compilerPlugins =
				if (setup.composeEnabled) listOf(paths.composeCompilerPlugin) else emptyList(),
		)

	/** Delivered synchronously on [dispatcher] by the orchestrator; hop to a launch. */
	private fun onOrchestratorEvent(event: OrchestratorEvent) {
		scope.launch {
			when (event) {
				is OrchestratorEvent.BuildStarted -> {
					report { metrics.onBuildStarted(event.buildId, event.route, event.changes) }
					dispatch(SessionEvent.BuildStarted)
				}
				is OrchestratorEvent.BuildSucceeded -> {
					report { metrics.onBuildFinished(event.buildId, event.result) }
					live?.let {
						it.lastDeployedGeneration = maxOf(it.lastDeployedGeneration, event.result.generation)
					}
					dispatch(
						SessionEvent.BuildSucceeded(
							event.result.generation,
							event.result.durationMillis,
							event.result.restarted,
						),
					)
				}
				is OrchestratorEvent.BuildFailed -> {
					report { metrics.onBuildFinished(event.buildId, event.outcome) }
					val outcome = event.outcome
					if (outcome is BuildOutcome.RequiresRebaseline) {
						// The build was fine but the baseline cannot take the deploy (a
						// restart-requiring change on a pre-restart baseline). Route into
						// the existing rebaseline fallback; the orchestrator already put
						// the changed set back into pending, so the rebaseline absorbs it.
						log.info("Quick build routed to rebaseline: {}", outcome.detail)
						report { metrics.onInvalidation(outcome.reason) }
						dispatch(SessionEvent.InvalidationDetected(outcome.reason))
					} else if (outcome is BuildOutcome.InfrastructureFailure && outcome.daemonDied) {
						dispatch(SessionEvent.DaemonDied)
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

	/** Metrics can never affect a build: a throwing sink degrades to a logged warning. */
	private inline fun report(block: () -> Unit) {
		try {
			block()
		} catch (e: Throwable) {
			log.warn("Quick Build metrics sink failed", e)
		}
	}

	private suspend fun rebaseline(startEpoch: Long) {
		val session = live ?: return
		session.orchestrator.onRebaselineStarted()
		dispatch(SessionEvent.RebaselineStarted)

		val startedAtNanos = System.nanoTime()
		val outcome =
			try {
				provisioner.rebaseline()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Throwable) {
				log.error("Rebaseline threw instead of reporting an outcome", e)
				RebaselineOutcome.Failure(e.message ?: e.javaClass.name)
			}
		report {
			metrics.onRebaseline(
				isSuccess = outcome is RebaselineOutcome.Success,
				durationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000,
			)
		}

		if (startEpoch != sessionEpoch) {
			// The session this rebaseline was for is gone; don't poke its orchestrator.
			log.info("Quick-build rebaseline outlived a session restart; discarding")
			return
		}

		when (outcome) {
			is RebaselineOutcome.Success -> {
				// The rebaseline regenerated setup.json and reinstalled the test app:
				// every SetupInfo-derived piece of the session (deploy-policy components,
				// componentInfoAvailable, launcher/entry targets, classpath) must move to
				// the new baseline, or the policy keeps routing on provisioning-time
				// facts - e.g. a service the rebaseline just proxied would hot-swap and
				// silently leave its live instance stale.
				session.setup = outcome.setup
				session.layout = outcome.layout
				session.executor.delegate = buildExecutor(outcome.setup, outcome.layout, session.tracker)
				session.annotationImpact.delegate = annotationImpact(outcome.setup, outcome.layout)
				// The freshly installed baseline boots gen 0 again; the fingerprint gate
				// in its runtime discarded any older persisted payload.
				session.lastDeployedGeneration = -1L
				session.orchestrator.onBaselineReset()
				dispatch(SessionEvent.ProvisioningSucceeded(session.tracker.current))
			}
			is RebaselineOutcome.Failure -> {
				session.orchestrator.onRebaselineFailed()
				dispatch(SessionEvent.ProvisioningFailed(outcome.message))
			}
		}
	}

	/**
	 * B3 hand-back: an external full build finished. When the setup artifacts the daemon
	 * builds against are still on disk, marking the baseline dirty is enough - the next
	 * build recompiles everything from current disk, without reinstalling anything. When
	 * the external build removed them (a clean wiped build/), only a full setup rebuild
	 * helps: route to the existing invalidation machinery as EXTERNAL_FULL_BUILD.
	 */
	private suspend fun reseedBaseline() {
		val session = live ?: return
		if (setupArtifactsIntact(session.setup)) {
			session.orchestrator.onBaselineUntrusted()
		} else {
			log.warn("Setup artifacts missing after an external build; forcing a rebaseline")
			dispatch(SessionEvent.InvalidationDetected(InvalidationReason.EXTERNAL_FULL_BUILD))
		}
	}

	private fun setupArtifactsIntact(setup: SetupInfo): Boolean =
		setup.classpath.all { it.exists() } &&
			setup.proxyClassesDir?.isDirectory != false &&
			setup.transformedManifest?.isFile != false

	private suspend fun respawnDaemon() {
		val session = live ?: return
		when (val started = daemon.start(daemonConfig(session.layout, session.setup))) {
			is DaemonReply.Ok -> {
				dispatch(SessionEvent.DaemonRespawned)
				// A fresh daemon has no trustworthy IC state: re-seed with Unknown so
				// the next build recompiles everything rather than serving stale code.
				session.orchestrator.onFilesChanged(ChangedFiles.Unknown)
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
	 * Tears down the live session AND any in-flight provision/prewarm/rebaseline. The
	 * epoch bump + cancel pair is what makes "Restart session" safe mid-provisioning:
	 * without it, a provision resuming after the restart would set [live], start its
	 * watcher and build/deploy invisibly while the UI shows Idle - and the next tap's
	 * provision would overwrite [live] leaving the orphan watcher running forever.
	 * Cancelling [sessionWork] from within that very coroutine (the
	 * SurfaceProvisioningError path) is safe: nothing suspends after the dispatch.
	 */
	private fun teardown() {
		sessionEpoch++
		sessionWork?.cancel()
		sessionWork = null
		live?.watcher?.stop()
		live = null
		connections.endSession()
		scope.launch { daemon.shutdown() }
	}

	private fun surfaceUserMessage(message: String) {
		onUserMessage(message)
		_userMessages.tryEmit(message)
	}

	private fun BuildOutcome.toSessionFailure(): SessionFailure =
		when (this) {
			is BuildOutcome.CompileError -> SessionFailure.CompileError(diagnostics)
			is BuildOutcome.DeployFailure -> SessionFailure.DeployError(message)
			is BuildOutcome.InfrastructureFailure -> SessionFailure.DeployError(message)
			// Handled as an invalidation before this mapping; keep it total anyway.
			is BuildOutcome.RequiresRebaseline -> SessionFailure.DeployError(detail)
			// Success never reaches BuildFailed; keep the mapping total anyway.
			is BuildOutcome.Success -> SessionFailure.DeployError("unexpected success in failure path")
		}

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildSessionManager::class.java)

		/** setup.json schema that introduced `components` + runtime restart support. */
		private const val COMPONENT_SCHEMA_VERSION = 2
	}
}
