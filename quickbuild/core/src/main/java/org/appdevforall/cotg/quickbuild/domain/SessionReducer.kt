package org.appdevforall.cotg.quickbuild.domain

/**
 * Pure transition function for the session state machine.
 *
 * The reducer is total: an unknown (state, event) pair keeps the current state and produces no
 * effects, so a late or duplicate event can never corrupt the session. The shell logs those.
 */
class SessionReducer {
	/**
	 * Maps a state and an incoming event to the next state plus the effects to run.
	 *
	 * @param state the session's current state.
	 * @param event what happened; a state that does not handle it keeps [state] unchanged rather
	 *   than failing.
	 * @return the state to adopt and the effects the shell must then run, in order.
	 */
	fun reduce(
		state: QuickBuildSessionState,
		event: SessionEvent,
	): SessionTransition {
		// Restart always wins and always tears down, whatever state it came from, so it is
		// handled once here rather than repeated in every per-state reducer. Idle has nothing
		// to tear down and falls through to the usual no-op.
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
			is QuickBuildSessionState.Prebuilding -> reducePrebuilding(state, event)
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
			SessionEvent.QuickBuildTapped -> {
				SessionTransition(
					QuickBuildSessionState.Provisioning(userInitiated = true),
					listOf(SessionEffect.StartProvisioning),
				)
			}

			SessionEvent.PrebuildRequested -> {
				SessionTransition(QuickBuildSessionState.Prebuilding(), listOf(SessionEffect.StartProxyAppPrebuild))
			}

			else -> {
				SessionTransition(state)
			}
		}

	private fun reducePrebuilding(
		state: QuickBuildSessionState.Prebuilding,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			// The tap must not race the warm build (one Gradle build at a time through
			// the tooling server); it queues and fires on PrebuildFinished.
			SessionEvent.QuickBuildTapped -> {
				SessionTransition(QuickBuildSessionState.Prebuilding(tapQueued = true))
			}

			SessionEvent.PrebuildFinished -> {
				if (state.tapQueued) {
					SessionTransition(
						QuickBuildSessionState.Provisioning(userInitiated = true),
						listOf(SessionEffect.StartProvisioning),
					)
				} else {
					SessionTransition(QuickBuildSessionState.Idle)
				}
			}

			SessionEvent.CancelRequested -> {
				if (state.tapQueued) {
					// The button only shows the stop affordance once a tap has queued, so a
					// cancel here means drop the queued tap AND stop the Gradle build it waits on.
					SessionTransition(QuickBuildSessionState.Idle, listOf(SessionEffect.CancelProxyAppBuild))
				} else {
					SessionTransition(state)
				}
			}

			else -> {
				SessionTransition(state)
			}
		}

	private fun reduceProvisioning(
		state: QuickBuildSessionState.Provisioning,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			is SessionEvent.ProvisioningSucceeded -> {
				SessionTransition(
					QuickBuildSessionState.Ready(event.generation),
					// Behaviour 2: nothing else launches the freshly installed proxy app, so a
					// tap gets its answer here. A rebuild routed through this state stays in
					// the editor.
					if (state.userInitiated) {
						listOf(SessionEffect.StartWarmCompile, SessionEffect.SwitchToProxyApp)
					} else {
						listOf(SessionEffect.StartWarmCompile)
					},
				)
			}

			SessionEvent.CancelRequested -> {
				// No half-provisioned session is worth keeping. A cancel mid-install is safe
				// because the epoch guard discards a late provisioning success, and the next
				// tap re-provisions from build outputs still on disk.
				SessionTransition(
					QuickBuildSessionState.Idle,
					listOf(SessionEffect.CancelProxyAppBuild, SessionEffect.TeardownSession),
				)
			}

			is SessionEvent.ProvisioningFailed -> {
				SessionTransition(
					QuickBuildSessionState.Idle,
					listOf(SessionEffect.SurfaceProvisioningError(event.message)),
				)
			}

			is SessionEvent.ProxyAppRebuildDeferred -> {
				// Park back where the retry came from and refund the attempt: it ran no Gradle
				// build and prompted no install, which is what the budget bounds. Floored at
				// zero, since a tap-initiated retry arrives having already reset it.
				SessionTransition(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						event.deployedGeneration,
						awaitingRetry = true,
						installAutoRetries = (state.installAutoRetries - 1).coerceAtLeast(0),
					),
				)
			}

