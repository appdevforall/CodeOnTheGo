package org.appdevforall.cotg.quickbuild.domain.session

import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic

/**
 * Lifecycle states of a quick-build session, as one sealed type rather than a set of booleans.
 *
 * The generation carried by the live states is the one the PROXY APP currently runs, which is
 * what the "running gen N" line reports. A compile error keeps the session in [Ready] at the
 * old generation with [Ready.lastFailure] set; the proxy app never moved.
 */
sealed interface QuickBuildSessionState {
	/**
	 * No session. The Quick Build button starts provisioning.
	 *
	 * @property lastStartFailed the last transition into Idle was [SessionEvent.ProvisioningFailed],
	 *   so the bolt keeps the error tone instead of settling back to a green READY right after the
	 *   failure flash. Cleared by the next tap (which starts a fresh provision anyway) or the next
	 *   save ([SessionEvent.FileSaved]) - the save clears the tone only and never retries the
	 *   start; a retry stays a tap.
	 */
	data class Idle(
		val lastStartFailed: Boolean = false,
	) : QuickBuildSessionState

	/**
	 * The eager proxy app build is running in the background at project open - no install, no
	 * daemon, no session.
	 *
	 * @property tapQueued a Quick Build tap landed mid-warm, so provisioning starts when the warm
	 *   build finishes; two concurrent Gradle builds through the tooling server would fail.
	 * @property lastStartFailed carried from [Idle.lastStartFailed] so the silent warm build does
	 *   not clear the failed-start error tone on its way through; a queued tap clears it, and a
	 *   tapless finish hands it back to [Idle].
	 */
	data class Prebuilding(
		val tapQueued: Boolean = false,
		val lastStartFailed: Boolean = false,
	) : QuickBuildSessionState

	/**
	 * Proxy app build, proxy-app install and daemon spawn in progress.
	 *
	 * @property userInitiated a Quick Build tap started this, so the proxy app is brought forward on
	 *   [SessionEvent.ProvisioningSucceeded]; false for a proxy app rebuild, which a plain save can
	 *   trigger and which is answered by the deferred switch the shell holds, not by this flag.
	 * @property installAutoRetries carried through a proxy app rebuild so an unconfirmed reinstall
	 *   parks back in [Invalidated] with the count intact (see [Invalidated.installAutoRetries]).
	 * @property rebaselineReason what invalidated the baseline when this is a rebaseline rather than
	 *   a session's first provision, null for the first - carried in the state rather than inferred
	 *   from the [Invalidated] hop before it, because the status surfaces read a conflating
	 *   [kotlinx.coroutines.flow.StateFlow] and may never observe that hop.
	 */
	data class Provisioning(
		val userInitiated: Boolean = false,
		val installAutoRetries: Int = 0,
		val rebaselineReason: InvalidationReason? = null,
	) : QuickBuildSessionState

	/**
	 * Session live, no build running. [lastFailure] is surfaced until the next build.
	 *
	 * @property generation the generation the proxy app currently runs.
	 * @property lastFailure why the previous build did not move that generation, or null when the
	 *   last build landed; a compile error and a proxy-app crash both park here.
	 */
	data class Ready(
		val generation: Long,
		val lastFailure: SessionFailure? = null,
	) : QuickBuildSessionState

	/**
	 * A build is running; the proxy app still runs [deployedGeneration].
	 *
	 * @property deployedGeneration the generation the proxy app still runs while this build is in
	 *   flight; it only moves on a successful deploy.
	 * @property warmingCompiler the in-flight build is the background warm compile
	 *   ([org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute.WarmCompile]), which deploys
	 *   nothing, so the status must not present it as blocking and a tap must trigger a real build.
	 * @property pendingCrash a proxy-app crash seen mid-warm-compile, which
	 *   [SessionEvent.WarmCompileFinished] lands as [Ready.lastFailure]; the warm compile
	 *   suppresses its own outcome, not crashes of the running generation.
	 */
	data class Building(
		val deployedGeneration: Long,
		val warmingCompiler: Boolean = false,
		val pendingCrash: SessionFailure.ProxyAppCrash? = null,
	) : QuickBuildSessionState

