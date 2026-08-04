package org.appdevforall.cotg.quickbuild.domain

/**
 * What the status surface should show, derived purely from session state rather than set and
 * cleared imperatively.
 *
 * Deriving it makes a stuck banner unrepresentable: every state maps to exactly one status, so
 * every terminal state clears the transient one. A banner cleared only on successful render
 * would leave "Compiling..." up forever after a compile error or a payload crash.
 */
sealed interface QuickBuildStatus {
	/** No session - show nothing. */
	data object Hidden : QuickBuildStatus

	/** Proxy app build, install and daemon spawn in progress. */
	data object Provisioning : QuickBuildStatus

	/**
	 * A build is running; the proxy app still runs [runningGeneration].
	 *
	 * @property runningGeneration the generation live in the proxy app right now, one behind the
	 *   build in flight.
	 */
	data class Building(
		val runningGeneration: Long,
	) : QuickBuildStatus

	/**
	 * The proxy app is running the latest edit.
	 *
	 * @property generation the generation the proxy app runs, which is also the latest built.
	 * @property buildDurationMillis how long the build that landed took, in milliseconds; null when
	 *   no build landed in this session yet, and the surface then shows no timing.
	 * @property restarted the deploy relaunched the proxy-app process (service/provider/Application
	 *   code changed), so the surface phrases it as a restart rather than a plain reload.
	 */
	data class UpToDate(
		val generation: Long,
		val buildDurationMillis: Long?,
		val restarted: Boolean = false,
	) : QuickBuildStatus

	/**
	 * The edit did not land; the proxy app still runs [runningGeneration].
	 *
	 * @property runningGeneration the generation still live in the proxy app - a failure never
	 *   moves it.
	 * @property failure what went wrong: a compile error, a failed deploy, or a crash of the
	 *   running generation.
	 */
	data class Failed(
		val runningGeneration: Long,
		val failure: SessionFailure,
	) : QuickBuildStatus

	/**
	 * The baseline is stale; only a full Gradle build can move the proxy app forward.
	 *
	 * @property reason what the live reload path could not absorb, which the surface names to the
	 *   user.
	 * @property runningGeneration the generation still live in the proxy app until the rebuild
	 *   lands.
	 */
	data class NeedsFullBuild(
		val reason: InvalidationReason,
		val runningGeneration: Long,
	) : QuickBuildStatus

	/**
	 * The compile daemon died and is being respawned.
	 *
	 * @property runningGeneration the generation the proxy app keeps running through the outage -
	 *   its process is untouched.
	 */
	data class Reconnecting(
		val runningGeneration: Long,
	) : QuickBuildStatus

	companion object {
		/**
		 * Maps a session state to the one status that represents it.
		 *
		 * @param state the current session state; every state maps, so no caller has to handle a
		 *   missing status.
		 * @return the status to render, [Hidden] when the surface should show nothing.
		 */
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

						// A crash of the running generation surfaces immediately, exactly as it
						// would outside the warm-compile window.
						state.pendingCrash != null -> Failed(state.deployedGeneration, state.pendingCrash)

						// The warm compile recompiles what already runs and deploys nothing,
						// so the app is genuinely up to date for its whole window.
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
