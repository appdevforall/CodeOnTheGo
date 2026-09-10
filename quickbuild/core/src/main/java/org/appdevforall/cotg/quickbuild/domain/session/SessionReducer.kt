package org.appdevforall.cotg.quickbuild.domain.session

import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason

/**
 * Pure transition function for the session state machine.
 *
 * The reducer is total: every (state, event) pair is listed, and the ones a state has no use
 * for keep the current state and produce no effects, so a late or duplicate event can never
 * corrupt the session. Each per-state `when` is exhaustive over [SessionEvent] on purpose: an
 * event that is added without saying what every state does with it fails to compile, rather
 * than disappearing into a catch-all.
 */
class SessionReducer {
	/**
	 * Maps a state and an incoming event to the next state plus the effects to run.
	 *
	 * @param state the session's current state.
	 * @param event what happened; a state that does not handle it keeps [state] unchanged rather
	 *   than failing.
	 * @param askOutstanding whether the session's [PendingAsk] holds a tap still waiting to see
	 *   the app - the one input the reducer needs from outside the state, read where a landing
	 *   decides whether to emit [SessionEffect.SwitchToProxyApp].
	 * @return the state to adopt and the effects the shell must then run, in order.
	 */
	fun reduce(
		state: QuickBuildSessionState,
		event: SessionEvent,
		askOutstanding: Boolean = false,
	): SessionTransition =
		when (state) {
			is QuickBuildSessionState.Idle -> reduceIdle(state, event)
			is QuickBuildSessionState.Prebuilding -> reducePrebuilding(state, event)
			is QuickBuildSessionState.Provisioning -> reduceProvisioning(state, event, askOutstanding)
			is QuickBuildSessionState.Ready -> reduceLive(state, state.generation, event)
			is QuickBuildSessionState.Building -> reduceBuilding(state, event, askOutstanding)
			is QuickBuildSessionState.Deployed -> reduceLive(state, state.generation, event)
			is QuickBuildSessionState.Invalidated -> reduceInvalidated(state, event, askOutstanding)
			is QuickBuildSessionState.Degraded -> reduceDegraded(state, event, askOutstanding)
		}

	/**
	 * [SessionEvent.SessionRestartRequested] from any state with something to tear down.
	 *
	 * Restart always wins and always tears down, whatever state it came from. Idle has nothing
	 * to tear down and answers the event itself, clearing a stale failed-start tone.
	 */
	private fun tearDown(): SessionTransition =
		SessionTransition(QuickBuildSessionState.Idle(), listOf(SessionEffect.WithdrawAsk, SessionEffect.TeardownSession))

	/**
	 * [SessionEvent.SessionRestartAndReprovisionRequested] from any state.
	 *
	 * The user-facing restart also wins from any state. Unlike the teardown-only event it never
	 * rests at Idle: it goes straight on to a fresh provision, so the toolbar icon turns BUILDING
	 * and the surfaces narrate the rebuild the user asked for. Idle has nothing to tear down, so
	 * it starts one without the teardown effect.
	 */
	private fun reprovision(
		state: QuickBuildSessionState,
		event: SessionEvent.SessionRestartAndReprovisionRequested,
	): SessionTransition {
		val effect =
			if (state is QuickBuildSessionState.Idle) {
				// Nothing to tear down, so this is an ordinary first provision.
				SessionEffect.StartProvisioning
			} else {
				SessionEffect.TeardownAndProvision
			}
		// Only a restart the USER asked for records an ask, so the fresh session brings the
		// proxy app forward when it goes live; an automatic reprovision (a Build Variants
		// switch) must leave them in the editor. Neither withdraws: a tap the torn-down
		// state was holding is still owed, and the fresh session answers it.
		return SessionTransition(
			QuickBuildSessionState.Provisioning(),
			if (event.userInitiated) listOf(SessionEffect.RecordAsk, effect) else listOf(effect),
		)
	}