	/**
	 * A build just landed; the proxy app runs [generation].
	 *
	 * @property generation the generation the deploy just moved the proxy app to.
	 * @property buildDurationMillis the whole save-to-live loop this deploy closed, in
	 *   milliseconds - not the build alone; the status surface shows it as the "reloaded in"
	 *   figure, so it has to be the span the user waited (see
	 *   [org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome.Success.durationMillis]).
	 * @property restarted it landed via the process-restart path (service/provider/Application code
	 *   changed), so the proxy app relaunched at its launcher and lost in-process state.
	 */
	data class Deployed(
		val generation: Long,
		val buildDurationMillis: Long,
		val restarted: Boolean = false,
	) : QuickBuildSessionState

	/**
	 * The baseline is stale (manifest, gradle, or an external build) and needs a full Gradle
	 * build.
	 *
	 * @property reason what the live reload path could not absorb, which the status surface names
	 *   to the user.
	 * @property deployedGeneration the generation the proxy app keeps running until a full rebuild
	 *   replaces it.
	 * @property awaitingRetry no proxy app rebuild is in flight, so the next Quick Build tap or
	 *   [SessionEvent.HostForegrounded] retries it instead of the session dying to [Idle].
	 * @property installAutoRetries how many [SessionEvent.HostForegrounded] auto-retries this
	 *   unconfirmed reinstall has spent; at [SessionReducer.MAX_INSTALL_AUTO_RETRIES] the foreground
	 *   trigger stops, so a user who keeps declining does not pay a Gradle build on every resume,
	 *   while an explicit tap still retries and resets the budget.
	 */
	data class Invalidated(
		val reason: InvalidationReason,
		val deployedGeneration: Long,
		val awaitingRetry: Boolean = false,
		val installAutoRetries: Int = 0,
	) : QuickBuildSessionState

	/**
	 * The compile daemon died; respawn and warm compile in progress.
	 *
	 * @property deployedGeneration the generation the proxy app keeps running across the daemon
	 *   outage - the process is untouched, only the compiler is gone.
	 * @property restartFailed the respawn did not stick - it failed outright
	 *   ([SessionEvent.DaemonRestartFailed]) or the fresh daemon died again - and nothing is
	 *   scheduled to try once more, deliberately, because auto-retrying a hard-broken daemon just
	 *   spins; a flag rather than a state because all that changes is that the status must stop
	 *   claiming a restart is under way.
	 */
	data class Degraded(
		val deployedGeneration: Long,
		val restartFailed: Boolean = false,
	) : QuickBuildSessionState
}

/** Why the last quick build did not move the proxy app to a new generation. */
sealed interface SessionFailure {
	/**
	 * The changed sources did not compile, so nothing was deployed.
	 *
	 * @property diagnostics the compiler messages for this build, in the order the daemon reported
	 *   them; read [BuildDiagnostic.severity] rather than assuming every entry is an error.
	 */
	data class CompileError(
		val diagnostics: List<BuildDiagnostic>,
	) : SessionFailure

	/**
	 * The sources compiled but the payload never reached the proxy app.
	 *
	 * @property message why the deploy or reload failed, already user-facing - the status surface
	 *   shows it verbatim.
	 */
	data class DeployError(
		val message: String,
	) : SessionFailure

	/**
	 * The payload crashed in the proxy app (render or lifecycle), not a compile error.
	 *
	 * @property summary short description of the crash, from the runtime's report rather than a
	 *   full stack trace.
	 */
	data class ProxyAppCrash(
		val summary: String,
	) : SessionFailure
}

/** Inputs to [SessionReducer], from the UI, the orchestrator, and process observers. */
sealed interface SessionEvent {
	/**
	 * The user tapped the Quick Build button.
	 *
	 * @property wroteSomething whether the tap's save-all wrote at least one file - the single
	 *   bit the tap carries across the save/watch boundary. The watcher stays the only
	 *   changeset source, so no filenames travel with the tap: a true bit routes the tap
	 *   through the batch those writes will produce, a false bit with nothing pending answers
	 *   the tap by switching without building. States that do not trigger a live reload
	 *   ignore it.
	 */
	data class QuickBuildTapped(
		val wroteSomething: Boolean = false,
	) : SessionEvent

	/**
	 * The user tapped the button while it showed the stop affordance.
	 *
	 * Only states that own a build the user asked for act on it, so the shell can dispatch it
	 * without checking.
	 */
	data object CancelRequested : SessionEvent

	/**
	 * The editor wrote a file to disk - the host-side save path, not the session's watcher.
	 *
	 * Only [QuickBuildSessionState.Idle] with `lastStartFailed = true` acts on it, clearing the
	 * stale error tone without retrying the start (a retry stays a tap). Every other state
	 * ignores it: a live session learns about saves from its own watcher, and this event must
	 * never start a build.
	 */
	data object FileSaved : SessionEvent

