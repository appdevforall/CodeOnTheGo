package org.appdevforall.cotg.quickbuild.domain

/**
 * Runs one quick build end to end: compile (if the route needs it), dex, relink, deploy.
 * Implemented by the warm-daemon pipeline (plan section 2.3/2.4); tests use scripted fakes.
 *
 * Contract:
 * - Called with at most one request in flight (the [BuildOrchestrator] guarantees it).
 * - Must NOT throw for build problems — report them as a [BuildOutcome]; an escaped
 *   exception is treated by the orchestrator as [BuildOutcome.InfrastructureFailure].
 * - Never receives a [BuildRoute.FullGradleBuild] route (those bypass the quick path).
 */
interface QuickBuildExecutor {
	suspend fun execute(request: BuildRequest): BuildOutcome
}

/**
 * @property buildId orchestrator-unique id; tags diagnostics so a superseded build's
 *   output is discarded, never rendered.
 * @property forced true for an explicit Quick Build tap — the executor must produce a
 *   deploy even when [changes] is empty, by rebuilding the current sources at a FRESH
 *   generation (the runtime only accepts strictly-newer generations, so replaying the
 *   current one can never land; see QuickBuildExecutorImpl's NoOp branch).
 */
data class BuildRequest(
	val buildId: Long,
	val changes: ChangedFiles,
	val route: BuildRoute,
	val forced: Boolean = false,
)

sealed interface BuildOutcome {
	/**
	 * Compiled, deployed and reloaded: the test app now runs [generation].
	 * [restarted] true = the deploy went through the process-restart path (a
	 * service/provider/Application class changed): the payload was persisted, the
	 * process exited and was relaunched, instead of a hot swap.
	 */
	data class Success(
		val generation: Long,
		val durationMillis: Long,
		val restarted: Boolean = false,
	) : BuildOutcome

	/**
	 * The build succeeded but the quick path must not deploy it: the installed
	 * baseline cannot take a restart-requiring payload safely (its runtime would
	 * ignore the restart request and hot-swap, leaving a live service stale). The
	 * session manager routes [reason] into the full-rebaseline fallback, which
	 * regenerates the baseline; the changed set stays pending and is absorbed there.
	 */
	data class RequiresRebaseline(
		val reason: InvalidationReason,
		val detail: String,
	) : BuildOutcome

	/** The changed-set does not compile. The test app keeps running the old generation. */
	data class CompileError(
		val diagnostics: List<BuildDiagnostic>,
	) : BuildOutcome

	/** Compile succeeded but the payload never reached the test app (deploy/reload failed). */
	data class DeployFailure(
		val message: String,
	) : BuildOutcome

	/** The build pipeline itself broke (daemon died, I/O error) — not the user's code. */
	data class InfrastructureFailure(
		val message: String,
		val daemonDied: Boolean = false,
	) : BuildOutcome
}

/** One compiler message, tagged file:line so the status surface can jump to the editor. */
data class BuildDiagnostic(
	val severity: Severity,
	val message: String,
	val file: String? = null,
	val line: Int? = null,
	val column: Int? = null,
) {
	enum class Severity { ERROR, WARNING }
}
