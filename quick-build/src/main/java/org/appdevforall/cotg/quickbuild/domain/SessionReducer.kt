package org.appdevforall.cotg.quickbuild.domain

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
					// The button only shows the stop affordance once a tap has queued (an
					// unasked-for warm-up stays invisible), so a cancel here means: drop the
					// queued tap AND stop the Gradle proxy app build it is waiting on.
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
					// Behaviour 2: a TAP is what started this, and nothing else ever launches
					// the freshly installed proxy app - so the session going live is where the
					// tap gets its answer. A proxy app rebuild routed through this state carries
					// userInitiated = false and stays in the editor.
					if (state.userInitiated) {
						listOf(SessionEffect.StartWarmCompile, SessionEffect.SwitchToProxyApp)
					} else {
						listOf(SessionEffect.StartWarmCompile)
					},
				)
			}

			SessionEvent.CancelRequested -> {
				// There is no half-provisioned session worth keeping: stop the Gradle proxy app
				// build and tear the rest down, which is also what makes a cancel mid-install
				// safe (a late provisioning success is discarded by the epoch guard). The next
				// tap re-provisions - the proxy app build's outputs are still on disk, so it is
				// not the full cold cost again.
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
				// Park back exactly where the retry came from, with the attempt given
				// back: it never ran a Gradle build and never prompted an install, which
				// is what the budget bounds. Floored at zero - a TAP-initiated retry
				// arrives here having already reset the budget.
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
				// The proxy app rebuild built fine; only the install confirmation is missing.
				// Park recoverable (no effect - a retry loop would re-prompt forever):
				// the next tap or foreground return retries, "Restart session" still
				// tears down. The auto-retry count survives the round trip so the
				// HostForegrounded budget is spent per unconfirmed install, not per park.
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

	/** Shared by [QuickBuildSessionState.Ready] and [QuickBuildSessionState.Deployed]. */
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
					// orchestrator queues it (pendingForced) and builds right after the
					// warm compile - single-flight preserved, tap never dropped.
					SessionTransition(state, listOf(SessionEffect.TriggerLiveReload(userInitiated = true)))
				} else {
					// The in-flight real build deploys anyway and satisfies the tap's build.
					// It does NOT satisfy the ask, though: the tap used to be dropped here
					// outright, so tapping during a save-triggered build did nothing the user
					// could see. Record the ask on that build instead (behaviour 2).
					SessionTransition(state, listOf(SessionEffect.MarkBuildUserInitiated))
				}
			}

			SessionEvent.CancelRequested -> {
				if (state.warmingCompiler) {
					// The background warm compile is not the user's build: they never asked for it, it
					// deploys nothing, and the button shows the bolt throughout - so there is
					// nothing here to cancel.
					SessionTransition(state)
				} else {
					// Behaviour 5: back to the bolt at the generation the proxy app still runs,
					// with no failure recorded - the user chose this, it is not an error.
					SessionTransition(
						QuickBuildSessionState.Ready(state.deployedGeneration),
						listOf(SessionEffect.CancelLiveReload),
					)
				}
			}

			SessionEvent.WarmCompileFinished -> {
				// The warm compile deployed nothing: back to Ready at the unchanged generation.
				// No warm-compile OUTCOME is surfaced (see the event's contract), but a crash of
				// the running generation observed mid-warm-compile lands now.
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
					// crash of the CURRENTLY RUNNING generation (nothing new is coming
					// to supersede it). Carry it; WarmCompileFinished surfaces it.
					SessionTransition(state.copy(pendingCrash = SessionFailure.ProxyAppCrash(event.summary)))
				} else {
					// The old generation crashed while the next build runs; stay
					// Building - the imminent deploy supersedes the crashed code.
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
				// Deliberately NOT user-initiated even when a tap triggered the retry: a
				// a proxy app rebuild is a full Gradle build (~a minute), and a save can trigger one
				// too, so completing it is not by itself a reason to leave the editor.
				// The auto-retry count rides along so an unconfirmed reinstall parks back
				// with it intact.
				SessionTransition(
					QuickBuildSessionState.Provisioning(installAutoRetries = state.installAutoRetries),
				)
			}

			SessionEvent.QuickBuildTapped -> {
				if (state.awaitingRetry) {
					// Retry the parked proxy app rebuild (its reinstall was never confirmed).
					// An explicit tap is fresh consent: it also re-arms the foreground
					// auto-retry budget. awaitingRetry drops immediately so a second
					// trigger before ProxyAppRebuildStarted lands cannot double-run the
					// Gradle build.
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
					// The reinstall ran while CoGo was backgrounded: Android DEFERS the
					// PENDING_USER_ACTION broadcast until the app is foregrounded, and the
					// dialog-owning subscriber is lifecycle-bound (registered onStart), so
					// no confirm dialog was ever launched - the user's return is the first
					// chance to re-prompt. Bounded: past MAX_INSTALL_AUTO_RETRIES the
					// session stays parked (a tap still retries) instead of paying a fresh
					// Gradle build on every resume of a user who keeps declining.
					// awaitingRetry drops immediately so a second trigger before
					// ProxyAppRebuildStarted lands cannot double-run the Gradle build.
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
				// Dropping this would strand the session: the orchestrator reports an
				// invalidation ONCE, so a gradle/manifest edit landing while Degraded
				// would otherwise never rebuild its proxy app and no build would ever run again.
				// The proxy app rebuild needs Gradle, not the daemon - and the shell's
				// daemonEpoch guard discards the in-flight respawn the proxy app rebuild's
				// daemon teardown supersedes, so the two cannot race (2026-07-26
				// review finding 2).
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
		 * How many times [SessionEvent.HostForegrounded] may auto-retry an unconfirmed
		 * proxy app rebuild reinstall before the session just stays parked. Each retry costs a
		 * full Gradle build plus an install prompt; two declined prompts is a clear
		 * "not now" - after that only an explicit Quick Build tap re-prompts (and
		 * re-arms this budget).
		 */
		const val MAX_INSTALL_AUTO_RETRIES = 2
	}
}