	/** Project opened with the feature enabled: warm the proxy app build, defer the install. */
	data object PrebuildRequested : SessionEvent

	/** The eager proxy app build finished; a warm failure is not surfaced. */
	data object PrebuildFinished : SessionEvent

	/**
	 * The session is live at [generation].
	 *
	 * @property generation the generation the freshly installed proxy app starts at; every later
	 *   deploy must be strictly newer.
	 */
	data class ProvisioningSucceeded(
		val generation: Long,
	) : SessionEvent

	/**
	 * Provisioning failed; the session drops to [QuickBuildSessionState.Idle] with
	 * `lastStartFailed = true` and surfaces [message].
	 *
	 * @property message why it failed, already user-facing - it is shown verbatim.
	 */
	data class ProvisioningFailed(
		val message: QuickBuildMessage,
	) : SessionEvent

	/** A real quick build started; its deploy will move the generation. */
	data object BuildStarted : SessionEvent

	/**
	 * The background warm compile started ([org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute.WarmCompile]).
	 *
	 * A distinct event rather than a flag on [BuildStarted] so the session can mark itself
	 * `warmingCompiler`, which keeps the status surface reading "up to date" and keeps taps and
	 * crashes during the window handled honestly (see [QuickBuildSessionState.Building]).
	 */
	data object WarmCompileStarted : SessionEvent

	/**
	 * A build deployed; the proxy app now runs [generation].
	 *
	 * @property generation the generation now live in the proxy app, always newer than the one it
	 *   replaced.
	 * @property durationMillis the whole save-to-live loop this deploy closed, in milliseconds -
	 *   not the build alone.
	 * @property restarted true when the deploy restarted the proxy-app process (component code
	 *   changed).
	 * @property userInitiated true when this build answers a Quick Build tap, so the deploy landing
	 *   is the moment to bring the proxy app forward; false for a build a file write
	 *   triggered - a save is not the user asking to leave the editor - and for a cancelled tap.
	 */
	data class BuildSucceeded(
		val generation: Long,
		val durationMillis: Long,
		val restarted: Boolean = false,
		val userInitiated: Boolean = false,
	) : SessionEvent

	/**
	 * A build did not deploy; the proxy app stays on its current generation.
	 *
	 * @property failure why it did not land; surfaced as [QuickBuildSessionState.Ready.lastFailure]
	 *   until the next build supersedes it.
	 */
	data class BuildFailed(
		val failure: SessionFailure,
	) : SessionEvent

	/**
	 * The background warm compile finished, whether green or failed.
	 *
	 * Nothing deployed and the generation did not move, so no warm-compile outcome is surfaced: it
	 * recompiled sources that already built green. A proxy-app crash seen during the window is not
	 * a warm-compile outcome and lands as [QuickBuildSessionState.Ready.lastFailure]; daemon death
	 * stays on the [DaemonDied] path.
	 */
	data object WarmCompileFinished : SessionEvent

	/**
	 * A change the live reload path cannot absorb; the baseline is now stale.
	 *
	 * @property reason what could not be absorbed; reported once per invalidation, so no state may
	 *   silently drop this event.
	 */
	data class InvalidationDetected(
		val reason: InvalidationReason,
	) : SessionEvent

	/** The full Gradle proxy app rebuild has been kicked off. */
	data object ProxyAppRebuildStarted : SessionEvent

	/**
	 * The proxy app rebuild built fine but its reinstall was never confirmed - no dialog could be
	 * shown, the user cancelled, or it went untapped until the installer timed out.
	 *
	 * The session is not dead: it parks in [QuickBuildSessionState.Invalidated] with
	 * `awaitingRetry = true`, where the next tap or [HostForegrounded] rebuilds and re-prompts.
	 *
	 * @property deployedGeneration the generation the proxy app still runs, carried through the
	 *   park so the parked state keeps reporting it.
	 */
	data class ProxyAppRebuildInstallNotConfirmed(
		val deployedGeneration: Long,
	) : SessionEvent

