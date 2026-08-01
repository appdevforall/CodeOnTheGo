package org.appdevforall.cotg.quickbuild.domain

/**
 * Quick-build session lifecycle states — one sealed type, not booleans.
 *
 * [generation] on the live states is the generation the PROXY APP currently runs — the
 * "running gen N" honesty line derives from it. A compile error keeps the session in
 * [Ready] at the old generation with [Ready.lastFailure] set; the proxy app never moved.
 */
sealed interface QuickBuildSessionState {
	/** No session. The Quick Build button starts provisioning. */
	data object Idle : QuickBuildSessionState

	/**
	 * The eager proxy app build runs in the background (project open, plan B2) - no install,
	 * no daemon, no session. [tapQueued] records a Quick Build tap that landed mid-warm:
	 * provisioning starts the moment the warm build finishes instead of racing it (two
	 * concurrent Gradle builds through the tooling server would fail).
	 */
	data class Prebuilding(
		val tapQueued: Boolean = false,
	) : QuickBuildSessionState

	/**
	 * Proxy app build + proxy-app install + daemon spawn in progress.
	 *
	 * [userInitiated] true = a Quick Build TAP is what started this, so the session going
	 * live is the answer to the user asking - the proxy app is brought to the foreground on
	 * [SessionEvent.ProvisioningSucceeded] (Bryan's behaviour 2). Nothing launches the proxy
	 * app after its install otherwise, so without this a first tap would install the app,
	 * warm the daemon and leave the user staring at the editor. False for a proxy app rebuild
	 * (also routed through this state): a proxy app rebuild is a full Gradle build that a plain
	 * save can trigger, and yanking the user out of the editor a minute later is not an
	 * answer to anything they asked for.
	 *
	 * [installAutoRetries] rides along on a proxy app rebuild so an unconfirmed reinstall parks
	 * back in [Invalidated] with the count intact - it is how the [SessionEvent.HostForegrounded]
	 * auto-retry stays bounded across park/retry/park cycles (see [Invalidated.installAutoRetries]).
	 */
	data class Provisioning(
		val userInitiated: Boolean = false,
		val installAutoRetries: Int = 0,
	) : QuickBuildSessionState

	/** Session live, no build running. [lastFailure] is surfaced until the next build. */
	data class Ready(
		val generation: Long,
		val lastFailure: SessionFailure? = null,
	) : QuickBuildSessionState

	/**
	 * A quick build is running; the proxy app still runs [deployedGeneration].
	 *
	 * [warmingCompiler] true = the in-flight build is the background warm compile ([BuildRoute.WarmCompile]):
	 * it compiles the sources the proxy app ALREADY runs and deploys nothing, so the status
	 * surface must not present it as a blocking "Building" (the app is genuinely up to
	 * date), and a Quick Build tap must trigger a real build instead of being dropped (a
	 * real build satisfies a mid-build tap by deploying; a warm compile never deploys).
	 * [pendingCrash] carries a proxy-app crash observed mid-warm-compile so [SessionEvent.WarmCompileFinished]
	 * lands it as [Ready.lastFailure] instead of swallowing it — the warm compile's "surface
	 * nothing" contract covers warm-compile OUTCOMES, not crashes of the running generation.
	 */
	data class Building(
		val deployedGeneration: Long,
		val warmingCompiler: Boolean = false,
		val pendingCrash: SessionFailure.ProxyAppCrash? = null,
	) : QuickBuildSessionState

	/**
	 * A build just landed; the proxy app runs [generation]. [restarted] true = it landed
	 * via the process-restart path (service/provider/Application code changed), so the
	 * proxy app relaunched at its launcher and lost in-process state - the status surface
	 * says so instead of a plain "reloaded".
	 */
	data class Deployed(
		val generation: Long,
		val buildDurationMillis: Long,
		val restarted: Boolean = false,
	) : QuickBuildSessionState

