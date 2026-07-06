package org.appdevforall.cotg.profiler.heap

import androidx.compose.ui.graphics.Color
import org.appdevforall.cotg.flamegraph.model.FlameNode

/** Which retained quantity drives a heap frame's width. Shallow size is always the heat (self) metric. */
enum class HeapMetric {
	/** Frame width = retained bytes (memory freed if the object were collected). */
	RetainedBytes,

	/** Frame width = number of objects in the retained set. */
	InstanceCount,
}

/**
 * Converts the heap dominator tree into the flamegraph's weighted-node tree, mirroring
 * `CpuCallNode.toFlameNode`. The frame width follows [metric] (retained bytes or retained object
 * count — both are inclusive, since dominated subtrees are disjoint); ids are synthesized from the
 * path so identical class names on different branches stay distinct.
 *
 * [colorOf] assigns a per-frame fill — return null to fall back to the flamegraph's default palette.
 * It heat-colors frames by shallow (self) size; the layout honors a node's own color over the palette.
 */
fun HeapObjectNode.toFlameNode(
	metric: HeapMetric,
	id: String = "0",
	colorOf: (HeapObjectNode) -> Color? = { null },
): FlameNode<Nothing> =
	FlameNode(
		id = id,
		label = label,
		value =
			when (metric) {
				HeapMetric.RetainedBytes -> retainedBytes.toDouble()
				HeapMetric.InstanceCount -> retainedCount.toDouble()
			},
		color = colorOf(this),
		children = children.mapIndexed { index, child -> child.toFlameNode(metric, "$id/$index", colorOf) },
	)
