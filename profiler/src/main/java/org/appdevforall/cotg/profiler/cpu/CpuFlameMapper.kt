package org.appdevforall.cotg.profiler.cpu

import androidx.compose.ui.graphics.Color
import org.appdevforall.cotg.flamegraph.model.FlameNode

/**
 * Converts an aggregated CPU call tree into the flamegraph's weighted-node tree. [totalMicros] is the
 * inclusive time, which becomes the frame's width; ids are synthesized from the path so identical
 * method names on different branches stay distinct.
 *
 * [colorOf] assigns a per-frame fill — return null to fall back to the flamegraph's default palette.
 * It's used to heat-color frames by self-time (see `heatColor`); the layout honors a node's own
 * color over the palette.
 */
fun CpuCallNode.toFlameNode(
	id: String = "0",
	colorOf: (CpuCallNode) -> Color? = { null },
): FlameNode<Nothing> =
	FlameNode(
		id = id,
		label = name,
		value = totalMicros.toDouble(),
		color = colorOf(this),
		children = children.mapIndexed { index, child -> child.toFlameNode("$id/$index", colorOf) },
	)
