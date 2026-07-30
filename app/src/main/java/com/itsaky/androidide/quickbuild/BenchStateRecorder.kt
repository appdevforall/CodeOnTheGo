package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState

/**
 * Fans quick-build session state changes into [BenchEventsFile] as `state` events for the
 * ADFA-4128 harness - a second, read-only collector on the session manager's existing
 * state stream; the UI's own collector is untouched. Each line is
 * `{"event":"state","state":<simpleName>,"generation":<gen>?}`; `generation` appears only
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
			put("state", state::class.simpleName ?: "Unknown")
			generationOf(state)?.let { put("generation", it) }
		}
	}

	private fun generationOf(state: QuickBuildSessionState): Long? =
		when (state) {
			is QuickBuildSessionState.Ready -> state.generation

			is QuickBuildSessionState.Building -> state.deployedGeneration

			is QuickBuildSessionState.Deployed -> state.generation

			is QuickBuildSessionState.Invalidated -> state.deployedGeneration

			is QuickBuildSessionState.Degraded -> state.deployedGeneration

			QuickBuildSessionState.Idle,
			is QuickBuildSessionState.Prewarming,
			is QuickBuildSessionState.Provisioning,
			-> null
		}
}
