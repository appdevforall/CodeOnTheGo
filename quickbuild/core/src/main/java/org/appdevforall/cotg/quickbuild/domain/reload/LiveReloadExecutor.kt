package org.appdevforall.cotg.quickbuild.domain.reload

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason

/**
 * Runs one quick build end to end: compile (if the route needs it), dex, relink, deploy.
 *
 * Called with at most one request in flight (the [LiveReloadOrchestrator] guarantees it), and
 * never with a [BuildRoute.FullGradleBuild] route. Must NOT throw for build problems - report
 * them as a [BuildOutcome]; an escaped exception becomes [BuildOutcome.InfrastructureFailure].
 */
interface LiveReloadExecutor {
	/**
	 * Runs one build to completion and reports how it ended.
	 *
	 * @param request what to build, already routed; the executor does not re-classify it.
	 * @return how the build ended - only [BuildOutcome.Success] means the proxy app moved to a new
	 *   generation, every other outcome leaves it on the old one.
	 */
	suspend fun execute(request: BuildRequest): BuildOutcome

	/**
	 * Promotes the build already running to a user-initiated one, so its deploy may take the
	 * foreground.
	 *
	 * A tap landing while a save's build is in flight is answered by that build rather than
	 * queueing a second, so the intent arrives after [execute] was called with
	 * [BuildRequest.userInitiated] false; without this a tap against a closed app would do nothing.
	 */
	fun markCurrentBuildUserInitiated() = Unit
}

/**
 * One build the executor is asked to run.
 *
 * @property buildId orchestrator-unique id; tags diagnostics so a superseded build's output is
 *   discarded rather than rendered.
 * @property changes the coalesced changed-set this build must absorb, with [ChangedFiles.Unknown]
 *   meaning recompile everything.
 * @property route the classifier's verdict, which fixes which steps run; never a
 *   [BuildRoute.FullGradleBuild].
 * @property forced true for an explicit Quick Build tap - the executor must deploy even when
 *   [changes] is empty, by rebuilding the current sources at a FRESH generation, since the
 *   runtime only accepts strictly-newer generations.
 * @property triggeredAtMillis monotonic stamp of when the earliest change in this build started
 *   WAITING for it - t0 of the [org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline], on
 *   the clock the executor stamps t1-t3 with, restarted at the next save for a batch a failed
 *   build handed back (see `LiveReloadOrchestrator.pendingSince`) and 0 when there is no clock.
 * @property userInitiated true only when a Quick Build tap asked for this build, which is what
 *   licenses the deploy to bring the proxy app to the foreground - a save must never take the
 *   screen from someone who is still typing.
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
	 * @property durationMillis the whole save-to-live loop measured from [triggeredAtMillis] - the
	 *   span the user actually waited, not build time alone, which reads as a second contradictory
	 *   total beside the timing line; falls back to the build's own start with no trigger stamp.
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
	 * @property kotlinDeclaredChanged Kotlin sources the daemon declared changed to its engine,
	 *   or null when it reported none. 0 vs >= 1 separates two causes of a stale mixed-language
	 *   output: 0 means the edit never entered the dirty set, so the fix is upstream in
	 *   changed-set assembly; >= 1 means it did and the staleness is downstream. Null is
	 *   ABSENT, never a measured zero.
	 * @property allSources the source set this compile was handed - [kotlinDeclaredChanged]'s
	 *   denominator, without which "0" cannot be read.
	 * @property javaSources `.java` count, all of which javac recompiles every build.
	 *
	 * These are DIAGNOSTIC counts for the bench feed, deliberately plain numbers rather than the
	 * daemon's `CompileStats`: no file in this domain package imports the wire protocol, and a
	 * counter is a poor reason to be the first. They must NOT be threaded into `SessionFailure`
	 * - that boundary is what keeps them out of the user-facing Build Output pane, which
	 *   consumes `SessionFailure` and should never show an engine statistic.
	 */
	data class CompileError(
		val diagnostics: List<BuildDiagnostic>,
		val kotlinDeclaredChanged: Int? = null,
		val allSources: Int? = null,
		val javaSources: Int? = null,
	) : BuildOutcome

	/**
	 * Compile succeeded but the payload never reached the proxy app (deploy/reload failed).
	 *
	 * @property message what failed, for the status surface; the built outputs stay on disk, so
	 *   the retry does not recompile them from scratch.
	 * @property proxyAppNotConnected true when the payload had nowhere to land because the proxy
	 *   app was not connected after a launch was already attempted, typed rather than matched on
	 *   [message] because repeating it is the evidence that the app cannot stay up at all (a
	 *   baseline that crashes in `onCreate`), which no edit fixes and no relaunch clears.
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
 * One compiler message, tagged file:line so the status surface can name where it failed.
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
