package org.appdevforall.cotg.profiler

import androidx.compose.runtime.Immutable
import org.appdevforall.cotg.profiler.cpu.CpuProfile
import org.appdevforall.cotg.profiler.heap.HeapProfile

/**
 * The result produced by a finished profiling run. Rendered by [ProfilerUiState.Completed],
 * branched on the concrete report type.
 */
@Immutable
sealed interface ProfilerReport {
	/** Heap dump result: the object dominator tree (flamegraph) + per-class histogram (table). */
	@Immutable
	data class HeapDump(
		val profile: HeapProfile,
	) : ProfilerReport

	/** CPU sampling result: the aggregated call tree + per-method table (flamegraph-ready). */
	@Immutable
	data class CpuSampling(
		val profile: CpuProfile,
	) : ProfilerReport
}
