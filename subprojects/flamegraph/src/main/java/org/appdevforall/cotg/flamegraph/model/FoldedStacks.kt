package org.appdevforall.cotg.flamegraph.model

/**
 * Builds a [FlameNode] tree from collapsed/"folded" stacks.
 *
 * Each entry is a `(frames, value)` pair where `frames` is a path from the outermost frame to the
 * innermost (e.g. `["main", "a", "b"]`) and `value` is its weight (sample count, bytes, …). Frames
 * sharing a prefix are merged and their values summed, so the result is the inclusive-weight tree
 * the flamegraph expects. Node ids are synthesized from the path so identical labels on different
 * branches stay distinct.
 *
 * This is pure Kotlin (no Android/Compose runtime) and is the primary unit-tested entry point.
 */
fun buildFlameTree(
    stacks: List<Pair<List<String>, Double>>,
    rootLabel: String = "root",
): FlameNode<Nothing> {
    val root = MutableFlameNode(rootLabel)
    for ((frames, value) in stacks) {
        root.value += value
        var node = root
        for (frame in frames) {
            node = node.children.getOrPut(frame) { MutableFlameNode(frame) }
            node.value += value
        }
    }
    return root.toImmutable(id = "0")
}

/**
 * Parses Brendan-Gregg "folded stacks" text into a [FlameNode] tree.
 *
 * Each non-blank line has the form `frame1;frame2;frame3 value`, where the weight is the last
 * whitespace-separated token (so frame names may themselves contain spaces) and the frames are
 * joined by [separator]. Blank lines are ignored; a line without a parseable trailing number is a
 * hard error (fail fast rather than silently dropping data).
 *
 * @throws IllegalArgumentException if a non-blank line has no numeric weight.
 */
fun parseFoldedStacks(
    text: String,
    separator: Char = ';',
    rootLabel: String = "root",
): FlameNode<Nothing> {
    val stacks = ArrayList<Pair<List<String>, Double>>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val cut = line.lastIndexOf(' ')
        val value = line.substring(cut + 1).toDoubleOrNull()
        require(cut > 0 && value != null) { "Malformed folded-stack line: \"$rawLine\"" }
        val frames = line.substring(0, cut).split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        stacks.add(frames to value)
    }
    return buildFlameTree(stacks, rootLabel)
}

private class MutableFlameNode(val label: String) {
    var value: Double = 0.0
    val children = LinkedHashMap<String, MutableFlameNode>()

    fun toImmutable(id: String): FlameNode<Nothing> {
        val kids = ArrayList<FlameNode<Nothing>>(children.size)
        for ((index, child) in children.values.withIndex()) {
            kids.add(child.toImmutable("$id/$index"))
        }
        return FlameNode(id = id, label = label, value = value, children = kids)
    }
}