	private fun reduceIdle(
		state: QuickBuildSessionState.Idle,
		event: SessionEvent,
	): SessionTransition =
		when (event) {
			is SessionEvent.QuickBuildTapped -> {
				SessionTransition(
					QuickBuildSessionState.Provisioning(),
					listOf(SessionEffect.RecordAsk, SessionEffect.StartProvisioning),
				)
			}

			SessionEvent.CancelRequested -> {
				// Nothing to stop, but a stop still withdraws whatever ask is outstanding, so
				// every state answers it the same way.
				SessionTransition(state, listOf(SessionEffect.WithdrawAsk))
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

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.PrebuildFinished,
			is SessionEvent.ProvisioningSucceeded,
			is SessionEvent.ProvisioningFailed,
			SessionEvent.BuildStarted,
			SessionEvent.WarmCompileStarted,
			is SessionEvent.BuildSucceeded,
			is SessionEvent.BuildFailed,
			SessionEvent.WarmCompileFinished,
			is SessionEvent.InvalidationDetected,
			SessionEvent.ProxyAppRebuildStarted,
			is SessionEvent.ProxyAppRebuildInstallNotConfirmed,
			is SessionEvent.ProxyAppRebuildDeferred,
			is SessionEvent.ProxyAppRebuildFailed,
			SessionEvent.HostForegrounded,
			SessionEvent.ExternalBuildCompleted,
			SessionEvent.DaemonDied,
			SessionEvent.DaemonRespawned,
			SessionEvent.DaemonRestartFailed,
			is SessionEvent.ProxyAppCrashed,
			-> {
				// No session, no build and no daemon: these are late reports from a session
				// that is gone.
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
				SessionTransition(state.copy(tapQueued = true, lastStartFailed = false), listOf(SessionEffect.RecordAsk))
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
					// The ask the tap recorded is still outstanding; the provision answers it.
					SessionTransition(
						QuickBuildSessionState.Provisioning(),
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
					SessionTransition(
						QuickBuildSessionState.Idle(),
						listOf(SessionEffect.WithdrawAsk, SessionEffect.CancelProxyAppBuild),
					)
				} else {
					SessionTransition(state, listOf(SessionEffect.WithdrawAsk))
				}
			}

			SessionEvent.SessionRestartRequested -> {
				tearDown()
			}

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.PrebuildRequested,
			is SessionEvent.ProvisioningSucceeded,
			is SessionEvent.ProvisioningFailed,
			SessionEvent.BuildStarted,
			SessionEvent.WarmCompileStarted,
			is SessionEvent.BuildSucceeded,
			is SessionEvent.BuildFailed,
			SessionEvent.WarmCompileFinished,
			is SessionEvent.InvalidationDetected,
			SessionEvent.ProxyAppRebuildStarted,
			is SessionEvent.ProxyAppRebuildInstallNotConfirmed,
			is SessionEvent.ProxyAppRebuildDeferred,
			is SessionEvent.ProxyAppRebuildFailed,
			SessionEvent.HostForegrounded,
			SessionEvent.ExternalBuildCompleted,
			SessionEvent.DaemonDied,
			SessionEvent.DaemonRespawned,
			SessionEvent.DaemonRestartFailed,
			is SessionEvent.ProxyAppCrashed,
			-> {
				// The warm build installs nothing and starts no daemon, so there is no session
				// for a build, rebuild, daemon or crash report to be about; a second
				// PrebuildRequested has nothing to add to the build already running.
				SessionTransition(state)
			}
		}

	private fun reduceProvisioning(
		state: QuickBuildSessionState.Provisioning,
		event: SessionEvent,
		askOutstanding: Boolean,
	): SessionTransition =
		when (event) {
			is SessionEvent.ProvisioningSucceeded -> {
				SessionTransition(
					QuickBuildSessionState.Ready(event.generation),
					// Behaviour 2: nothing else launches the freshly installed proxy app, so a
					// tap gets its answer here. A save-triggered rebuild stays in the editor,
					// and one whose own relaunch already answered the tap arrives with the ask
					// settled.
					if (askOutstanding) {
						listOf(SessionEffect.StartWarmCompile, SessionEffect.SwitchToProxyApp)
					} else {
						listOf(SessionEffect.StartWarmCompile)
					},
				)
			}

			is SessionEvent.QuickBuildTapped -> {
				// The tap asks to see the app once the user's changes are in it. The build
				// already in flight covers the building, so all the tap adds is the ask,
				// which is what makes ProvisioningSucceeded above switch to the proxy app.
				// Without this a save-triggered rebaseline would never answer the tap.
				SessionTransition(state, listOf(SessionEffect.RecordAsk))
			}

			SessionEvent.CancelRequested -> {
				if (state.rebaselineReason != null) {
					// A rebaseline runs over a live session that is worth keeping: the proxy
					// app still runs and the watcher, daemon and scratch tree are all good.
					// Only the Gradle build is stopped; its cancelled outcome comes back as
					// ProxyAppRebuildFailed and parks at Invalidated for retry, exactly where
					// a build failure or a lost slot parks. Tearing down here made a
					// deliberate stop cost the ~97 s cold provision a failure does not.
					//
					// The stop withdraws the ask first: a cancel that loses the race to the
					// build's own completion lets the rebaseline run on to
					// ProvisioningSucceeded, and an ask left standing there would bring
					// forward the app the user just asked to stop.
					SessionTransition(state, listOf(SessionEffect.WithdrawAsk, SessionEffect.CancelProxyAppRebuild))
				} else {
					// No half-provisioned session is worth keeping. A cancel mid-install is
					// safe because the epoch guard discards a late provisioning success, and
					// the next tap re-provisions from build outputs still on disk. The user
					// chose this, so the Idle it lands in carries no failure.
					SessionTransition(
						QuickBuildSessionState.Idle(),
						listOf(SessionEffect.WithdrawAsk, SessionEffect.CancelProxyAppBuild, SessionEffect.TeardownSession),
					)
				}
			}

			is SessionEvent.ProvisioningFailed -> {
				// lastStartFailed keeps the error tone on the bolt after the failure flash
				// fades - a plain Idle here read READY right after a failed start (Q8).
				SessionTransition(
					QuickBuildSessionState.Idle(lastStartFailed = true),
					listOf(SessionEffect.WithdrawAsk, SessionEffect.SurfaceProvisioningError(event.message)),
				)
			}

			is SessionEvent.ProxyAppRebuildFailed -> {
				// The user's build files do not build. The session itself is fine and the proxy app
				// is still running, so park recoverable rather than die: the next save, a tap, or a
				// return to CoGo retries. No effect on purpose: SurfaceProvisioningError tears the
				// session down, which is the very thing being fixed here; the shell surfaces the
				// reason before dispatching.
				SessionTransition(
					QuickBuildSessionState.Invalidated(
						// From the event: the invalidation the failed build was answering.
						reason = event.reason,
						// From the event: the failed build deployed nothing.
						deployedGeneration = event.deployedGeneration,
						// Reset: nothing is in flight now, so the next save, tap or return retries.
						awaitingRetry = true,
						// Carried: an unfixed build file must not buy a fresh budget of Gradle
						// builds on every return to CoGo.
						installAutoRetries = state.installAutoRetries,
					),
					// The ask is dropped on purpose: a park needs the user to act, and a tap
					// left standing would let the foreground auto-retry bring the app forward
					// unasked.
					listOf(SessionEffect.WithdrawAsk),
				)
			}

			is SessionEvent.ProxyAppRebuildDeferred -> {
				// Park back where the retry came from: it ran no Gradle build and prompted no
				// install, which is what the budget bounds.
				SessionTransition(
					QuickBuildSessionState.Invalidated(
						// Fixed: a deferred attempt only ever retries an unconfirmed install.
						reason = InvalidationReason.INSTALL_NOT_CONFIRMED,
						// From the event: nothing ran, so the proxy app still runs what it did.
						deployedGeneration = event.deployedGeneration,
						// Reset: nothing is in flight now, so the next save, tap or return retries.
						awaitingRetry = true,
						// Refunded: the attempt cost nothing the budget bounds. Floored at zero,
						// since a tap-initiated retry arrives having already reset it.
						installAutoRetries = (state.installAutoRetries - 1).coerceAtLeast(0),
					),
					// The ask is dropped on purpose, as on every park.
					listOf(SessionEffect.WithdrawAsk),
				)
			}

			is SessionEvent.ProxyAppRebuildInstallNotConfirmed -> {
				// Only the install confirmation is missing, so park with no effect - retrying
				// here would re-prompt forever. The next tap or foreground return retries.
				SessionTransition(
					QuickBuildSessionState.Invalidated(
						// Fixed: what the park is waiting on.
						reason = InvalidationReason.INSTALL_NOT_CONFIRMED,
						// From the event: the rebuilt app was never installed, so the old one runs on.
						deployedGeneration = event.deployedGeneration,
						// Reset: nothing is in flight now, so the next save, tap or return retries.
						awaitingRetry = true,
						// Carried: the budget is spent per unconfirmed install, not per park.
						installAutoRetries = state.installAutoRetries,
					),
					// The ask is dropped on purpose, as on every park.
					listOf(SessionEffect.WithdrawAsk),
				)
			}

			is SessionEvent.InvalidationDetected -> {
				// The Gradle build in flight already reads current disk, so the invalidation
				// changes nothing here; a tap its batch consumed is already the outstanding
				// ask and is answered when this build lands.
				SessionTransition(state)
			}

			SessionEvent.SessionRestartRequested -> {
				tearDown()
			}

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.FileSaved,
			SessionEvent.PrebuildRequested,
			SessionEvent.PrebuildFinished,
			SessionEvent.BuildStarted,
			SessionEvent.WarmCompileStarted,
			is SessionEvent.BuildSucceeded,
			is SessionEvent.BuildFailed,
			SessionEvent.WarmCompileFinished,
			SessionEvent.ProxyAppRebuildStarted,
			SessionEvent.HostForegrounded,
			SessionEvent.ExternalBuildCompleted,
			SessionEvent.DaemonDied,
			SessionEvent.DaemonRespawned,
			SessionEvent.DaemonRestartFailed,
			is SessionEvent.ProxyAppCrashed,
			-> {
				// Quick builds are suspended for the whole Gradle build and the daemon is
				// deliberately down, so build, daemon and crash reports here are echoes of
				// the session being replaced; the warm-up and park-retry events belong to
				// phases this is not, and a save is only a gesture to a failed-start Idle.
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
						SessionEffect.RecordAsk,
						SessionEffect.TriggerLiveReload(
							userInitiated = true,
							expectChanges = event.wroteSomething,
						),
					),
				)
			}

