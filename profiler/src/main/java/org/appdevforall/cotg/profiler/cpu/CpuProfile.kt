package org.appdevforall.cotg.profiler.cpu

import androidx.compose.runtime.Immutable

/** A single live CPU-usage data point streamed while recording. */
@Immutable
data class CpuSample(
    val elapsedMillis: Long,
    val cpuPercent: Float,
)

/**
 * A node in the aggregated call tree. This is the flamegraph-ready structure: each node is a frame,
 * [totalMicros] is its inclusive time (the frame's width), and [children] are the frames called from
 * it. [selfMicros] is the exclusive time spent in the frame itself.
 */
@Immutable
data class CpuCallNode(
    val name: String,
    val selfMicros: Long,
    val totalMicros: Long,
    val children: List<CpuCallNode>,
)

/** One row of the per-method table (aggregated across the whole profile). */
@Immutable
data class CpuMethodRow(
    val name: String,
    val totalMicros: Long,
    val totalPercent: Float,
    val childrenMicros: Long,
    val childrenPercent: Float,
)

/**
 * Result of a CPU profiling session. [root] is the full call tree (kept for the future flamegraph);
 * [methods] is the flattened per-method table derived from the same samples.
 */
@Immutable
data class CpuProfile(
    val root: CpuCallNode,
    val totalMicros: Long,
    val methods: List<CpuMethodRow>,
)
