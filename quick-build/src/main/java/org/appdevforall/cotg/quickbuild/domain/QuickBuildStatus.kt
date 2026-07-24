package org.appdevforall.cotg.quickbuild.domain

/**
 * What the status surface should show — derived purely from session state, never set and
 * cleared imperatively. The prototype's banner was event-driven and only cleared on a
 * successful render, so a compile error or payload crash left "Compiling…" stuck forever.
 * Deriving the banner from state makes that failure mode unrepresentable: every state
 * maps to exactly one status, so every terminal state clears the transient one.
 */
sealed interface QuickBuildStatus {
	/** No session — show nothing. */
	data object Hidden : QuickBuildStatus

	data object Provisioning : QuickBuildStatus

	data class Building(
		val runningGeneration: Long,
	) : QuickBuildStatus

	/**
	 * [restarted] true = the last deploy relaunched the test-app process (a
	 * service/provider/Application class changed); the surface should phrase it as a
	 * restart ("Restarted <app> - component code changed"), not a plain reload.
	 */
	data class UpToDate(
		val generation: Long,
		val buildDurationMillis: Long?,
		val restarted: Boolean = false,
	) : QuickBuildStatus

	/** Honesty line: the test app still runs [runningGeneration]; the edit did not land. */
	data class Failed(
		val runningGeneration: Long,
		val failure: SessionFailure,
	) : QuickBuildStatus

	data class NeedsFullBuild(
		val reason: InvalidationReason,
		val runningGeneration: Long,
	) : QuickBuildStatus

	data class Reconnecting(
		val runningGeneration: Long,
	) : QuickBuildStatus

	companion object {
		fun from(state: QuickBuildSessionState): QuickBuildStatus =
			when (state) {
				QuickBuildSessionState.Idle -> Hidden
				// A background warm-up the user never asked for stays invisible; a tap
				// that queued mid-warm reads as provisioning already underway.
				is QuickBuildSessionState.Prewarming ->
					if (state.tapQueued) Provisioning else Hidden
				QuickBuildSessionState.Provisioning -> Provisioning
				is QuickBuildSessionState.Ready ->
					state.lastFailure?.let { Failed(state.generation, it) }
						?: UpToDate(state.generation, buildDurationMillis = null)
				is QuickBuildSessionState.Building -> Building(state.deployedGeneration)
				is QuickBuildSessionState.Deployed ->
					UpToDate(state.generation, state.buildDurationMillis, state.restarted)
				is QuickBuildSessionState.Invalidated -> NeedsFullBuild(state.reason, state.deployedGeneration)
				is QuickBuildSessionState.Degraded -> Reconnecting(state.deployedGeneration)
			}
	}
}