			SessionEvent.CancelRequested -> {
				// No build is owned here, so a stop has nothing to cancel (a save's build only
				// becomes cancellable once BuildStarted has moved the session to Building);
				// it still withdraws an outstanding ask, as every state's stop does.
				SessionTransition(state, listOf(SessionEffect.WithdrawAsk))
			}

			SessionEvent.BuildStarted -> {
				SessionTransition(QuickBuildSessionState.Building(generation))
			}

			SessionEvent.WarmCompileStarted -> {
				SessionTransition(QuickBuildSessionState.Building(generation, warmingCompiler = true))
			}

			is SessionEvent.InvalidationDetected -> {
				// A tap the invalidating batch consumed stays the outstanding ask, and the
				// rebuild's relaunch answers it.
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

			SessionEvent.SessionRestartRequested -> {
				tearDown()
			}

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.FileSaved,
			SessionEvent.PrebuildRequested,
			SessionEvent.PrebuildFinished,
			is SessionEvent.ProvisioningSucceeded,
			is SessionEvent.ProvisioningFailed,
			is SessionEvent.BuildSucceeded,
			is SessionEvent.BuildFailed,
			SessionEvent.WarmCompileFinished,
			SessionEvent.ProxyAppRebuildStarted,
			is SessionEvent.ProxyAppRebuildInstallNotConfirmed,
			is SessionEvent.ProxyAppRebuildDeferred,
			is SessionEvent.ProxyAppRebuildFailed,
			SessionEvent.HostForegrounded,
			SessionEvent.DaemonRespawned,
			SessionEvent.DaemonRestartFailed,
			-> {
				// No build is owned here, so a build outcome is the late report of one already
				// closed. The prebuild, provisioning and rebuild events belong to phases with
				// no live session, the daemon is up so its respawn reports are stale, and
				// HostForegrounded is only a parked Invalidated's retry trigger.
				SessionTransition(state)
			}
		}

