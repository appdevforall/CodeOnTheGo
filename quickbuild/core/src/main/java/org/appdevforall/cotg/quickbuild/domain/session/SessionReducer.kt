package org.appdevforall.cotg.quickbuild.domain.session

import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason

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
		// to tear down and falls through to reduceIdle, which still clears a stale
		// failed-start tone.
		if (event == SessionEvent.SessionRestartRequested && state !is QuickBuildSessionState.Idle) {
			return SessionTransition(QuickBuildSessionState.Idle(), listOf(SessionEffect.TeardownSession))
		}
		// The user-facing restart, which also wins from any state. Unlike the teardown-only event
		// above it never rests at Idle: it goes straight on to a fresh provision, so the toolbar
		// icon turns BUILDING and the surfaces narrate the rebuild the user asked for. Idle has
		// nothing to tear down, so it starts one without the teardown effect.
		if (event == SessionEvent.SessionRestartAndReprovisionRequested) {
			val effect =
				if (state is QuickBuildSessionState.Idle) {
					// Nothing to tear down, so this is an ordinary first provision.
					SessionEffect.StartProvisioning
				} else {
					SessionEffect.TeardownAndProvision
				}
			return SessionTransition(
				QuickBuildSessionState.Provisioning(userInitiated = true),
				listOf(effect),
			)
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
		state: QuickBuildSessionState.Idle,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			is SessionEvent.QuickBuildTapped -> {
				SessionTransition(
					QuickBuildSessionState.Provisioning(userInitiated = true),
					listOf(SessionEffect.StartProvisioning),
				)
			}

			SessionEvent.PrebuildRequested -> {
				// The flag rides along so the silent warm build cannot clear a failed-start
				// tone: only a tap or a save is a user gesture.
				SessionTransition(
					QuickBuildSessionState.Prebuilding(lastStartFailed = state.lastStartFailed),
					listOf(SessionEffect.StartProxyAppPrebuild),
				)
			}

			SessionEvent.FileSaved -> {
				// The save is the clearing gesture, not a retry: no effect on purpose, so a
				// save can never start a provision the user did not ask for.
				if (state.lastStartFailed) {
					SessionTransition(QuickBuildSessionState.Idle())
				} else {
					SessionTransition(state)
				}
			}

			SessionEvent.SessionRestartRequested -> {
				// Nothing to tear down, but an explicit teardown (project close, a Standard Run
				// taking over the app id) ends the failed-start story too - the tone must not
				// survive into whatever comes next.
				if (state.lastStartFailed) {
					SessionTransition(QuickBuildSessionState.Idle())
				} else {
					SessionTransition(state)
				}
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
			// the tooling server); it queues and fires on PrebuildFinished. The tap is also
			// the retry gesture, so it clears a carried failed-start tone.
			is SessionEvent.QuickBuildTapped -> {
				SessionTransition(state.copy(tapQueued = true, lastStartFailed = false))
			}

			SessionEvent.FileSaved -> {
				// Same clearing gesture as in Idle; the warm build itself is not one.
				if (state.lastStartFailed) {
					SessionTransition(state.copy(lastStartFailed = false))
				} else {
					SessionTransition(state)
				}
			}

			SessionEvent.PrebuildFinished -> {
				if (state.tapQueued) {
					SessionTransition(
						QuickBuildSessionState.Provisioning(userInitiated = true),
						listOf(SessionEffect.StartProvisioning),
					)
				} else {
					// A carried failed-start tone goes back to Idle uncleared: the warm build's
					// outcome is silent either way, and only a tap or a save clears the tone.
					SessionTransition(QuickBuildSessionState.Idle(lastStartFailed = state.lastStartFailed))
				}
			}

			SessionEvent.CancelRequested -> {
				if (state.tapQueued) {
					// The button only shows the stop affordance once a tap has queued, so a
					// cancel here means drop the queued tap AND stop the Gradle build it waits on.
					SessionTransition(QuickBuildSessionState.Idle(), listOf(SessionEffect.CancelProxyAppBuild))
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
				// tap re-provisions from build outputs still on disk. The user chose this, so
				// the Idle it lands in carries no failure.
				SessionTransition(
					QuickBuildSessionState.Idle(),
					listOf(SessionEffect.CancelProxyAppBuild, SessionEffect.TeardownSession),
				)
			}

			is SessionEvent.ProvisioningFailed -> {
				// lastStartFailed keeps the error tone on the bolt after the failure flash
				// fades - a plain Idle here read READY right after a failed start (Q8).
				SessionTransition(
					QuickBuildSessionState.Idle(lastStartFailed = true),
					listOf(SessionEffect.SurfaceProvisioningError(event.message)),
				)
			}

			is SessionEvent.ProxyAppRebuildFailed -> {
				// The user's build files do not build. The session itself is fine and the proxy app
				// is still running, so park recoverable rather than die: the next save, a tap, or a
				// return to CoGo retries. The auto-retry count is CARRIED, not reset - an unfixed
				// build file must not buy a fresh budget of Gradle builds on every return.
				// No effect on purpose: SurfaceProvisioningError tears the session down, which is
				// the very thing being fixed here; the shell surfaces the reason before dispatching.
				SessionTransition(
					QuickBuildSessionState.Invalidated(
						event.reason,
						event.deployedGeneration,
						awaitingRetry = true,
						installAutoRetries = state.installAutoRetries,
					),
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
			is SessionEvent.QuickBuildTapped -> {
				SessionTransition(
					state,
					listOf(
						SessionEffect.TriggerLiveReload(
							userInitiated = true,
							expectChanges = event.wroteSomething,
						),
					),
				)
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

			is SessionEvent.QuickBuildTapped -> {
				if (state.warmingCompiler) {
					// A warm compile deploys nothing, so the tap would otherwise vanish. The
					// orchestrator answers it: a tap that wrote something builds off its own
					// watcher batch right after the warm compile, and a clean tap switches
					// without queueing a forced build.
					SessionTransition(
						state,
						listOf(
							SessionEffect.TriggerLiveReload(
								userInitiated = true,
								expectChanges = event.wroteSomething,
							),
						),
					)
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
				// so an unconfirmed reinstall parks back with it intact, and the reason so the
				// status surfaces can call this a rebaseline without having to have seen the
				// Invalidated hop.
				SessionTransition(
					QuickBuildSessionState.Provisioning(
						installAutoRetries = state.installAutoRetries,
						rebaselineReason = state.reason,
					),
				)
			}

			is SessionEvent.QuickBuildTapped -> {
				if (state.awaitingRetry) {
					// An explicit tap is fresh consent, so it re-arms the foreground auto-retry
					// budget. awaitingRetry drops immediately so a second trigger arriving
					// before ProxyAppRebuildStarted cannot double-run the Gradle build.
					//
					// The tap is still a request to see the app, so it is recorded rather than
					// dropped - but a rebaseline holds the screen for a full Gradle build and an
					// install only CoGo can confirm, so the shell holds the switch until the
					// rebuild lands and abandons it if it does not. Answering it now would park
					// the user in the app they already had for the whole build.
					SessionTransition(
						state.copy(awaitingRetry = false, installAutoRetries = 0),
						listOf(SessionEffect.RunProxyAppRebuild, SessionEffect.SwitchToProxyApp),
					)
				} else {
					// A proxy app rebuild is already in flight; the trigger has nothing to add.
					SessionTransition(state)
				}
			}

			is SessionEvent.InvalidationDetected -> {
				if (state.awaitingRetry) {
					// The user saved one of the files that parked us - overwhelmingly the fix for
					// whatever failed. That save is the recovery gesture and has to move the
					// session: a user who never leaves the editor sends neither a tap nor a
					// foreground return, so nothing else would unpark it. The budget resets because
					// a changed file is a genuinely new attempt, not a retry of the failure.
					SessionTransition(
						QuickBuildSessionState.Invalidated(
							event.reason,
							state.deployedGeneration,
							awaitingRetry = false,
							installAutoRetries = 0,
						),
						listOf(SessionEffect.RunProxyAppRebuild),
					)
				} else {
					// A proxy app rebuild is already in flight; it will build from current disk.
					SessionTransition(state)
				}
			}

			SessionEvent.BuildStarted -> {
				if (state.awaitingRetry) {
					// Parked with no rebuild in flight, so the orchestrator is holding nothing
					// back (ProxyAppRebuildFailed cleared its absorption gate) and a save it
					// judges absorbable really does start a quick build. That build has to be
					// visible: without this hop the status stays on "a full build is needed" while
					// builds run, deploy and fail unseen, which reads to the user as "I saved my
					// fix and nothing happened".
					SessionTransition(QuickBuildSessionState.Building(state.deployedGeneration))
				} else {
					// A proxy app rebuild owns the session and is about to supersede this build,
					// so its result is discarded by the orchestrator. Staying put is what keeps
					// the ProxyAppRebuildStarted hop able to land.
					SessionTransition(state)
				}
			}

			is SessionEvent.BuildSucceeded -> {
				if (state.awaitingRetry) {
					// The deploy landed, so the proxy app really does run the new generation;
					// carrying on as Invalidated would keep reporting the old one. Reached
					// without a BuildStarted of its own when the park and the build raced.
					SessionTransition(
						QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted),
						if (event.userInitiated) listOf(SessionEffect.SwitchToProxyApp) else emptyList(),
					)
				} else {
					// The rebuild that superseded this build is what the session waits on.
					// Moving to Deployed here would leave ProxyAppRebuildStarted nowhere to land
					// and narrate a multi-minute Gradle build as "up to date".
					SessionTransition(state)
				}
			}

			is SessionEvent.BuildFailed -> {
				if (state.awaitingRetry) {
					// Same reachability as BuildSucceeded above. The failure has to be visible:
					// a compile error is fixable in seconds, which is what Ready.lastFailure is
					// for, and the next save re-reports the invalidation if the baseline is
					// still stale.
					SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration, event.failure))
				} else {
					SessionTransition(state)
				}
			}

			SessionEvent.DaemonDied -> {
				if (state.awaitingRetry) {
					// Deliberately stays Invalidated - the stale baseline is the more urgent
					// fact and only Gradle clears it - but the compiler still has to come back,
					// or every later save's quick build dies on a dead daemon and the session
					// never moves again.
					SessionTransition(state, listOf(SessionEffect.RespawnDaemon))
				} else {
					// A proxy app rebuild is in flight and restarts the daemon itself (see
					// ProxyAppBuildRunner's DaemonRestartFailed outcome); a respawn issued here
					// would race it for the same daemon.
					SessionTransition(state)
				}
			}

			SessionEvent.DaemonRespawned -> {
				// Deliberately ignored: a working compiler does not make a stale baseline
				// fresh, so the park stands until a full Gradle build clears it.
				SessionTransition(state)
			}

			SessionEvent.WarmCompileStarted,
			SessionEvent.WarmCompileFinished,
			-> {
				// Deliberately ignored: a warm compile deploys nothing and its outcome is never
				// surfaced, so routing it through Building would end in Ready and silently
				// cancel the park - losing both the reason and the retry.
				SessionTransition(state)
			}

			is SessionEvent.ProxyAppCrashed -> {
				// Deliberately ignored, and not silent: the manager flashes
				// QuickBuildNotice.RELOAD_CRASHED on every crash before dispatching this, so
				// the user is told. All the state decides is the STATUS, and "a full build is
				// needed" outranks a crash that already rolled back to the generation the proxy
				// app is still running.
				SessionTransition(state)
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
				// What legitimately reaches here: the prebuild and provisioning events, which
				// belong to phases with no live session; CancelRequested, since the button offers
				// no stop affordance while a full build is what is needed; and the
				// ProxyAppRebuild* outcomes, which are dispatched from Provisioning, after the
				// ProxyAppRebuildStarted hop moved the session there.
				SessionTransition(state)
			}
		}

	private fun reduceDegraded(
		state: QuickBuildSessionState.Degraded,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			SessionEvent.DaemonRespawned -> {
				if (state.restartFailed) {
					// The daemon this announces has already been reported dead - the respawned
					// child died in the window between start() returning Ok and this landing. Going
					// Ready here would claim a live compiler and hide the outage until the next
					// save discovered it; stay degraded and keep telling the truth.
					SessionTransition(state)
				} else {
					SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration))
				}
			}

			SessionEvent.DaemonDied -> {
				// Deliberately schedules no second respawn: the one already attempted either failed
				// or produced a daemon that died immediately, and auto-retrying a hard-broken
				// compiler just spins. What it must do is stop the status claiming a restart is in
				// flight. The two gestures that recover from here are a Quick Build tap (below) and
				// a save, whose build dies on the dead daemon and arrives as DaemonDied from
				// Building, which does respawn.
				SessionTransition(state.copy(restartFailed = true))
			}

			SessionEvent.DaemonRestartFailed -> {
				SessionTransition(state.copy(restartFailed = true))
			}

			is SessionEvent.QuickBuildTapped -> {
				// The one gesture the user has while the compiler is down, so it must not fall through
				// to the else below - that would answer the tap with no build, no message and no Build
				// Output line, since that pane is driven by status transitions. A failed respawn leaves
				// the daemon epoch alone, so the retry really runs; the message goes out alongside it
				// because a respawn still in flight answers with Superseded and would otherwise leave
				// the tap unacknowledged. Clearing restartFailed makes the status honest again.
				SessionTransition(
					state.copy(restartFailed = false),
					listOf(
						SessionEffect.SurfaceMessage(QuickBuildMessage.DaemonRestartRetrying),
						SessionEffect.RespawnDaemon,
					),
				)
			}

			SessionEvent.BuildStarted -> {
				// The watcher never stops, so a save while the compiler is down still starts a quick
				// build, and this hop is what makes it visible - without it the status stays on
				// "restarting the compiler" while save after save comes to nothing. A build that then
				// dies on the dead daemon arrives as DaemonDied from Building, which respawns again,
				// so each save both narrates itself and pushes recovery along.
				SessionTransition(QuickBuildSessionState.Building(state.deployedGeneration))
			}

			is SessionEvent.BuildSucceeded -> {
				// Reachable with no BuildStarted of its own: the daemon death listener can fire
				// mid-build, parking the session here while that build runs on. A deploy that landed
				// moved the proxy app, whatever the daemon did afterwards.
				SessionTransition(
					QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted),
					if (event.userInitiated) listOf(SessionEffect.SwitchToProxyApp) else emptyList(),
				)
			}

			is SessionEvent.BuildFailed -> {
				// Same reachability as BuildSucceeded above. A build that reported diagnostics reached
				// a working compiler, so Ready is honest and the diagnostics are what the user needs;
				// a daemon death arrives as DaemonDied instead, never here.
				SessionTransition(QuickBuildSessionState.Ready(state.deployedGeneration, event.failure))
			}

			SessionEvent.WarmCompileStarted,
			SessionEvent.WarmCompileFinished,
			-> {
				// Deliberately ignored: a warm compile deploys nothing and its outcome is never
				// surfaced, so routing it through Building would swap "restarting the compiler" for
				// "up to date" while the daemon is still being respawned.
				SessionTransition(state)
			}

			is SessionEvent.ProxyAppCrashed -> {
				// Deliberately ignored, and not silent: the manager flashes
				// QuickBuildNotice.RELOAD_CRASHED on every crash before dispatching this. All the
				// state decides is the STATUS, and "restarting the compiler" outranks a crash that
				// already rolled back to the generation the proxy app is still running.
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
				// What legitimately reaches here: the prebuild and provisioning events, which
				// belong to phases with no live session; CancelRequested, since a save's build only
				// becomes cancellable once BuildStarted has moved the session to Building; the
				// ProxyAppRebuild* outcomes, dispatched from Provisioning; and HostForegrounded,
				// which only a parked Invalidated acts on.
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