			is SessionEvent.ProxyAppRebuildInstallNotConfirmed -> {
				// Only the install confirmation is missing, so park with no effect - retrying
				// here would re-prompt forever. The next tap or foreground return retries. The
				// auto-retry count survives so the budget is spent per unconfirmed install,
				// not per park.
				SessionTransition(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.INSTALL_NOT_CONFIRMED,
						event.deployedGeneration,
						awaitingRetry = true,
						installAutoRetries = state.installAutoRetries,
					),
				)
			}

			else -> {
				SessionTransition(state)
			}
		}

	/**
	 * Shared by [QuickBuildSessionState.Ready] and [QuickBuildSessionState.Deployed].
	 *
	 * @param state the live state to return to when the event changes nothing.
	 * @param generation the generation the proxy app runs, passed separately because the two live
	 *   states carry it under different property names.
	 * @param event what happened while the session was live.
	 * @return the state to adopt and the effects the shell must then run.
	 */
	private fun reduceLive(
		state: QuickBuildSessionState,
		generation: Long,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.QuickBuildTapped -> {
				SessionTransition(state, listOf(SessionEffect.TriggerLiveReload(userInitiated = true)))
			}

			SessionEvent.BuildStarted -> {
				SessionTransition(QuickBuildSessionState.Building(generation))
			}

			SessionEvent.WarmCompileStarted -> {
				SessionTransition(QuickBuildSessionState.Building(generation, warmingCompiler = true))
			}

			is SessionEvent.InvalidationDetected -> {
				SessionTransition(
					QuickBuildSessionState.Invalidated(event.reason, generation),
					listOf(SessionEffect.RunProxyAppRebuild),
				)
			}

			SessionEvent.DaemonDied -> {
				SessionTransition(
					QuickBuildSessionState.Degraded(generation),
					listOf(SessionEffect.RespawnDaemon),
				)
			}

			is SessionEvent.ProxyAppCrashed -> {
				SessionTransition(
					QuickBuildSessionState.Ready(generation, SessionFailure.ProxyAppCrash(event.summary)),
				)
			}

			SessionEvent.ExternalBuildCompleted -> {
				SessionTransition(state, listOf(SessionEffect.RefreshBaseline))
			}

			else -> {
				SessionTransition(state)
			}
		}

	private fun reduceBuilding(
		state: QuickBuildSessionState.Building,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			is SessionEvent.BuildSucceeded -> {
				SessionTransition(
					QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted),
					// Behaviour 2 vs 3: the deploy landing is where a TAP gets its answer, and
					// where a save deliberately gets none - the user is still editing.
					if (event.userInitiated) listOf(SessionEffect.SwitchToProxyApp) else emptyList(),
				)
			}

			is SessionEvent.BuildFailed -> {
				SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration, event.failure))
			}

			SessionEvent.QuickBuildTapped -> {
				if (state.warmingCompiler) {
					// A warm compile deploys nothing, so the tap would otherwise vanish. The
					// orchestrator queues it and builds right after, keeping single-flight.
					SessionTransition(state, listOf(SessionEffect.TriggerLiveReload(userInitiated = true)))
				} else {
					// The in-flight build satisfies the tap's build but not the ask, so record
					// the ask on it (behaviour 2) rather than dropping it.
					SessionTransition(state, listOf(SessionEffect.MarkBuildUserInitiated))
				}
			}

			SessionEvent.CancelRequested -> {
				if (state.warmingCompiler) {
					// The warm compile is not the user's build: unasked for, deploys nothing,
					// and the button shows the bolt throughout. Nothing here to cancel.
					SessionTransition(state)
				} else {
					// Behaviour 5: back to the generation the proxy app still runs, with no
					// failure recorded - the user chose this, it is not an error.
					SessionTransition(
						QuickBuildSessionState.Ready(state.deployedGeneration),
						listOf(SessionEffect.CancelLiveReload),
					)
				}
			}

			SessionEvent.WarmCompileFinished -> {
				// The warm compile deployed nothing, so return to the unchanged generation. Its
				// own outcome is not surfaced, but a crash of the running generation lands now.
				SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration, state.pendingCrash))
			}

			is SessionEvent.InvalidationDetected -> {
				SessionTransition(
					QuickBuildSessionState.Invalidated(event.reason, state.deployedGeneration),
					listOf(SessionEffect.RunProxyAppRebuild),
				)
			}

			SessionEvent.DaemonDied -> {
				SessionTransition(
					QuickBuildSessionState.Degraded(state.deployedGeneration),
					listOf(SessionEffect.RespawnDaemon),
				)
			}

			is SessionEvent.ProxyAppCrashed -> {
				if (state.warmingCompiler) {
					// A warm compile ends in Ready with no failure, which would swallow this
					// crash of the running generation - nothing is coming to supersede it.
					// Carry it; WarmCompileFinished surfaces it.
					SessionTransition(state.copy(pendingCrash = SessionFailure.ProxyAppCrash(event.summary)))
				} else {
					// The imminent deploy supersedes the crashed code, so stay Building.
					SessionTransition(state)
				}
			}

			SessionEvent.ExternalBuildCompleted -> {
				// The in-flight build may have read half-rewritten inputs; the baseline
				// refresh coalesces into the follow-up build, which recompiles everything.
				SessionTransition(state, listOf(SessionEffect.RefreshBaseline))
			}

			else -> {
				SessionTransition(state)
			}
		}

	private fun reduceInvalidated(
		state: QuickBuildSessionState.Invalidated,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.ProxyAppRebuildStarted -> {
				// Deliberately not user-initiated even when a tap triggered the retry: a
				// rebuild is a full Gradle build a save can also trigger, so finishing one is
				// not by itself a reason to leave the editor. The auto-retry count is carried
				// so an unconfirmed reinstall parks back with it intact.
				SessionTransition(
					QuickBuildSessionState.Provisioning(installAutoRetries = state.installAutoRetries),
				)
			}

			SessionEvent.QuickBuildTapped -> {
				if (state.awaitingRetry) {
					// An explicit tap is fresh consent, so it re-arms the foreground auto-retry
					// budget. awaitingRetry drops immediately so a second trigger arriving
					// before ProxyAppRebuildStarted cannot double-run the Gradle build.
					SessionTransition(
						state.copy(awaitingRetry = false, installAutoRetries = 0),
						listOf(SessionEffect.RunProxyAppRebuild),
					)
				} else {
					// A proxy app rebuild is already in flight; the trigger has nothing to add.
					SessionTransition(state)
				}
			}

			SessionEvent.HostForegrounded -> {
				if (state.awaitingRetry && state.installAutoRetries < MAX_INSTALL_AUTO_RETRIES) {
					// The user's return is the first chance to re-prompt an install dialog that
					// was never launched (see HostForegrounded). awaitingRetry drops immediately
					// so a second trigger arriving before ProxyAppRebuildStarted cannot
					// double-run the Gradle build.
					SessionTransition(
						state.copy(awaitingRetry = false, installAutoRetries = state.installAutoRetries + 1),
						listOf(SessionEffect.RunProxyAppRebuild),
					)
				} else {
					// Proxy app rebuild in flight, or the auto-retry budget is spent: stay parked.
					SessionTransition(state)
				}
			}

			else -> {
				SessionTransition(state)
			}
		}

	private fun reduceDegraded(
		state: QuickBuildSessionState.Degraded,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.DaemonRespawned -> {
				SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration))
			}

			SessionEvent.DaemonDied -> {
				SessionTransition(state)
			}

			is SessionEvent.InvalidationDetected -> {
				// The orchestrator reports an invalidation once, so dropping this would strand
				// the session: a gradle/manifest edit landing while Degraded would never
				// rebuild and no build would run again. The rebuild needs Gradle rather than
				// the daemon, and the shell's daemonEpoch guard keeps it from racing the
				// in-flight respawn.
				SessionTransition(
					QuickBuildSessionState.Invalidated(event.reason, state.deployedGeneration),
					listOf(SessionEffect.RunProxyAppRebuild),
				)
			}

			SessionEvent.ExternalBuildCompleted -> {
				SessionTransition(state, listOf(SessionEffect.RefreshBaseline))
			}

			else -> {
				SessionTransition(state)
			}
		}

	companion object {
		/**
		 * How many times [SessionEvent.HostForegrounded] may auto-retry an unconfirmed reinstall
		 * before the session stays parked.
		 *
		 * Each retry costs a full Gradle build plus an install prompt, so two declined prompts is
		 * taken as "not now"; after that only an explicit tap re-prompts and re-arms the budget.
		 */
		const val MAX_INSTALL_AUTO_RETRIES = 2
	}
}
