package org.appdevforall.cotg.profiler

import androidx.compose.runtime.Immutable
import org.appdevforall.cotg.profiler.cpu.CpuSample
import org.appdevforall.cotg.profiler.model.ProcessInfo

/**
 * Profiler UI state machine.
 *
 * Flow: `Idle → ChooseProcess → Profiling → (Completed | Failed)`, and any non-[Idle] state returns
 * to [Idle] (cancel / start-another). Only [Idle] shows the task-selection buttons; while a run is
 * in flight (a [Profiling] state) the user can only stop/cancel it — a second run can't be started.
 */
@Immutable
sealed interface ProfilerUiState {
	/** Choose a profiling task (heap dump / CPU hotspots). The only state that shows the start buttons. */
	data object Idle : ProfilerUiState

	/** Picking which process to profile for [mode]. */
	data class ChooseProcess(
		val mode: ProfilerMode,
		val processes: List<ProcessInfo>,
	) : ProfilerUiState

	/** A profiling run is in flight, typed by the [ProfilerReport] it produces. */
	sealed interface Profiling<out R : ProfilerReport> : ProfilerUiState {
		val process: ProcessInfo

		/** Heap dump in progress (one-shot, cancellable). */
		@Immutable
		data class HeapDump(
			override val process: ProcessInfo,
		) : Profiling<ProfilerReport.HeapDump>

		/**
		 * Live CPU sampling. [samples] grows as usage is sampled; [finalizing] is true after the user
		 * taps Stop while the perf.data is converted into the report.
		 */
		@Immutable
		data class CpuSampling(
			override val process: ProcessInfo,
			val samples: List<CpuSample>,
			val finalizing: Boolean = false,
		) : Profiling<ProfilerReport.CpuSampling>
	}

	/** A run finished successfully; [report] holds the produced data. */
	data class Completed(
		val process: ProcessInfo,
		val report: ProfilerReport,
	) : ProfilerUiState

	/** A run failed, or was cancelled by the user ([cancelled] = true). */
	data class Failed(
		val message: String,
		val cancelled: Boolean = false,
	) : ProfilerUiState
}
