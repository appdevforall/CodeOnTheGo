package org.appdevforall.cotg.quickbuild.domain

/**
 * Quick-build session lifecycle states (plan section 2.1) — one sealed type, not booleans.
 *
 * [generation] on the live states is the generation the TEST APP currently runs — the
 * "running gen N" honesty line derives from it. A compile error keeps the session in
 * [Ready] at the old generation with [Ready.lastFailure] set; the test app never moved.
 */
sealed interface QuickBuildSessionState {
	/** No session. The Quick Build button starts provisioning. */
	data object Idle : QuickBuildSessionState

	/**
	 * The eager setup build runs in the background (project open, plan B2) - no install,
	 * no daemon, no session. [tapQueued] records a Quick Build tap that landed mid-warm:
	 * provisioning starts the moment the warm build finishes instead of racing it (two
	 * concurrent Gradle builds through the tooling server would fail).
	 */
	data class Prewarming(
		val tapQueued: Boolean = false,
	) : QuickBuildSessionState

	/** Setup build + test-app install + daemon spawn in progress. */
	data object Provisioning : QuickBuildSessionState

	/** Session live, no build running. [lastFailure] is surfaced until the next build. */
	data class Ready(
		val generation: Long,
		val lastFailure: SessionFailure? = null,
	) : QuickBuildSessionState

	/** A quick build is running; the test app still runs [deployedGeneration]. */
	data class Building(
		val deployedGeneration: Long,
	) : QuickBuildSessionState

	/**
	 * A build just landed; the test app runs [generation]. [restarted] true = it landed
	 * via the process-restart path (service/provider/Application code changed), so the
	 * test app relaunched at its launcher and lost in-process state - the status surface
	 * says so instead of a plain "reloaded".
	 */
	data class Deployed(
		val generation: Long,
		val buildDurationMillis: Long,
		val restarted: Boolean = false,
	) : QuickBuildSessionState

	/** The baseline is stale (manifest/gradle/external build); needs a full Gradle build. */
	data class Invalidated(
		val reason: InvalidationReason,
		val deployedGeneration: Long,
	) : QuickBuildSessionState

	/** The compile daemon died; respawn + re-seed in progress. */
	data class Degraded(
		val deployedGeneration: Long,
	) : QuickBuildSessionState
}

/** Why the last quick build did not move the test app to a new generation. */
sealed interface SessionFailure {
	data class CompileError(
		val diagnostics: List<BuildDiagnostic>,
	) : SessionFailure

	data class DeployError(
		val message: String,
	) : SessionFailure

	/** The payload crashed in the test app (render/lifecycle) — distinct from a compile error. */
	data class TestAppCrash(
		val summary: String,
	) : SessionFailure
}

/** Inputs to [SessionReducer] — from the UI, the orchestrator, and process observers. */
sealed interface SessionEvent {
	data object QuickBuildTapped : SessionEvent

	/** Project opened with the feature enabled: warm the setup build, defer the install. */
	data object PrewarmRequested : SessionEvent

	/** The eager setup build finished (success or not - a warm failure is not surfaced). */
	data object PrewarmFinished : SessionEvent

	data class ProvisioningSucceeded(
		val generation: Long,
	) : SessionEvent

	data class ProvisioningFailed(
		val message: String,
	) : SessionEvent

	data object BuildStarted : SessionEvent

	data class BuildSucceeded(
		val generation: Long,
		val durationMillis: Long,
		/** True when the deploy restarted the test-app process (component code changed). */
		val restarted: Boolean = false,
	) : SessionEvent

	data class BuildFailed(
		val failure: SessionFailure,
	) : SessionEvent

	data class InvalidationDetected(
		val reason: InvalidationReason,
	) : SessionEvent

	/** The full Gradle re-baseline build has been kicked off. */
	data object RebaselineStarted : SessionEvent

	/**
	 * A full Gradle build ran OUTSIDE the session (a Standard Run) and completed. The
	 * baseline may have moved beneath the daemon (regenerated build/ inputs the watcher
	 * cannot see), so a live session must re-seed from current disk before its next build.
	 */
	data object ExternalBuildCompleted : SessionEvent

