package org.appdevforall.cotg.quickbuild.domain.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.junit.jupiter.api.Test

/**
 * Feeds every event into every representative state and checks the rules that must hold for
 * any pair, so an arm nobody wrote a hand-picked test for cannot silently drop a tap, let a
 * stop leave the ask standing, or move a generation backwards.
 *
 * Representative means every Boolean flag on and off, `awaitingRetry` both ways,
 * `rebaselineReason` null and set, and the install auto-retry count at zero and at its bound.
 * Each rule is its own test, so a red run names the rule and lists every pair that breaks it.
 * The first test pins the denominator: the sweep covers every state and event subclass, so
 * a new one cannot be added without being swept.
 */
class SessionReducerInvariantsTest {
	private val reducer = SessionReducer()

	@Test
	fun `every state and event subclass is swept`() {
		// Hand-counted: kotlin-reflect is not on this module's test classpath, so
		// sealedSubclasses cannot enumerate them. The reducer's exhaustive when
		// fails to compile on a new subclass, and this count fails when it is added
		// there but not here.
		assertThat(states.map { it::class }.distinct()).hasSize(8)
		assertThat(events.map { it::class }.distinct()).hasSize(25)
	}

	@Test
	fun `a stop leaves no state carrying the ask and never switches to the proxy app`() {
		val broken =
			violations { _, event, transition ->
				when {
					event != SessionEvent.CancelRequested -> null
					transition.state.carriesAsk -> "the ask survives the stop"
					SessionEffect.SwitchToProxyApp in transition.effects -> "the stop switches to the proxy app"
					else -> null
				}
			}
		assertWithMessage("(1) a stop withdraws the ask").that(broken).isEmpty()
	}

	@Test
	fun `the proxy app is only brought forward when the state or the event carried an ask`() {
		val broken =
			violations { state, event, transition ->
				if (SessionEffect.SwitchToProxyApp in transition.effects && !state.carriesAsk && !event.carriesAsk) {
					"switch without an ask"
				} else {
					null
				}
			}
		assertWithMessage("(2) no switch without an ask").that(broken).isEmpty()
	}

	@Test
	fun `an invalidation that consumed a tap keeps the ask on the state it lands in`() {
		val broken =
			violations { _, event, transition ->
				val landsWhereTheAskLives =
					transition.state is QuickBuildSessionState.Invalidated ||
						transition.state is QuickBuildSessionState.Provisioning
				val tapped = event is SessionEvent.InvalidationDetected && event.userInitiated
				if (tapped && landsWhereTheAskLives && !transition.state.carriesAsk) {
					"the tap on the invalidation is dropped"
				} else {
					null
				}
			}
		assertWithMessage("(3) InvalidationDetected(userInitiated = true) keeps the ask").that(broken).isEmpty()
	}

	@Test
	fun `a restart carries the ask forward - the event's own or the one the state already held`() {
		val broken =
			violations { state, event, transition ->
				if (event !is SessionEvent.SessionRestartAndReprovisionRequested) return@violations null
				val fresh = transition.state as? QuickBuildSessionState.Provisioning ?: return@violations "did not reprovision"
				val expected = event.userInitiated || state.carriesAsk
				if (fresh.userInitiated != expected) "expected userInitiated = $expected" else null
			}
		assertWithMessage("(4) a reprovision preserves an outstanding ask").that(broken).isEmpty()
	}

	@Test
	fun `stopping a rebaseline never tears the live session down`() {
		val broken =
			violations { state, event, transition ->
				val rebaseline = state is QuickBuildSessionState.Provisioning && state.rebaselineReason != null
				if (event == SessionEvent.CancelRequested && rebaseline && transition.state is QuickBuildSessionState.Idle) {
					"a rebaseline stop went Idle"
				} else {
					null
				}
			}
		assertWithMessage("(5) a rebaseline stop keeps the session").that(broken).isEmpty()
	}

	@Test
	fun `the generation the proxy app runs never goes backwards`() {
		val broken =
			violations { state, _, transition ->
				val before = state.runningGeneration
				val after = transition.state.runningGeneration
				if (before != null && after != null && after < before) "generation $before -> $after" else null
			}
		assertWithMessage("(6) generation is monotonic").that(broken).isEmpty()
	}

	@Test
	fun `an invalidation of a live session always starts the proxy app rebuild`() {
		val broken =
			violations { state, event, transition ->
				val live =
					state is QuickBuildSessionState.Ready ||
						state is QuickBuildSessionState.Building ||
						state is QuickBuildSessionState.Deployed ||
						state is QuickBuildSessionState.Degraded
				if (event is SessionEvent.InvalidationDetected && live && SessionEffect.RunProxyAppRebuild !in transition.effects) {
					"no RunProxyAppRebuild"
				} else {
					null
				}
			}
		assertWithMessage("(7) a live invalidation rebuilds").that(broken).isEmpty()
	}

	/**
	 * Reduces every (state, event) pair and collects the ones [rule] rejects.
	 *
	 * @param rule null when the pair is fine, otherwise a short reason; the collected line
	 *   prefixes it with the pair and the transition so a red run is readable as is.
	 * @return one line per broken pair, empty when the rule holds everywhere.
	 */
	private fun violations(rule: (QuickBuildSessionState, SessionEvent, SessionTransition) -> String?): List<String> =
		buildList {
			for (state in states) {
				for (event in events) {
					val transition = reducer.reduce(state, event)
					rule(state, event, transition)?.let { reason ->
						add("$state + $event -> ${transition.state} ${transition.effects}: $reason")
					}
				}
			}
		}