	/**
	 * The baseline is stale (manifest/gradle/external build); needs a full Gradle build.
	 * [awaitingRetry] true = no proxy app rebuild is in flight and the next Quick Build tap or
	 * [SessionEvent.HostForegrounded] retries it instead of the session having died to
	 * [Idle]. Two things park here: a proxy app rebuild whose reinstall was never confirmed
	 * ([SessionEvent.ProxyAppRebuildInstallNotConfirmed]) and a retry that never got the
	 * device's single Gradle slot ([SessionEvent.ProxyAppRebuildDeferred]).
	 *
	 * [installAutoRetries] counts the [SessionEvent.HostForegrounded] auto-retries already
	 * spent on this unconfirmed reinstall. Once it reaches
	 * [SessionReducer.MAX_INSTALL_AUTO_RETRIES] the foreground trigger stops re-running
	 * the proxy app rebuild - a user who keeps declining must not pay a fresh Gradle build on
	 * every resume, forever. A Quick Build TAP still retries (and resets the budget):
	 * an explicit ask is fresh consent.
	 */
	data class Invalidated(
		val reason: InvalidationReason,
		val deployedGeneration: Long,
		val awaitingRetry: Boolean = false,
		val installAutoRetries: Int = 0,
	) : QuickBuildSessionState

	/** The compile daemon died; respawn + warm compile in progress. */
	data class Degraded(
		val deployedGeneration: Long,
	) : QuickBuildSessionState
}

/** Why the last quick build did not move the proxy app to a new generation. */
sealed interface SessionFailure {
	data class CompileError(
		val diagnostics: List<BuildDiagnostic>,
	) : SessionFailure

	data class DeployError(
		val message: String,
	) : SessionFailure

	/** The payload crashed in the proxy app (render/lifecycle) — distinct from a compile error. */
	data class ProxyAppCrash(
		val summary: String,
	) : SessionFailure
}

/** Inputs to [SessionReducer] — from the UI, the orchestrator, and process observers. */
sealed interface SessionEvent {
	data object QuickBuildTapped : SessionEvent

	/**
	 * The user tapped the button while it was showing the stop affordance (Bryan's
	 * behaviour 5). Only the states that actually own a build the user asked for act on
	 * it; every other state ignores it, so the shell can dispatch it without checking.
	 */
	data object CancelRequested : SessionEvent

	/** Project opened with the feature enabled: warm the proxy app build, defer the install. */
	data object PrebuildRequested : SessionEvent

	/** The eager proxy app build finished (success or not - a warm failure is not surfaced). */
	data object PrebuildFinished : SessionEvent

	data class ProvisioningSucceeded(
		val generation: Long,
	) : SessionEvent

	data class ProvisioningFailed(
		val message: String,
	) : SessionEvent

	data object BuildStarted : SessionEvent

	/**
	 * The background warm compile started ([BuildRoute.WarmCompile]). A distinct event, not a flag on
	 * [BuildStarted]: the session enters [QuickBuildSessionState.Building] with
	 * `warmingCompiler = true` so the status surface keeps reading "up to date" (nothing will
	 * deploy) and taps/crashes during the warm compile are handled honestly (see [Building]).
	 */
	data object WarmCompileStarted : SessionEvent

	data class BuildSucceeded(
		val generation: Long,
		val durationMillis: Long,
		/** True when the deploy restarted the proxy-app process (component code changed). */
		val restarted: Boolean = false,
		/**
		 * True when a Quick Build TAP is what this build answers, so the deploy landing is
		 * the moment to bring the proxy app forward (Bryan's behaviour 2). False for a
		 * build a file write triggered - a save is not the user asking to leave the editor
		 * (behaviour 3) - and false for a tap the user then cancelled.
		 */
		val userInitiated: Boolean = false,
	) : SessionEvent

	data class BuildFailed(
		val failure: SessionFailure,
	) : SessionEvent