	data object DaemonDied : SessionEvent

	data object DaemonRespawned : SessionEvent

	data class TestAppCrashed(
		val summary: String,
	) : SessionEvent

	/** User-requested escape hatch (plan A2 dropdown "Restart session"). Valid from any state. */
	data object SessionRestartRequested : SessionEvent
}

/** Side effects the session manager must run after a transition. */
sealed interface SessionEffect {
	data object StartProvisioning : SessionEffect

	/** Run the setup build only - no install, no daemon (plan B2's eager warm-up). */
	data object StartPrewarm : SessionEffect

	/** Ask the orchestrator to build now (explicit tap while a session is live). */
	data object TriggerQuickBuild : SessionEffect

	/** Route to the real Gradle build; on completion the session re-baselines. */
	data object RunFullGradleRebaseline : SessionEffect

	/**
	 * Re-seed the live session after an external full build: either mark the whole
	 * incremental baseline dirty (next build recompiles from current disk) or, when the
	 * external build clobbered the setup artifacts, escalate to a full rebaseline with
	 * [InvalidationReason.EXTERNAL_FULL_BUILD]. The shell decides which.
	 */
	data object ReseedBaseline : SessionEffect

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

/**
 * Pure transition function for the session state machine. Unknown (state, event) pairs
 * keep the current state and produce no effects — a late/duplicate event must never
 * corrupt the session (the shell logs them; this keeps the reducer total and testable).
 */
class SessionReducer {
	fun reduce(
		state: QuickBuildSessionState,
		event: SessionEvent,
	): SessionTransition {
		// Handled once, for every state: restart always wins and always tears down -
		// duplicating this arm into all seven per-state reducers below would be pure
		// repetition for a transition that never depends on which state it came from.
		// Idle has nothing to tear down; fall through so it's the total no-op every
		// other unhandled event gets there.
		if (event == SessionEvent.SessionRestartRequested && state != QuickBuildSessionState.Idle) {
			return SessionTransition(QuickBuildSessionState.Idle, listOf(SessionEffect.TeardownSession))
		}
		return reduceByState(state, event)
	}

	private fun reduceByState(
		state: QuickBuildSessionState,
		event: SessionEvent,
	): SessionTransition =
		when (state) {
			is QuickBuildSessionState.Idle -> reduceIdle(state, event)
			is QuickBuildSessionState.Prewarming -> reducePrewarming(state, event)
			is QuickBuildSessionState.Provisioning -> reduceProvisioning(state, event)
			is QuickBuildSessionState.Ready -> reduceLive(state, state.generation, event)
			is QuickBuildSessionState.Building -> reduceBuilding(state, event)
			is QuickBuildSessionState.Deployed -> reduceLive(state, state.generation, event)
			is QuickBuildSessionState.Invalidated -> reduceInvalidated(state, event)
			is QuickBuildSessionState.Degraded -> reduceDegraded(state, event)
		}

	private fun reduceIdle(
		state: QuickBuildSessionState,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.QuickBuildTapped ->
				SessionTransition(QuickBuildSessionState.Provisioning, listOf(SessionEffect.StartProvisioning))
			SessionEvent.PrewarmRequested ->
				SessionTransition(QuickBuildSessionState.Prewarming(), listOf(SessionEffect.StartPrewarm))
			else -> SessionTransition(state)
		}

	private fun reducePrewarming(
		state: QuickBuildSessionState.Prewarming,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			// The tap must not race the warm build (one Gradle build at a time through
			// the tooling server); it queues and fires on PrewarmFinished.
			SessionEvent.QuickBuildTapped ->
				SessionTransition(QuickBuildSessionState.Prewarming(tapQueued = true))
			SessionEvent.PrewarmFinished ->
				if (state.tapQueued) {
					SessionTransition(
						QuickBuildSessionState.Provisioning,
						listOf(SessionEffect.StartProvisioning),
					)
				} else {
					SessionTransition(QuickBuildSessionState.Idle)
				}
			else -> SessionTransition(state)
		}

