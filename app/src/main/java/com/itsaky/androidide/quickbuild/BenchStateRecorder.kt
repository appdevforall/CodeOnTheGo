package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState

/**
 * Fans quick-build session state changes into [BenchEventsFile] as `state` events for the
 * ADFA-4128 harness - a second, read-only collector on the session manager's existing
 * state stream; the UI's own collector is untouched. Each line is
 * `{"event":"state","state":<wireName>,"generation":<gen>?}`; `generation` appears only
 * for the states that carry one.
 */
class BenchStateRecorder(
	private val events: BenchEventsFile,
) {
	/** Collects [state] on [scope] until the scope is cancelled, writing one line per change. */
	fun attach(
		state: StateFlow<QuickBuildSessionState>,
		scope: CoroutineScope,
	) {
		scope.launch {
			state.collect(::record)
		}
	}

	fun record(state: QuickBuildSessionState) {
		events.append("state") {
			put("state", state.wireName())
			generationOf(state)?.let { put("generation", it) }
		}
	}

	// Pins today's serialized state values as an explicit contract, decoupled from the
	// Kotlin identifiers (which used to be serialized directly via ::class.simpleName).
	// The benchmark harness string-compares these literals (run_e2e_bench.py drives its
	// state machine off "Prewarming"), and historical .events.jsonl files carry them -
	// so an identifier rename must NOT change any string here. Same pattern as
	// AnalyticsQuickBuildMetricsSink.metricName().
	private fun QuickBuildSessionState.wireName(): String =
		when (this) {
			QuickBuildSessionState.Idle -> "Idle"
			is QuickBuildSessionState.Prebuilding -> "Prewarming"
			is QuickBuildSessionState.Provisioning -> "Provisioning"
			is QuickBuildSessionState.Ready -> "Ready"
			is QuickBuildSessionState.Building -> "Building"
			is QuickBuildSessionState.Deployed -> "Deployed"
			is QuickBuildSessionState.Invalidated -> "Invalidated"
			is QuickBuildSessionState.Degraded -> "Degraded"
		}

	private fun generationOf(state: QuickBuildSessionState): Long? =
		when (state) {
			is QuickBuildSessionState.Ready -> state.generation

			is QuickBuildSessionState.Building -> state.deployedGeneration

			is QuickBuildSessionState.Deployed -> state.generation

			is QuickBuildSessionState.Invalidated -> state.deployedGeneration

			is QuickBuildSessionState.Degraded -> state.deployedGeneration

			QuickBuildSessionState.Idle,
			is QuickBuildSessionState.Prebuilding,
			is QuickBuildSessionState.Provisioning,
			-> null
		}
}