	/**
	 * The background warm-compile build finished (success or a silently-logged failure).
	 * Nothing deployed, the generation did not move: Building returns to Ready at the
	 * deployed generation with no warm-compile OUTCOME surfaced - a warm-compile problem is invisible by
	 * design (the proxy app build just compiled the same sources green; the next real save
	 * surfaces anything real). A proxy-app crash observed DURING the warm compile is not a warm-compile
	 * outcome: it lands as [QuickBuildSessionState.Ready.lastFailure] via
	 * [QuickBuildSessionState.Building.pendingCrash]. Daemon death during a warm compile does NOT
	 * arrive here - it stays on the [DaemonDied] recovery path.
	 */
	data object WarmCompileFinished : SessionEvent

	data class InvalidationDetected(
		val reason: InvalidationReason,
	) : SessionEvent

	/** The full Gradle proxy app rebuild build has been kicked off. */
	data object ProxyAppRebuildStarted : SessionEvent

	/**
	 * The proxy app rebuild's Gradle build succeeded but the proxy-app reinstall was never
	 * confirmed - no dialog could be shown (CoGo backgrounded), the user cancelled it,
	 * or it was left untapped until the installer timed out. The session is NOT dead:
	 * it parks in [QuickBuildSessionState.Invalidated] with `awaitingRetry = true`,
	 * where the next Quick Build tap or [HostForegrounded] re-runs the proxy app rebuild and
	 * re-prompts. [deployedGeneration] is the generation the proxy app still runs.
	 */
	data class ProxyAppRebuildInstallNotConfirmed(
		val deployedGeneration: Long,
	) : SessionEvent

	/**
	 * A parked proxy app rebuild RETRY never started: the device's single Gradle slot was already
	 * taken (CoGo's own project sync, a Standard Run), so nothing was built and no install
	 * was prompted. The session parks straight back in
	 * [QuickBuildSessionState.Invalidated] awaiting a retry, and the attempt is NOT counted
	 * against [QuickBuildSessionState.Invalidated.installAutoRetries]: that budget bounds
	 * Gradle builds and install prompts, and a deferred attempt produced neither.
	 *
	 * Reachable on exactly the path the park is made of: the invalidation that parks a
	 * session is by definition a gradle-file change, which is also what makes CoGo's
	 * ProjectSyncHelper declare NEED_SYNC - so a foreground return can start a sync build
	 * milliseconds before the [HostForegrounded] retry asks for the same slot. Counting
	 * that collision spent the whole budget and dropped the session to [Idle] with a
	 * build-failure banner instead of the install re-prompt.
	 */
	data class ProxyAppRebuildDeferred(
		val deployedGeneration: Long,
	) : SessionEvent

	/**
	 * CoGo's editor came (back) to the foreground. Only meaningful to a session parked
	 * in [QuickBuildSessionState.Invalidated] with `awaitingRetry = true`: when the
	 * reinstall ran while CoGo was BACKGROUNDED (e.g. the user was in the proxy app),
	 * Android DEFERS the PENDING_USER_ACTION broadcast until the app is foregrounded -
	 * and the dialog-owning subscriber (InstallationResultHandler) is EventBus
	 * lifecycle-bound (registered onStart, unregistered onStop), so the deferred
	 * delivery can land before it re-registers and no confirm dialog is ever launched.
	 * The user saw nothing to tap. Re-running the proxy app rebuild now (with CoGo foreground)
	 * makes the dialog actually appear. Bounded by
	 * [QuickBuildSessionState.Invalidated.installAutoRetries]; every other state
	 * ignores this event.
	 */
	data object HostForegrounded : SessionEvent

	/**
	 * A full Gradle build ran OUTSIDE the session (a Standard Run) and completed. The
	 * baseline may have moved beneath the daemon (regenerated build/ inputs the watcher
	 * cannot see), so a live session must refresh its baseline from current disk before its
	 * next build.
	 */
	data object ExternalBuildCompleted : SessionEvent