	/**
	 * A parked rebuild retry never started because the device's single Gradle slot was taken.
	 *
	 * The session parks straight back awaiting a retry, and the attempt is NOT charged against
	 * [QuickBuildSessionState.Invalidated.installAutoRetries]: that budget bounds Gradle builds and
	 * install prompts, and a deferred attempt produced neither. The collision is routine - the
	 * gradle-file change that parks a session is also what makes CoGo declare NEED_SYNC.
	 *
	 * @property deployedGeneration the generation the proxy app still runs, carried through the
	 *   park so the parked state keeps reporting it.
	 */
	data class ProxyAppRebuildDeferred(
		val deployedGeneration: Long,
	) : SessionEvent

	/**
	 * The full Gradle build behind a proxy app rebuild failed: the user's build files do not build.
	 *
	 * Distinct from [ProvisioningFailed], which drops to [QuickBuildSessionState.Idle] because there
	 * is no session to keep. Here there IS one, running perfectly well, and the cause is a file the
	 * user can fix in seconds - so the session parks in [QuickBuildSessionState.Invalidated]
	 * awaiting a retry instead of dying, as a broken source file already does.
	 *
	 * @property reason the invalidation that asked for the rebuild, carried so the park keeps
	 *   naming it.
	 * @property deployedGeneration the generation the proxy app still runs; the failed build
	 *   deployed nothing.
	 */
	data class ProxyAppRebuildFailed(
		val reason: InvalidationReason,
		val deployedGeneration: Long,
	) : SessionEvent

	/**
	 * CoGo's editor came (back) to the foreground - the first chance to re-prompt a missed install.
	 *
	 * Only meaningful to a session parked in [QuickBuildSessionState.Invalidated] with
	 * `awaitingRetry = true`: when the reinstall ran while CoGo was backgrounded, Android defers the
	 * PENDING_USER_ACTION broadcast until the app returns, and the EventBus dialog subscriber
	 * (registered onStart) can re-register after that delivery lands, so no dialog is launched.
	 */
	data object HostForegrounded : SessionEvent

	/**
	 * A full Gradle build ran outside the session (a Standard Run) and completed.
	 *
	 * It may have regenerated `build/` inputs the watcher cannot see, so a live session must
	 * refresh its baseline from current disk before its next build.
	 */
	data object ExternalBuildCompleted : SessionEvent

	/** The compile daemon died. */
	data object DaemonDied : SessionEvent

	/** The compile daemon is back and warm. */
	data object DaemonRespawned : SessionEvent

	/**
	 * A respawn attempt failed, so the compiler is down with nothing scheduled to bring it back.
	 *
	 * The manager already shows [QuickBuildMessage.DaemonRestartFailed] when this happens; the
	 * event exists so the STATUS can stop saying "compile daemon restarting" as well, which after
	 * a failed respawn asserts an activity that is not happening. Only
	 * [QuickBuildSessionState.Degraded] acts on it.
	 */
	data object DaemonRestartFailed : SessionEvent

	/**
	 * The proxy app process crashed.
	 *
	 * @property summary short description of the crash, carried into
	 *   [SessionFailure.ProxyAppCrash] rather than shown as a stack trace.
	 */
	data class ProxyAppCrashed(
		val summary: String,
	) : SessionEvent

	/**
	 * Tear the session down and leave it Idle. Valid from any state.
	 *
	 * The internal half of the escape hatch, for callers that want the session gone and nothing
	 * started in its place - closing a project, or a Standard Run about to install over the proxy
	 * app. A user who asked to restart wants [SessionRestartAndReprovisionRequested] instead.
	 */
	data object SessionRestartRequested : SessionEvent

	/**
	 * Tear the session down and immediately provision a fresh one. Valid from any state.
	 *
	 * What the "Restart session" menu item and the proxy-app-won't-stay-up dialog mean: every notice
	 * naming Restart session as the remedy ([QuickBuildNotice.RELOAD_CRASHED],
	 * [QuickBuildNotice.RELINK_STUCK], [QuickBuildNotice.PROXY_APP_WONT_STAY_UP]) needs a fresh
	 * proxy app build to deliver it, and stopping at Idle instead leaves an unchanged toolbar icon
	 * and a second tap for the user to discover (T15).
	 */
	data object SessionRestartAndReprovisionRequested : SessionEvent
}

/** Side effects the session manager must run after a transition. */
sealed interface SessionEffect {
	/** Build, install and start a session from scratch. */
	data object StartProvisioning : SessionEffect

	/** Run the proxy app build only - no install, no daemon. */
	data object StartProxyAppPrebuild : SessionEffect

