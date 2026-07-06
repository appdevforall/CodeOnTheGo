package org.appdevforall.cotg.flamegraph.internal

import androidx.compose.ui.graphics.Color
import org.appdevforall.cotg.flamegraph.model.FlameNode

/** Path key of the true root; an empty/null focus key is normalised to this. */
internal const val ROOT_KEY = ""

/**
 * Subtrees narrower than this fraction of the current focus root are dropped from the layout (they
 * would be sub-pixel on any realistic width). They become reachable again by focusing a visible
 * ancestor, which re-bases all fractions to that subtree.
 */
internal const val MIN_FRACTION = 0.0002f

internal fun childKey(
	parentKey: String,
	index: Int,
): String = if (parentKey.isEmpty()) index.toString() else "$parentKey/$index"

internal data class FrameRef(
	val key: String,
	val node: FlameNode<*>,
)

/**
 * Resolves a focus [key] (a `/`-joined child-index path) back to its node. Falls back to the root
 * if the key is stale or invalid — e.g. restored from saved state against a different tree. This is
 * UI-state recovery, not error suppression: an invalid key simply means "no usable focus".
 */
internal fun resolveFocus(
	root: FlameNode<*>,
	key: String?,
): FrameRef {
	if (key.isNullOrEmpty()) return FrameRef(ROOT_KEY, root)
	var node: FlameNode<*> = root
	var resolved = ROOT_KEY
	for (part in key.split('/')) {
		val index = part.toIntOrNull() ?: return FrameRef(ROOT_KEY, root)
		val child = node.children.getOrNull(index) ?: return FrameRef(ROOT_KEY, root)
		resolved = childKey(resolved, index)
		node = child
	}
	return FrameRef(resolved, node)
}

/**
 * Whether the frame at [framePathKey] belongs to the highlighted subtree of [selectedKey] — the
 * selected frame itself or any descendant. A null/empty selection highlights everything (no fade).
 */
internal fun isInSubtree(
	framePathKey: String,
	selectedKey: String?,
): Boolean {
	if (selectedKey.isNullOrEmpty()) return true
	return framePathKey == selectedKey || framePathKey.startsWith("$selectedKey/")
}

/** A single frame, positioned in fractional coordinates relative to the current focus root. */
internal class LaidOutFrame(
	val pathKey: String,
	val label: String,
	val depth: Int,
	val xFraction: Float,
	val widthFraction: Float,
	val color: Color,
	val nodeIndex: Int,
	val value: Double,
)

/** A resolution-independent layout of the focused subtree; pixels are applied only at draw time. */
internal class FlameLayout(
	val frames: List<LaidOutFrame>,
	val framesByDepth: List<List<LaidOutFrame>>,
	val nodes: List<FlameNode<*>>,
	val maxDepth: Int,
	val rootKey: String,
) {
	val rowCount: Int get() = if (frames.isEmpty()) 0 else maxDepth + 1
	val isEmpty: Boolean get() = frames.isEmpty()

	/** Frame at [depth] containing [xFraction] (0..1), or null if the point falls in a gap. */
	fun frameAt(
		depth: Int,
		xFraction: Float,
	): LaidOutFrame? {
		if (depth < 0 || depth >= framesByDepth.size) return null
		val row = framesByDepth[depth]
		var lo = 0
		var hi = row.size - 1
		while (lo <= hi) {
			val mid = (lo + hi) ushr 1
			val frame = row[mid]
			when {
				xFraction < frame.xFraction -> hi = mid - 1
				xFraction >= frame.xFraction + frame.widthFraction -> lo = mid + 1
				else -> return frame
			}
		}
		return null
	}
}

/**
 * Lays out the subtree rooted at [focusedKey] (or the whole tree when null) into fractional frames.
 * Iterative (explicit stack) so deep trees cannot overflow. Colours are resolved here via [colorOf]
 * so the draw loop allocates nothing.
 */
internal fun layoutFlame(
	root: FlameNode<*>,
	focusedKey: String?,
	colorOf: (label: String, depth: Int) -> Color,
): FlameLayout {
	val focus = resolveFocus(root, focusedKey)
	val focusNode = focus.node
	if (focusNode.value <= 0.0 && focusNode.children.isEmpty()) {
		return FlameLayout(emptyList(), emptyList(), emptyList(), 0, focus.key)
	}

	val frames = ArrayList<LaidOutFrame>()
	val nodes = ArrayList<FlameNode<*>>()
	var maxDepth = 0

	val stack = ArrayDeque<LayoutItem>()
	stack.addLast(LayoutItem(focusNode, focus.key, 0, 0f, 1f))

	while (stack.isNotEmpty()) {
		val item = stack.removeLast()
		val node = item.node
		if (item.depth > maxDepth) maxDepth = item.depth
		frames.add(
			LaidOutFrame(
				pathKey = item.key,
				label = node.label,
				depth = item.depth,
				xFraction = item.x,
				widthFraction = item.width,
				color = node.color ?: colorOf(node.label, item.depth),
				nodeIndex = nodes.size,
				value = node.value,
			),
		)
		nodes.add(node)

		val children = node.children
		if (children.isEmpty()) continue
		var childrenTotal = 0.0
		for (child in children) childrenTotal += child.value
		val effective = maxOf(node.value, childrenTotal)
		if (effective <= 0.0) continue

		var runningX = item.x
		for (index in children.indices) {
			val child = children[index]
			val fraction = (child.value / effective).toFloat().coerceAtLeast(0f)
			val childWidth = item.width * fraction
			if (childWidth >= MIN_FRACTION) {
				stack.addLast(LayoutItem(child, childKey(item.key, index), item.depth + 1, runningX, childWidth))
			}
			runningX += childWidth
		}
	}

	val byDepth = ArrayList<MutableList<LaidOutFrame>>(maxDepth + 1)
	repeat(maxDepth + 1) { byDepth.add(ArrayList()) }
	for (frame in frames) byDepth[frame.depth].add(frame)
	for (row in byDepth) row.sortBy { it.xFraction }

	return FlameLayout(frames, byDepth, nodes, maxDepth, focus.key)
}

private class LayoutItem(
	val node: FlameNode<*>,
	val key: String,
	val depth: Int,
	val x: Float,
	val width: Float,
)
