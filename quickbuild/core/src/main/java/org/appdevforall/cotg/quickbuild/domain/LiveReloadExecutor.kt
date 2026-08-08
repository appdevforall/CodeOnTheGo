package org.appdevforall.cotg.quickbuild.domain

/**
 * Runs one quick build end to end: compile (if the route needs it), dex, relink, deploy.
 * Implemented by the warm-daemon pipeline; tests use scripted fakes.
 *
 * Contract:
 * - Called with at most one request in flight (the [LiveReloadOrchestrator] guarantees it).
 * - Must NOT throw for build problems - report them as a [BuildOutcome]; an escaped
 *   exception is treated by the orchestrator as [BuildOutcome.InfrastructureFailure].
 * - Never receives a [BuildRoute.FullGradleBuild] route (those bypass the live reload path).
 */
interface LiveReloadExecutor {
	/**
	 * Runs one build to completion and reports how it ended.
	 *
	 * @param request what to build, already routed; the executor does not re-classify it.
	 * @return how the build ended. Only [BuildOutcome.Success] means the proxy app moved to a
	 *   new generation; every other outcome leaves it on the old one.
	 */
	suspend fun execute(request: BuildRequest): BuildOutcome

	/**
	 * Promotes the build already running to a user-initiated one, so its deploy may take the
	 * foreground.
	 *
	 * A tap that lands while a save's build is in flight is answered by that build rather than
	 * queueing a second one, so the intent arrives after [execute] was called with
	 * [BuildRequest.userInitiated] false. Without this the promoted build would still refuse to
	 * launch the app, and a tap against a closed app would do nothing at all.
	 */
	fun markCurrentBuildUserInitiated() = Unit
}

/**
 * One build the executor is asked to run.
 *
 * @property buildId orchestrator-unique id; tags diagnostics so a superseded build's output is
 *   discarded rather than rendered.
 * @property changes the coalesced changed-set this build must absorb. [ChangedFiles.Unknown]
 *   means recompile everything.
 * @property route the classifier's verdict, which fixes which steps run; never a
 *   [BuildRoute.FullGradleBuild].
 * @property forced true for an explicit Quick Build tap - the executor must deploy even when
 *   [changes] is empty, by rebuilding the current sources at a FRESH generation, since the
 *   runtime only accepts strictly-newer generations.
 * @property triggeredAtMillis monotonic stamp of the earliest not-yet-built change this build
 *   coalesced - t0 of the [E2eTimeline], on the same clock the executor stamps t1-t3 with. 0
 *   when the orchestrator has no clock (tests that don't measure timing), which only makes t0
 *   meaningless.
 * @property userInitiated true only when a Quick Build tap asked for this build. It is what
 *   licenses the deploy to bring the proxy app to the foreground: a save must never take the
 *   screen from someone who is still typing, so an unasked-for build deploys to a running app
 *   or not at all.
 */
data class BuildRequest(
	val buildId: Long,
	val changes: ChangedFiles,
	val route: BuildRoute,
	val forced: Boolean = false,
	val triggeredAtMillis: Long = 0L,
	val userInitiated: Boolean = false,
)

/** How one build ended. */
sealed interface BuildOutcome {
	/**
	 * Compiled, deployed and reloaded: the proxy app now runs [generation].
	 *
	 * @property generation the generation the proxy app confirmed live, not merely the one sent.
	 * @property durationMillis the whole build as the executor measured it; a wall-clock span,
	 *   so it includes any wait on the daemon.
	 * @property restarted true when the deploy took the process-restart path (a service,
	 *   provider or Application class changed) instead of a hot swap.
	 */
	data class Success(
		val generation: Long,
		val durationMillis: Long,
		val restarted: Boolean = false,
	) : BuildOutcome

	/**
	 * The build succeeded but must not be deployed: the installed baseline would hot-swap a
	 * restart-requiring payload and leave a live service stale.
	 *
	 * The session manager routes [reason] into the proxy-app-rebuild fallback, which
	 * regenerates the baseline; the changed set stays pending and is absorbed there.
	 *
	 * @property reason what the session manager reports and acts on.
	 * @property detail human-readable cause behind [reason], for the status surface.
	 */
	data class RequiresProxyAppRebuild(
		val reason: InvalidationReason,
		val detail: String,
	) : BuildOutcome

	/**
	 * The changed-set does not compile. The proxy app keeps running the old generation.
	 *
	 * @property diagnostics every compiler message, warnings included; equality across two
	 *   builds is what the orchestrator's duplicate-follow-up guard turns on.
	 */
	data class CompileError(
		val diagnostics: List<BuildDiagnostic>,
	) : BuildOutcome

	/**
	 * Compile succeeded but the payload never reached the proxy app (deploy/reload failed).
	 *
	 * @property message what failed, for the status surface; the built outputs stay on disk, so
	 *   the retry does not recompile them from scratch.
	 * @property proxyAppNotConnected true when the payload had nowhere to land because the proxy
	 *   app was not connected, after a launch was already attempted. Typed rather than matched
	 *   on [message] because repeating it is the evidence that the app cannot stay up at all -
	 *   a baseline that crashes in `onCreate` - which no edit can fix and no relaunch clears.
	 */
	data class DeployFailure(
		val message: String,
		val proxyAppNotConnected: Boolean = false,
	) : BuildOutcome

	/**
	 * The build pipeline itself broke (daemon died, I/O error) - not the user's code.
	 *
	 * @property message what broke, for the status surface and the log.
	 * @property daemonDied true when the daemon process is gone, so the session must start a new
	 *   one with empty incremental caches before the next build.
	 */
	data class InfrastructureFailure(
		val message: String,
		val daemonDied: Boolean = false,
	) : BuildOutcome
}

/**
 * One compiler message, tagged file:line so the status surface can jump to the editor.
 *
 * @property severity whether the message failed the build or only warned.
 * @property message the compiler's text, unformatted and not localized.
 * @property file absolute path of the offending source; null when the compiler named none.
 * @property line 1-based line number; null when the compiler named none.
 * @property column 1-based column number; null when the compiler named none.
 */
data class BuildDiagnostic(
	val severity: Severity,
	val message: String,
	val file: String? = null,
	val line: Int? = null,
	val column: Int? = null,
) {
	/** How much a diagnostic matters: only [ERROR] fails a build. */
	enum class Severity { ERROR, WARNING }
}
