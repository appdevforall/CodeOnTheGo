package org.appdevforall.cotg.profiler.heap

import androidx.compose.runtime.Immutable
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow

/**
 * A node in the heap dominator tree — the flamegraph-ready structure for a heap dump, mirroring
 * `CpuCallNode`. Each node is one heap object: [retainedBytes] is the memory it keeps alive (itself
 * plus everything it dominates), which becomes the frame's width; [children] are the objects it
 * immediately dominates. [shallowBytes] is the object's own size — the "self" weight, used for the
 * heat color (analogous to `CpuCallNode.selfMicros`). [retainedCount] is the number of objects in
 * the retained set, the alternative width metric.
 *
 * Because dominated subtrees are disjoint, `retainedBytes >= sum(children.retainedBytes)` and
 * likewise for `retainedCount`, so both make valid inclusive flamegraph widths.
 */
@Immutable
data class HeapObjectNode(
	val label: String,
	val shallowBytes: Long,
	val retainedBytes: Long,
	val retainedCount: Long,
	val children: List<HeapObjectNode>,
)

/**
 * Result of analysing a `.hprof`. [root] is the (pruned) object dominator tree for the flamegraph;
 * [rows] is the flat per-class histogram kept for the table view; [totalRetainedBytes] /
 * [totalObjects] are the whole-dump totals used to show a frame's share.
 */
@Immutable
data class HeapProfile(
	val root: HeapObjectNode,
	val totalRetainedBytes: Long,
	val totalObjects: Long,
	val rows: List<ProfilerTableRow>,
)
