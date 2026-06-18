package org.appdevforall.cotg.profiler.cpu

import org.appdevforall.cotg.flamegraph.model.FlameNode

/**
 * Converts an aggregated CPU call tree into the flamegraph's weighted-node tree. [totalMicros] is the
 * inclusive time, which becomes the frame's width; ids are synthesized from the path so identical
 * method names on different branches stay distinct.
 */
fun CpuCallNode.toFlameNode(id: String = "0"): FlameNode<Nothing> =
    FlameNode(
        id = id,
        label = name,
        value = totalMicros.toDouble(),
        children = children.mapIndexed { index, child -> child.toFlameNode("$id/$index") },
    )