	private fun reduceProvisioning(
		state: QuickBuildSessionState,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			is SessionEvent.ProvisioningSucceeded ->
				SessionTransition(QuickBuildSessionState.Ready(event.generation))
			is SessionEvent.ProvisioningFailed ->
				SessionTransition(
					QuickBuildSessionState.Idle,
					listOf(SessionEffect.SurfaceProvisioningError(event.message)),
				)
			else -> SessionTransition(state)
		}

	/** Shared by [QuickBuildSessionState.Ready] and [QuickBuildSessionState.Deployed]. */
	private fun reduceLive(
		state: QuickBuildSessionState,
		generation: Long,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.QuickBuildTapped ->
				SessionTransition(state, listOf(SessionEffect.TriggerQuickBuild))
			SessionEvent.BuildStarted ->
				SessionTransition(QuickBuildSessionState.Building(generation))
			is SessionEvent.InvalidationDetected ->
				SessionTransition(
					QuickBuildSessionState.Invalidated(event.reason, generation),
					listOf(SessionEffect.RunFullGradleRebaseline),
				)
			SessionEvent.DaemonDied ->
				SessionTransition(
					QuickBuildSessionState.Degraded(generation),
					listOf(SessionEffect.RespawnDaemon),
				)
			is SessionEvent.TestAppCrashed ->
				SessionTransition(
					QuickBuildSessionState.Ready(generation, SessionFailure.TestAppCrash(event.summary)),
				)
			SessionEvent.ExternalBuildCompleted ->
				SessionTransition(state, listOf(SessionEffect.ReseedBaseline))
			else -> SessionTransition(state)
		}

	private fun reduceBuilding(
		state: QuickBuildSessionState.Building,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			is SessionEvent.BuildSucceeded ->
				SessionTransition(
					QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted),
				)
			is SessionEvent.BuildFailed ->
				SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration, event.failure))
			is SessionEvent.InvalidationDetected ->
				SessionTransition(
					QuickBuildSessionState.Invalidated(event.reason, state.deployedGeneration),
					listOf(SessionEffect.RunFullGradleRebaseline),
				)
			SessionEvent.DaemonDied ->
				SessionTransition(
					QuickBuildSessionState.Degraded(state.deployedGeneration),
					listOf(SessionEffect.RespawnDaemon),
				)
			is SessionEvent.TestAppCrashed ->
				// The old generation crashed while the next build runs; stay Building.
				SessionTransition(state)
			SessionEvent.ExternalBuildCompleted ->
				// The in-flight build may have read half-rewritten inputs; the re-seed
				// coalesces into the follow-up build, which recompiles everything.
				SessionTransition(state, listOf(SessionEffect.ReseedBaseline))
			else -> SessionTransition(state)
		}

	private fun reduceInvalidated(
		state: QuickBuildSessionState.Invalidated,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.RebaselineStarted ->
				SessionTransition(QuickBuildSessionState.Provisioning)
			else -> SessionTransition(state)
		}

	private fun reduceDegraded(
		state: QuickBuildSessionState.Degraded,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.DaemonRespawned ->
				SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration))
			SessionEvent.DaemonDied -> SessionTransition(state)
			is SessionEvent.InvalidationDetected ->
				// Dropping this would strand the session: the orchestrator reports an
				// invalidation ONCE, so a gradle/manifest edit landing while Degraded
				// would otherwise never rebaseline and no build would ever run again.
				// The rebaseline needs Gradle, not the daemon - safe to run while a
				// respawn is still in flight.
				SessionTransition(
					QuickBuildSessionState.Invalidated(event.reason, state.deployedGeneration),
					listOf(SessionEffect.RunFullGradleRebaseline),
				)
			SessionEvent.ExternalBuildCompleted ->
				SessionTransition(state, listOf(SessionEffect.ReseedBaseline))
			else -> SessionTransition(state)
		}
}
