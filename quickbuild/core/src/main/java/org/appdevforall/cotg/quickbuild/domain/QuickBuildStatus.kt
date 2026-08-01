package org.appdevforall.cotg.quickbuild.domain

/**
 * What the status surface should show — derived purely from session state, never set and
 * cleared imperatively. Deriving it from state makes a stuck transient banner
 * unrepresentable: every state maps to exactly one status, so every terminal state
 * clears the transient one. An event-driven banner cleared only on successful render
 * leaves "Compiling…" up forever after a compile error or a payload crash.
 */
sealed interface QuickBuildStatus {
	/** No session — show nothing. */
	data object Hidden : QuickBuildStatus

	data object Provisioning : QuickBuildStatus

	data class Building(
		val runningGeneration: Long,
	) : QuickBuildStatus

	/**
	 * [restarted] true = the last deploy relaunched the proxy-app process (a
	 * service/provider/Application class changed); the surface should phrase it as a
	 * restart ("Restarted <app> - component code changed"), not a plain reload.
	 */
	data class UpToDate(
		val generation: Long,
		val buildDurationMillis: Long?,
		val restarted: Boolean = false,
	) : QuickBuildStatus

	/** Honesty line: the proxy app still runs [runningGeneration]; the edit did not land. */
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
				QuickBuildSessionState.Idle -> {
					Hidden
				}

				// A background warm-up the user never asked for stays invisible; a tap
				// that queued mid-warm reads as provisioning already underway.
				is QuickBuildSessionState.Prebuilding -> {
					if (state.tapQueued) Provisioning else Hidden
				}

				is QuickBuildSessionState.Provisioning -> {
					Provisioning
				}

				is QuickBuildSessionState.Ready -> {
					state.lastFailure?.let { Failed(state.generation, it) }
						?: UpToDate(state.generation, buildDurationMillis = null)
				}

				is QuickBuildSessionState.Building -> {
					when {
						// A real build: the proxy app is one generation behind, say so.
						!state.warmingCompiler -> Building(state.deployedGeneration)

						// A crash of the running generation observed mid-warm-compile surfaces
						// immediately, exactly as it would outside the warm-compile window.
						state.pendingCrash != null -> Failed(state.deployedGeneration, state.pendingCrash)

						// The background warm compile compiles what already runs and deploys
						// nothing - presenting it as a blocking "Building" for its whole
						// 12-50s window would be a lie. The app is genuinely up to date.
						else -> UpToDate(state.deployedGeneration, buildDurationMillis = null)
					}
				}

				is QuickBuildSessionState.Deployed -> {
					UpToDate(state.generation, state.buildDurationMillis, state.restarted)
				}

				is QuickBuildSessionState.Invalidated -> {
					NeedsFullBuild(state.reason, state.deployedGeneration)
				}

				is QuickBuildSessionState.Degraded -> {
					Reconnecting(state.deployedGeneration)
				}
			}
	}
}
