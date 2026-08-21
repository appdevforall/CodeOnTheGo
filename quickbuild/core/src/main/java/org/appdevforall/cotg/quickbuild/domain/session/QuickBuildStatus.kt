package org.appdevforall.cotg.quickbuild.domain.session

import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason

/**
 * What the status surface should show, derived purely from session state rather than set and
 * cleared imperatively.
 *
 * Deriving it makes a stuck banner unrepresentable: every state maps to exactly one status, so
 * every terminal state clears the transient one. A banner cleared only on successful render
 * would leave "Compiling..." up forever after a compile error or a payload crash.
 */
sealed interface QuickBuildStatus {
	/**
	 * No session - nothing in progress to narrate.
	 *
	 * @property lastStartFailed the last session start failed
	 *   ([QuickBuildSessionState.Idle.lastStartFailed]), so the bolt keeps the error tone until
	 *   the next tap or save; carried here because a failed start rests in Hidden and the tone
	 *   is derived from status alone.
	 */
	data class Hidden(
		val lastStartFailed: Boolean = false,
	) : QuickBuildStatus

	/**
	 * Proxy app build, install and daemon spawn in progress.
	 *
	 * @property rebaselineReason what invalidated the old baseline, or null on a session's first
	 *   provision; it has to travel in the status because this conflating
	 *   [kotlinx.coroutines.flow.StateFlow] lets a surface miss the [NeedsFullBuild] that preceded a
	 *   rebaseline and then call it "the initial full build".
	 */
	data class Provisioning(
		val rebaselineReason: InvalidationReason? = null,
	) : QuickBuildStatus

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
	 * @property buildDurationMillis how long the landed save-to-live loop took, in milliseconds -
	 *   the whole wait, not the build alone; null when no build landed in this session yet, and
	 *   the surface then shows no timing.
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
	 * @property awaitingRetry a rebaseline already ran and parked (build failed or install not
	 *   confirmed), so the surface must read as a failure the user resolves rather than ordinary
	 *   upcoming work; see [QuickBuildSessionState.Invalidated.awaitingRetry].
	 */
	data class NeedsFullBuild(
		val reason: InvalidationReason,
		val runningGeneration: Long,
		val awaitingRetry: Boolean = false,
	) : QuickBuildStatus

	/**
	 * The compile daemon died and is being respawned.
	 *
	 * @property runningGeneration the generation the proxy app keeps running through the outage -
	 *   its process is untouched.
	 * @property restartFailed the respawn did not stick and nothing is retrying it, so the surface
	 *   must name the gesture that brings the compiler back rather than claim a restart is in
	 *   progress; see [QuickBuildSessionState.Degraded.restartFailed].
	 */
	data class Reconnecting(
		val runningGeneration: Long,
		val restartFailed: Boolean = false,
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
				is QuickBuildSessionState.Idle -> {
					Hidden(state.lastStartFailed)
				}

				// A warm-up the user never asked for stays invisible - but it must not clear a
				// failed-start tone on its way through, so the flag rides along.
				is QuickBuildSessionState.Prebuilding -> {
					// A warm build has no baseline to replace, so a tap that queues on one is
					// always a session's first provision.
					if (state.tapQueued) Provisioning() else Hidden(state.lastStartFailed)
				}

				is QuickBuildSessionState.Provisioning -> {
					Provisioning(state.rebaselineReason)
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
					NeedsFullBuild(state.reason, state.deployedGeneration, state.awaitingRetry)
				}

				is QuickBuildSessionState.Degraded -> {
					Reconnecting(state.deployedGeneration, state.restartFailed)
				}
			}
	}
}