	private fun reduceBuilding(
		state: QuickBuildSessionState.Building,
		event: SessionEvent,
		askOutstanding: Boolean,
	): SessionTransition =
		when (event) {
			is SessionEvent.BuildSucceeded -> {
				SessionTransition(
					QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted, event.diagnostics),
					// Behaviour 2 vs 3: the deploy landing is where a TAP gets its answer, and
					// where a save deliberately gets none - the user is still editing.
					if (askOutstanding) listOf(SessionEffect.SwitchToProxyApp) else emptyList(),
				)
			}

			is SessionEvent.BuildFailed -> {
				// The tap was answered, with the failure: the save that fixes the code is not
				// a new ask, so it must not drag the user out of the editor.
				SessionTransition(
					QuickBuildSessionState.Ready(state.deployedGeneration, event.failure),
					listOf(SessionEffect.WithdrawAsk),
				)
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
							SessionEffect.RecordAsk,
							SessionEffect.TriggerLiveReload(
								userInitiated = true,
								expectChanges = event.wroteSomething,
							),
						),
					)
				} else {
					// The in-flight build satisfies the tap's build but not the ask, so record
					// the ask and promote that build to answer it (behaviour 2) rather than
					// dropping it.
					SessionTransition(state, listOf(SessionEffect.RecordAsk, SessionEffect.MarkBuildUserInitiated))
				}
			}

			SessionEvent.CancelRequested -> {
				if (state.warmingCompiler) {
					// The warm compile is not the user's build: unasked for, deploys nothing,
					// and the button shows the bolt throughout. Nothing here to cancel.
					SessionTransition(state, listOf(SessionEffect.WithdrawAsk))
				} else {
					// Behaviour 5: back to the generation the proxy app still runs, with no
					// failure recorded - the user chose this, it is not an error.
					SessionTransition(
						QuickBuildSessionState.Ready(state.deployedGeneration),
						listOf(SessionEffect.WithdrawAsk, SessionEffect.CancelLiveReload),
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

			SessionEvent.SessionRestartRequested -> {
				tearDown()
			}

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.FileSaved,
			SessionEvent.PrebuildRequested,
			SessionEvent.PrebuildFinished,
			is SessionEvent.ProvisioningSucceeded,
			is SessionEvent.ProvisioningFailed,
			SessionEvent.BuildStarted,
			SessionEvent.WarmCompileStarted,
			SessionEvent.ProxyAppRebuildStarted,
			is SessionEvent.ProxyAppRebuildInstallNotConfirmed,
			is SessionEvent.ProxyAppRebuildDeferred,
			is SessionEvent.ProxyAppRebuildFailed,
			SessionEvent.HostForegrounded,
			SessionEvent.DaemonRespawned,
			SessionEvent.DaemonRestartFailed,
			-> {
				// The orchestrator runs one build at a time, so a second start here is a
				// duplicate report; the prebuild, provisioning and rebuild events belong to
				// phases with no live session; the daemon is up, so its respawn reports are
				// stale; and HostForegrounded is only a parked Invalidated's retry trigger.
				SessionTransition(state)
			}
		}

	private fun reduceInvalidated(
		state: QuickBuildSessionState.Invalidated,
		event: SessionEvent,
		askOutstanding: Boolean,
	): SessionTransition =
		when (event) {
			SessionEvent.ProxyAppRebuildStarted -> {
				// A rebuild is a full Gradle build a save can also trigger, so finishing one is
				// not by itself a reason to leave the editor: only an outstanding ask - a tap
				// the invalidating batch consumed, or one that triggered the retry - makes the
				// rebuild's relaunch bring the app forward. The auto-retry count is carried so
				// an unconfirmed reinstall parks back with it intact, and the reason so the
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
					// install only CoGo can confirm, so the rebuild's relaunch answers it and a
					// park withdraws it. Answering it now would put the user in the app they
					// already had for the whole build.
					SessionTransition(
						state.copy(awaitingRetry = false, installAutoRetries = 0),
						listOf(SessionEffect.RecordAsk, SessionEffect.RunProxyAppRebuild),
					)
				} else {
					// A proxy app rebuild is already in flight; the tap adds only the ask.
					SessionTransition(state, listOf(SessionEffect.RecordAsk))
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
						state.copy(reason = event.reason, awaitingRetry = false, installAutoRetries = 0),
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
						QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted, event.diagnostics),
						if (askOutstanding) listOf(SessionEffect.SwitchToProxyApp) else emptyList(),
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
					// still stale. The failure answers the tap, as in Building.
					SessionTransition(
						QuickBuildSessionState.Ready(state.deployedGeneration, event.failure),
						listOf(SessionEffect.WithdrawAsk),
					)
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

			SessionEvent.CancelRequested -> {
				// Nothing here can be stopped: parked, nothing runs, and a rebuild about to
				// start has no Gradle build yet (that only exists once ProxyAppRebuildStarted
				// has moved the session on). The stop still withdraws the ask, so a rebuild
				// it could not stop lands in the background instead of pulling the user
				// into the app they just asked to stop.
				SessionTransition(state, listOf(SessionEffect.WithdrawAsk))
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

			SessionEvent.SessionRestartRequested -> {
				tearDown()
			}

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.FileSaved,
			SessionEvent.PrebuildRequested,
			SessionEvent.PrebuildFinished,
			is SessionEvent.ProvisioningSucceeded,
			is SessionEvent.ProvisioningFailed,
			is SessionEvent.ProxyAppRebuildInstallNotConfirmed,
			is SessionEvent.ProxyAppRebuildDeferred,
			is SessionEvent.ProxyAppRebuildFailed,
			SessionEvent.ExternalBuildCompleted,
			SessionEvent.DaemonRestartFailed,
			-> {
				// The prebuild and provisioning events belong to phases with no live session;
				// the ProxyAppRebuild* outcomes are dispatched from Provisioning, after the
				// ProxyAppRebuildStarted hop moved the session there; an external full build
				// has nothing to add to a state already waiting on one; and only Degraded
				// acts on a failed daemon restart.
				SessionTransition(state)
			}
		}

	private fun reduceDegraded(
		state: QuickBuildSessionState.Degraded,
		event: SessionEvent,
		askOutstanding: Boolean,
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
				// Output line, since that pane is driven by status transitions. The message goes out
				// in both arms so the tap is never silent, and the ask is recorded in both so the
				// build that follows the respawn answers it.
				if (state.restartFailed) {
					// Nothing is scheduled any more, so the tap is the retry; clearing
					// restartFailed puts the status back to "restarting".
					SessionTransition(
						state.copy(restartFailed = false),
						listOf(
							SessionEffect.RecordAsk,
							SessionEffect.SurfaceMessage(QuickBuildMessage.DaemonRestartRetrying),
							SessionEffect.RespawnDaemon,
						),
					)
				} else {
					// The DaemonDied respawn is still in flight, and a respawn never bumps the
					// daemon epoch - so a second RespawnDaemon here would RACE the first for
					// the same daemon rather than be answered with Superseded. Ack only.
					SessionTransition(
						state,
						listOf(SessionEffect.RecordAsk, SessionEffect.SurfaceMessage(QuickBuildMessage.DaemonRestartRetrying)),
					)
				}
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
					QuickBuildSessionState.Deployed(event.generation, event.durationMillis, event.restarted, event.diagnostics),
					if (askOutstanding) listOf(SessionEffect.SwitchToProxyApp) else emptyList(),
				)
			}

			is SessionEvent.BuildFailed -> {
				// Same reachability as BuildSucceeded above. A build that reported diagnostics reached
				// a working compiler, so Ready is honest and the diagnostics are what the user needs;
				// a daemon death arrives as DaemonDied instead, never here.
				SessionTransition(
					QuickBuildSessionState.Ready(state.deployedGeneration, event.failure),
					listOf(SessionEffect.WithdrawAsk),
				)
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

			SessionEvent.CancelRequested -> {
				// Nothing to stop, since a save's build only becomes cancellable once
				// BuildStarted has moved the session to Building; the stop still withdraws an
				// outstanding ask, as every state's stop does.
				SessionTransition(state, listOf(SessionEffect.WithdrawAsk))
			}

			SessionEvent.SessionRestartRequested -> {
				tearDown()
			}

			is SessionEvent.SessionRestartAndReprovisionRequested -> {
				reprovision(state, event)
			}

			SessionEvent.FileSaved,
			SessionEvent.PrebuildRequested,
			SessionEvent.PrebuildFinished,
			is SessionEvent.ProvisioningSucceeded,
			is SessionEvent.ProvisioningFailed,
			SessionEvent.ProxyAppRebuildStarted,
			is SessionEvent.ProxyAppRebuildInstallNotConfirmed,
			is SessionEvent.ProxyAppRebuildDeferred,
			is SessionEvent.ProxyAppRebuildFailed,
			SessionEvent.HostForegrounded,
			-> {
				// The prebuild and provisioning events belong to phases with no live session;
				// the ProxyAppRebuild* events are dispatched from Provisioning; and
				// HostForegrounded is only a parked Invalidated's retry trigger.
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