	/** Whether the state remembers a Quick Build tap that still has to bring the proxy app forward. */
	private val QuickBuildSessionState.carriesAsk: Boolean
		get() =
			when (this) {
				is QuickBuildSessionState.Prebuilding -> tapQueued
				is QuickBuildSessionState.Provisioning -> userInitiated
				is QuickBuildSessionState.Invalidated -> userInitiated
				else -> false
			}

	/** Whether the event itself is, or carries, a Quick Build tap. */
	private val SessionEvent.carriesAsk: Boolean
		get() =
			when (this) {
				is SessionEvent.QuickBuildTapped -> true
				is SessionEvent.BuildSucceeded -> userInitiated
				is SessionEvent.InvalidationDetected -> userInitiated
				is SessionEvent.SessionRestartAndReprovisionRequested -> userInitiated
				else -> false
			}

	/** The generation the proxy app runs in this state, null for the states with no live app. */
	private val QuickBuildSessionState.runningGeneration: Long?
		get() =
			when (this) {
				is QuickBuildSessionState.Ready -> generation

				is QuickBuildSessionState.Building -> deployedGeneration

				is QuickBuildSessionState.Deployed -> generation

				is QuickBuildSessionState.Invalidated -> deployedGeneration

				is QuickBuildSessionState.Degraded -> deployedGeneration

				is QuickBuildSessionState.Idle,
				is QuickBuildSessionState.Prebuilding,
				is QuickBuildSessionState.Provisioning,
				-> null
			}

	private companion object {
		const val GENERATION = 3L

		/** Strictly newer than [GENERATION], so an event can never be the reason a generation drops. */
		const val NEWER_GENERATION = 5L
		val REASON = InvalidationReason.GRADLE_CONFIG_CHANGED
		val FAILURE = SessionFailure.DeployError("proxy app not connected")
		val CRASH = SessionFailure.ProxyAppCrash("NPE in onCreate")
		val DIAGNOSTICS = listOf(BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "unused variable"))
		val BOOLEANS = listOf(false, true)
		val RETRY_COUNTS = listOf(0, SessionReducer.MAX_INSTALL_AUTO_RETRIES)

		val states: List<QuickBuildSessionState> =
			buildList {
				for (failed in BOOLEANS) add(QuickBuildSessionState.Idle(lastStartFailed = failed))
				for (tap in BOOLEANS) {
					for (failed in BOOLEANS) add(QuickBuildSessionState.Prebuilding(tapQueued = tap, lastStartFailed = failed))
				}
				for (ask in BOOLEANS) {
					for (retries in RETRY_COUNTS) {
						for (reason in listOf(null, REASON)) {
							add(QuickBuildSessionState.Provisioning(ask, retries, reason))
						}
					}
				}
				for (failure in listOf(null, FAILURE)) add(QuickBuildSessionState.Ready(GENERATION, failure))
				for (warming in BOOLEANS) {
					for (crash in listOf(null, CRASH)) add(QuickBuildSessionState.Building(GENERATION, warming, crash))
				}
				for (restarted in BOOLEANS) {
					for (diagnostics in listOf(emptyList(), DIAGNOSTICS)) {
						add(QuickBuildSessionState.Deployed(GENERATION, 10, restarted, diagnostics))
					}
				}
				for (awaiting in BOOLEANS) {
					for (retries in RETRY_COUNTS) {
						for (ask in BOOLEANS) {
							add(QuickBuildSessionState.Invalidated(REASON, GENERATION, awaiting, retries, ask))
						}
					}
				}
				for (failed in BOOLEANS) add(QuickBuildSessionState.Degraded(GENERATION, failed))
			}

		val events: List<SessionEvent> =
			listOf(
				SessionEvent.QuickBuildTapped(),
				SessionEvent.QuickBuildTapped(wroteSomething = true),
				SessionEvent.CancelRequested,
				SessionEvent.FileSaved,
				SessionEvent.PrebuildRequested,
				SessionEvent.PrebuildFinished,
				SessionEvent.ProvisioningSucceeded(NEWER_GENERATION),
				SessionEvent.ProvisioningSucceeded(NEWER_GENERATION, askAlreadyAnswered = true),
				SessionEvent.ProvisioningFailed(QuickBuildMessage.RebuildFailed),
				SessionEvent.BuildStarted,
				SessionEvent.WarmCompileStarted,
				SessionEvent.BuildSucceeded(NEWER_GENERATION, 10),
				SessionEvent.BuildSucceeded(NEWER_GENERATION, 10, userInitiated = true),
				SessionEvent.BuildFailed(FAILURE),
				SessionEvent.WarmCompileFinished,
				SessionEvent.InvalidationDetected(REASON),
				SessionEvent.InvalidationDetected(REASON, userInitiated = true),
				SessionEvent.ProxyAppRebuildStarted,
				SessionEvent.ProxyAppRebuildInstallNotConfirmed(GENERATION),
				SessionEvent.ProxyAppRebuildDeferred(GENERATION),
				SessionEvent.ProxyAppRebuildFailed(REASON, GENERATION),
				SessionEvent.HostForegrounded,
				SessionEvent.ExternalBuildCompleted,
				SessionEvent.DaemonDied,
				SessionEvent.DaemonRespawned,
				SessionEvent.DaemonRestartFailed,
				SessionEvent.ProxyAppCrashed("NPE in onCreate"),
				SessionEvent.SessionRestartRequested,
				SessionEvent.SessionRestartAndReprovisionRequested(userInitiated = true),
				SessionEvent.SessionRestartAndReprovisionRequested(userInitiated = false),
			)
	}
}