	data object DaemonDied : SessionEvent

	data object DaemonRespawned : SessionEvent

	data class ProxyAppCrashed(
		val summary: String,
	) : SessionEvent

	/** User-requested escape hatch. Valid from any state. */
	data object SessionRestartRequested : SessionEvent
}

/** Side effects the session manager must run after a transition. */
sealed interface SessionEffect {
	data object StartProvisioning : SessionEffect

	/** Run the proxy app build only - no install, no daemon. */
	data object StartProxyAppPrebuild : SessionEffect

	/**
	 * Ask the orchestrator to build now (explicit tap while a session is live).
	 *
	 * [userInitiated] carries WHO asked all the way to the deploy, which is what decides
	 * whether the proxy app is brought forward (Bryan's behaviours 2/3/4). It is a separate
	 * fact from [BuildRequest.forced]: `forced` is also set by the reconnect catch-up and
	 * is re-armed after a failure, so reusing it would yank the user out of the editor on a
	 * stale reconnect or on a save that retried a failed tap.
	 */
	data class TriggerLiveReload(
		val userInitiated: Boolean,
	) : SessionEffect

	/**
	 * Bring the proxy app to the foreground: the answer to a TAP (behaviours 2 and 4).
	 * Never emitted for a build a file write triggered - a save is not the user asking to
	 * leave the editor (behaviour 3) - nor after a cancelled tap (behaviour 5).
	 */
	data object SwitchToProxyApp : SessionEffect

	/**
	 * A tap landed while a real build was already in flight. That build deploys anyway, so it
	 * satisfies the tap's BUILD - this only records that the tap happened, so the deploy
	 * brings the proxy app forward. Distinct from [TriggerLiveReload] on purpose: forcing a
	 * second full rebuild behind a build that was about to do the same work would double the
	 * cost for nothing.
	 */
	data object MarkBuildUserInitiated : SessionEffect

	/**
	 * Stop the in-flight incremental quick build (behaviour 5). The reducer has already
	 * moved back to [QuickBuildSessionState.Ready] at the unchanged generation, so nothing
	 * new deploys and the button returns to the bolt.
	 */
	data object CancelLiveReload : SessionEffect

	/**
	 * Stop the out-of-process Gradle PROXY APP build (prebuild / provision / proxy app rebuild)
	 * (behaviour 5). Cancelling the awaiting coroutine alone leaves Gradle running to
	 * completion, so this has to reach the tooling server's cancellation token.
	 */
	data object CancelProxyAppBuild : SessionEffect

	/**
	 * Ask the orchestrator for the background warm compile ([BuildRoute.WarmCompile]) the moment a
	 * session goes live: pay the daemon's first-compile warm-up (kotlinc JIT + classpath
	 * snapshot + IC-cache build - measured 12-14s on an 8GB device, 37-50s on 3.6GB even
	 * for small Kotlin apps) in the provisioning tail instead of on the user's first save.
	 */
	data object StartWarmCompile : SessionEffect

	/** Route to the real Gradle build; on completion the session rebuilds its proxy app. */
	data object RunProxyAppRebuild : SessionEffect

	/**
	 * Refresh the live session's baseline after an external full build: either mark the whole
	 * incremental baseline dirty (next build recompiles from current disk) or, when the
	 * external build clobbered the proxy app build artifacts, escalate to a full proxy app rebuild with
	 * [InvalidationReason.EXTERNAL_FULL_BUILD]. The shell decides which.
	 */
	data object RefreshBaseline : SessionEffect

	data object RespawnDaemon : SessionEffect

	data class SurfaceProvisioningError(
		val message: String,
	) : SessionEffect

	/** Tear down the live session and daemon; the reducer has already moved to Idle. */
	data object TeardownSession : SessionEffect
}

data class SessionTransition(
	val state: QuickBuildSessionState,
	val effects: List<SessionEffect> = emptyList(),
)