	/**
	 * Ask the orchestrator to build now.
	 *
	 * @property userInitiated carries who asked all the way to the deploy, deliberately separate
	 *   from [org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest.forced], which the
	 *   reconnect catch-up also sets and which is re-armed after a failure - reusing it would pull
	 *   the user out of the editor on a stale reconnect or on a save retrying a failed tap.
	 * @property expectChanges the tap's save-all wrote at least one file, so the orchestrator
	 *   should wait for the watcher batch those writes produce instead of building an empty
	 *   set (see [SessionEvent.QuickBuildTapped.wroteSomething]); meaningless when
	 *   [userInitiated] is false.
	 */
	data class TriggerLiveReload(
		val userInitiated: Boolean,
		val expectChanges: Boolean = false,
	) : SessionEffect

	/**
	 * Bring the proxy app to the foreground - the answer to a tap.
	 *
	 * Never emitted for a build a file write triggered, nor after a cancelled tap.
	 */
	data object SwitchToProxyApp : SessionEffect

	/**
	 * Record that a tap landed on a real build already in flight, so its deploy brings the proxy
	 * app forward.
	 *
	 * Deliberately not [TriggerLiveReload]: the in-flight build is about to do the same work, so
	 * forcing a second rebuild behind it would double the cost for nothing.
	 */
	data object MarkBuildUserInitiated : SessionEffect

	/**
	 * Stop the in-flight incremental quick build.
	 *
	 * The reducer has already returned to [QuickBuildSessionState.Ready] at the unchanged
	 * generation, so nothing new deploys.
	 */
	data object CancelLiveReload : SessionEffect

	/**
	 * Stop the out-of-process Gradle proxy app build (prebuild, provision or rebuild).
	 *
	 * Cancelling the awaiting coroutine alone leaves Gradle running to completion, so this has to
	 * reach the tooling server's cancellation token.
	 */
	data object CancelProxyAppBuild : SessionEffect

	/**
	 * Start the background warm compile ([org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute.WarmCompile]) as soon as a session goes live.
	 *
	 * Pays the daemon's first-compile warm-up (kotlinc JIT, classpath snapshot, IC-cache build) in
	 * the provisioning tail instead of on the user's first save.
	 */
	data object StartWarmCompile : SessionEffect

	/** Route to the real Gradle build; on completion the session rebuilds its proxy app. */
	data object RunProxyAppRebuild : SessionEffect

	/**
	 * Recover the live session's baseline after an external full build.
	 *
	 * The shell chooses: mark the incremental baseline dirty so the next build recompiles from
	 * current disk, or - if the external build clobbered the proxy app artifacts - escalate to a
	 * full rebuild with [InvalidationReason.EXTERNAL_FULL_BUILD].
	 */
	data object RefreshBaseline : SessionEffect

	/** Bring the compile daemon back up after it died. */
	data object RespawnDaemon : SessionEffect

	/**
	 * Show the user why provisioning failed.
	 *
	 * @property message the wording to show, already user-facing - the shell does not rephrase it.
	 */
	data class SurfaceProvisioningError(
		val message: QuickBuildMessage,
	) : SessionEffect

	/**
	 * Show the user a failure and leave the session running.
	 *
	 * The counterpart to [SurfaceProvisioningError], which tears the session down: this one is for
	 * a state that is recoverable, where the message explains what just happened rather than what
	 * killed the session.
	 *
	 * @property message the wording to show, already user-facing - the shell does not rephrase it.
	 */
	data class SurfaceMessage(
		val message: QuickBuildMessage,
	) : SessionEffect

	/** Tear down the live session and daemon; the reducer has already moved to Idle. */
	data object TeardownSession : SessionEffect

	/**
	 * Tear the live session down and then provision a fresh one, in that order.
	 *
	 * One effect rather than [TeardownSession] followed by [StartProvisioning] because the teardown's
	 * daemon shutdown is asynchronous: as two effects the new session could start a daemon into a
	 * shutdown still in flight, which would then kill the daemon it just spawned. Only the
	 * user-facing restart pays for that wait; an ordinary tap after a teardown deliberately does not
	 * (the shell's scratch-tree handling is what makes that case safe).
	 */
	data object TeardownAndProvision : SessionEffect
}

/**
 * The reducer's output: the state to adopt and the effects the shell must then run.
 *
 * @property state the state to adopt; equal to the input state when the event was a no-op.
 * @property effects the effects to run after adopting [state], in order; empty for a no-op.
 */
data class SessionTransition(
	val state: QuickBuildSessionState,
	val effects: List<SessionEffect> = emptyList(),
)
