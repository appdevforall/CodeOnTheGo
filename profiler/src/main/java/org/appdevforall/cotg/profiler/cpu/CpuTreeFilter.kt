/**
 * Pure call-tree transforms / queries used by the CPU result UI: classifying frames as
 * system/framework code, collapsing those frames away, and resolving a flamegraph path key back to
 * its [CpuCallNode]. Kept free of Compose so it can be unit-tested in isolation.
 */
package org.appdevforall.cotg.profiler.cpu

/** The synthetic tree root produced by [SimpleperfReportParser]; never treated as a system frame. */
private const val ROOT_NAME = "(root)"

/**
 * Whether [name] looks like Android framework / language-runtime / native-system code rather than
 * the profiled app's own code. This is a deliberately simple, *tunable* heuristic over the frame
 * name shapes simpleperf produces — fully-qualified Java/Kotlin methods (`android.view.View.draw`),
 * native symbols optionally prefixed with a library (`libc.so nativePollOnce`), C++ runtime symbols
 * (`art::gc::ConcurrentMark`), and kernel/JNI markers (`[kernel]`, `JNI stringFromJNI`).
 */
fun isSystemFrame(name: String): Boolean {
	if (name == ROOT_NAME) return false

	val packagePrefixes =
		arrayOf(
			"android.",
			"androidx.",
			"com.android.",
			"com.google.android.",
			"java.",
			"javax.",
			"kotlin.",
			"kotlinx.",
			"dalvik.",
			"sun.",
			"libcore.",
		)
	if (packagePrefixes.any { name.startsWith(it) }) return true

	if (name.startsWith("JNI ")) return true
	// "[" prefixes kernel / pseudo frames such as [kernel], [root], [vdso], ...
	if (name.startsWith("[")) return true

	val nativeMarkers =
		arrayOf("art::", "libc", "libart", "liblog", "libm.so", "libutils", "linker", "libcutils")
	return nativeMarkers.any { name.contains(it) }
}

/**
 * Returns a copy of this tree with every [isSystemFrame] node removed and its children lifted to the
 * nearest surviving (app) ancestor — so app code that runs *under* framework frames stays visible.
 * Lifted siblings that collide by name are merged (self/total summed, children merged recursively).
 *
 * The root is always kept. A collapsed system frame's own [CpuCallNode.selfMicros] is dropped: it
 * was time spent *in* hidden system code, so it simply shows as un-childed width under the surviving
 * parent. Totals therefore still satisfy `total >= sum(children.total)`, which the flamegraph layout
 * relies on.
 */
fun CpuCallNode.collapseSystemFrames(): CpuCallNode {
	val merged = LinkedHashMap<String, MergeNode>()
	collectVisibleChildren(children, merged)
	return CpuCallNode(
		name = name,
		selfMicros = selfMicros,
		totalMicros = totalMicros,
		children = merged.values.map { it.freeze() }.sortedByDescending { it.totalMicros },
	)
}

/**
 * Accumulates [nodes] into [out] keyed by name, recursing into (and dropping) system frames so their
 * descendants are re-parented here.
 */
private fun collectVisibleChildren(
	nodes: List<CpuCallNode>,
	out: LinkedHashMap<String, MergeNode>,
) {
	for (node in nodes) {
		if (isSystemFrame(node.name)) {
			// Drop this frame; lift its children to the current level.
			collectVisibleChildren(node.children, out)
		} else {
			val target = out.getOrPut(node.name) { MergeNode(node.name) }
			target.selfMicros += node.selfMicros
			target.totalMicros += node.totalMicros
			collectVisibleChildren(node.children, target.children)
		}
	}
}

/** Mutable accumulator used while merging re-parented siblings. */
private class MergeNode(
	val name: String,
) {
	var selfMicros = 0L
	var totalMicros = 0L
	val children = LinkedHashMap<String, MergeNode>()

	fun freeze(): CpuCallNode =
		CpuCallNode(
			name = name,
			selfMicros = selfMicros,
			totalMicros = totalMicros,
			children = children.values.map { it.freeze() }.sortedByDescending { it.totalMicros },
		)
}

/** The largest [CpuCallNode.selfMicros] anywhere in this subtree (used to normalize heat color). */
fun CpuCallNode.maxSelfMicros(): Long {
	var max = selfMicros
	for (child in children) max = maxOf(max, child.maxSelfMicros())
	return max
}

/**
 * Resolves a flamegraph path [key] (a `/`-joined child-index path; `null`/empty = this node) to the
 * matching [CpuCallNode], or null if the key is stale/invalid. The child-index scheme mirrors the
 * flamegraph's own (see `CpuCallNode.toFlameNode` and the layout's `resolveFocus`).
 */
fun CpuCallNode.nodeAtPath(key: String?): CpuCallNode? {
	if (key.isNullOrEmpty()) return this
	var node = this
	for (part in key.split('/')) {
		val index = part.toIntOrNull() ?: return null
		node = node.children.getOrNull(index) ?: return null
	}
	return node
}

/**
 * The root → selected node label path for [key] (e.g. `["(root)", "doFrame", "draw"]`), or empty if
 * [key] is stale/invalid. The root label itself is included.
 */
fun CpuCallNode.pathLabels(key: String?): List<String> {
	val labels = ArrayList<String>()
	labels.add(name)
	if (key.isNullOrEmpty()) return labels
	var node = this
	for (part in key.split('/')) {
		val index = part.toIntOrNull() ?: return emptyList()
		node = node.children.getOrNull(index) ?: return emptyList()
		labels.add(node.name)
	}
	return labels
}
