package org.appdevforall.cotg.profiler.heap

/**
 * Pure dominator-tree transforms / queries used by the heap result UI: classifying classes as
 * framework code, collapsing those nodes away, normalizing the heat scale, and resolving a flamegraph
 * path key back to its [HeapObjectNode]. Kept free of Compose so it can be unit-tested in isolation.
 * Mirrors `CpuTreeFilter`.
 */

/** The synthetic dominator-tree root produced by [HeapDominators]; never treated as framework code. */
private const val ROOT_LABEL = HeapDominators.ROOT_LABEL

/**
 * Whether [label] looks like an Android framework / language-runtime class rather than the profiled
 * app's own code. A deliberately simple, tunable heuristic over the fully-qualified class names that
 * appear as dominator-tree labels (`java.lang.String`, `android.graphics.Bitmap`, `class kotlin.Unit`).
 */
fun isFrameworkClass(label: String): Boolean {
    if (label == ROOT_LABEL) return false
    val name = label.removePrefix("class ")
    val packagePrefixes =
        arrayOf(
            "android.", "androidx.", "com.android.", "com.google.android.",
            "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.", "sun.", "libcore.",
        )
    return packagePrefixes.any { name.startsWith(it) }
}

/**
 * Returns a copy of this tree with every [isFrameworkClass] node removed and its children lifted to
 * the nearest surviving (app) ancestor — so app objects retained *under* framework objects stay
 * visible. Lifted siblings that collide by class name are merged (shallow/retained sizes and counts
 * summed, children merged recursively). The root is always kept.
 *
 * A collapsed framework node's own [HeapObjectNode.shallowBytes] is dropped: it becomes un-childed
 * width under the surviving parent, so `retainedBytes >= sum(children.retainedBytes)` still holds,
 * which the flamegraph layout relies on.
 */
fun HeapObjectNode.collapseFrameworkClasses(): HeapObjectNode {
    val merged = LinkedHashMap<String, MergeNode>()
    collectVisibleChildren(children, merged)
    return HeapObjectNode(
        label = label,
        shallowBytes = shallowBytes,
        retainedBytes = retainedBytes,
        retainedCount = retainedCount,
        children = merged.values.map { it.freeze() }.sortedByDescending { it.retainedBytes },
    )
}

private fun collectVisibleChildren(
    nodes: List<HeapObjectNode>,
    out: LinkedHashMap<String, MergeNode>,
) {
    for (node in nodes) {
        if (isFrameworkClass(node.label)) {
            // Drop this node; lift its children to the current level.
            collectVisibleChildren(node.children, out)
        } else {
            val target = out.getOrPut(node.label) { MergeNode(node.label) }
            target.shallowBytes += node.shallowBytes
            target.retainedBytes += node.retainedBytes
            target.retainedCount += node.retainedCount
            collectVisibleChildren(node.children, target.children)
        }
    }
}

/** Mutable accumulator used while merging re-parented siblings. */
private class MergeNode(val label: String) {
    var shallowBytes = 0L
    var retainedBytes = 0L
    var retainedCount = 0L
    val children = LinkedHashMap<String, MergeNode>()

    fun freeze(): HeapObjectNode =
        HeapObjectNode(
            label = label,
            shallowBytes = shallowBytes,
            retainedBytes = retainedBytes,
            retainedCount = retainedCount,
            children = children.values.map { it.freeze() }.sortedByDescending { it.retainedBytes },
        )
}

/** The largest [HeapObjectNode.shallowBytes] anywhere in this subtree (used to normalize heat color). */
fun HeapObjectNode.maxShallowBytes(): Long {
    var max = shallowBytes
    for (child in children) max = maxOf(max, child.maxShallowBytes())
    return max
}

/**
 * Resolves a flamegraph path [key] (a `/`-joined child-index path; `null`/empty = this node) to the
 * matching [HeapObjectNode], or null if the key is stale/invalid. The child-index scheme mirrors the
 * flamegraph's own (see [toFlameNode] and the layout's `resolveFocus`).
 */
fun HeapObjectNode.nodeAtPath(key: String?): HeapObjectNode? {
    if (key.isNullOrEmpty()) return this
    var node = this
    for (part in key.split('/')) {
        val index = part.toIntOrNull() ?: return null
        node = node.children.getOrNull(index) ?: return null
    }
    return node
}

/**
 * The root → selected node label path for [key] (e.g. `["(heap)", "MainActivity", "byte[]"]`), or
 * empty if [key] is stale/invalid. The root label itself is included.
 */
fun HeapObjectNode.pathLabels(key: String?): List<String> {
    val labels = ArrayList<String>()
    labels.add(label)
    if (key.isNullOrEmpty()) return labels
    var node = this
    for (part in key.split('/')) {
        val index = part.toIntOrNull() ?: return emptyList()
        node = node.children.getOrNull(index) ?: return emptyList()
        labels.add(node.label)
    }
    return labels
}
