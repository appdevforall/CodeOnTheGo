@file:Suppress("ktlint:standard:max-line-length")

package org.appdevforall.cotg.profiler.heap

import org.appdevforall.cotg.profiler.heap.dominator.DominatorNode
import org.appdevforall.cotg.profiler.heap.dominator.DominatorTree
import org.appdevforall.cotg.profiler.heap.dominator.LongScatterSet
import org.slf4j.LoggerFactory
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ValueHolder

/**
 * Computes the heap **dominator tree** (what each object retains) from a `.hprof`, producing the
 * [HeapObjectNode] tree the flamegraph renders.
 *
 * Shark can compute this, but its dominator/retained-size types are all `internal`, so we vendor only
 * the self-contained algorithm ([DominatorTree]) and drive it ourselves: a breadth-first walk from
 * the GC roots over Shark's **public** [HeapGraph] API (`gcRoots`, `readFields`, `readElements`,
 * `readStaticFields`) feeds every reference edge into the dominator tree, then `buildFullDominatorTree`
 * computes retained sizes/counts. This stays close to Shark's own numbers but doesn't replicate its
 * native-allocation sizing or ignored-reference matchers, so retained sizes are approximate — fine
 * for a visualization.
 *
 * A full per-object dominator tree can hold millions of nodes, so the result is **pruned**: a node is
 * kept only if it retains at least [SIGNIFICANT_FRACTION] of the dump's total bytes *or* objects, and
 * the tree is capped at [MAX_NODES] frames. Pruned descendants fold into their nearest kept ancestor's
 * "self" weight (the flamegraph renders `value − Σchildren` as an empty gap).
 */
internal object HeapDominators {
	private val logger = LoggerFactory.getLogger(HeapDominators::class.java)

	/** Label of the synthetic root that gathers every GC-root dominator (mirrors CPU's "(root)"). */
	const val ROOT_LABEL = "(heap)"

	/** Hard cap on frames so a huge heap can't produce an unrenderable (or OOM) tree. */
	private const val MAX_NODES = 20_000

	/** A node must retain at least this fraction of the dump's total bytes or objects to be kept. */
	private const val SIGNIFICANT_FRACTION = 0.001 // 0.1%

	/** Builds the dominator tree by traversing [graph] via Shark's public API. */
	fun fromHeapGraph(graph: HeapGraph): HeapObjectNode =
		build(
			expectedElements = graph.objectCount,
			rootIds =
				graph.gcRoots
					.asSequence()
					.map { it.id }
					.filter { graph.objectExists(it) }
					.toList(),
			referencesOf = { id -> referencesOf(graph, id) },
			shallowSizeOf = { id -> shallowSizeOf(graph, id) },
			labelOf = { id -> labelOf(graph, id) },
		)

	/**
	 * Pure dominator-tree build over an abstract object graph — no Shark types, so it is unit-testable
	 * with a synthetic in-memory graph. [referencesOf] yields the outgoing reference targets of an
	 * object; [shallowSizeOf] its own byte size; [labelOf] its display name.
	 */
	fun build(
		rootIds: List<Long>,
		referencesOf: (Long) -> Sequence<Long>,
		shallowSizeOf: (Long) -> Int,
		labelOf: (Long) -> String,
		expectedElements: Int = 4,
	): HeapObjectNode {
		val dominatorTree = DominatorTree(expectedElements)
		val visited = LongScatterSet(expectedElements)
		val queue = ArrayDeque<Long>()

		for (rootId in rootIds) {
			if (visited.add(rootId)) {
				dominatorTree.updateDominatedAsRoot(rootId)
				queue.addLast(rootId)
			}
		}
		while (queue.isNotEmpty()) {
			val id = queue.removeFirst()
			for (childId in referencesOf(id)) {
				// Record the edge for every reference (so the lowest common dominator is correct),
				// but only enqueue each object once.
				dominatorTree.updateDominated(childId, id)
				if (visited.add(childId)) {
					queue.addLast(childId)
				}
			}
		}

		val tree = dominatorTree.buildFullDominatorTree { shallowSizeOf(it) }
		return assemble(tree, labelOf)
	}

	/** A kept node awaiting its children to be frozen. */
	private class Builder(
		val dom: DominatorNode,
	) {
		var childIds: List<Long> = emptyList()
	}

	/** Prunes [tree] to significant nodes and assembles the immutable [HeapObjectNode] tree. */
	private fun assemble(
		tree: Map<Long, DominatorNode>,
		labelOf: (Long) -> String,
	): HeapObjectNode {
		val rootDom =
			tree[ValueHolder.NULL_REFERENCE]
				?: return HeapObjectNode(ROOT_LABEL, 0, 0, 0, emptyList())

		val totalBytes = rootDom.retainedSize.toLong()
		val totalCount = rootDom.retainedCount.toLong()
		val byteThreshold = maxOf(1L, (totalBytes * SIGNIFICANT_FRACTION).toLong())
		val countThreshold = maxOf(1L, (totalCount * SIGNIFICANT_FRACTION).toLong())

		val builders = HashMap<Long, Builder>()
		val discovered = ArrayList<Long>() // ids in BFS order; children always follow their parent
		val queue = ArrayDeque<Long>()
		var budget = MAX_NODES
		var dropped = false

		fun significant(dom: DominatorNode): Boolean =
			dom.retainedSize.toLong() >= byteThreshold || dom.retainedCount.toLong() >= countThreshold

		fun keepChildren(ids: List<Long>): List<Long> {
			val kept = ArrayList<Long>()
			for (id in ids) {
				val dom = tree[id] ?: continue
				if (!significant(dom)) {
					dropped = true
					continue
				}
				if (budget <= 0) {
					dropped = true
					break
				}
				budget--
				builders[id] = Builder(dom)
				discovered.add(id)
				queue.addLast(id)
				kept.add(id)
			}
			return kept
		}

		val rootChildIds = keepChildren(rootDom.dominatedObjectIds)
		while (queue.isNotEmpty()) {
			val id = queue.removeFirst()
			val builder = builders.getValue(id)
			builder.childIds = keepChildren(builder.dom.dominatedObjectIds)
		}

		// Freeze bottom-up: a node's children are discovered after it, so reverse order freezes
		// every child before its parent without recursing (deep dominator chains can't overflow).
		val frozen = HashMap<Long, HeapObjectNode>(discovered.size)
		for (i in discovered.indices.reversed()) {
			val id = discovered[i]
			val builder = builders.getValue(id)
			frozen[id] =
				HeapObjectNode(
					label = labelOf(id),
					shallowBytes = builder.dom.shallowSize.toLong(),
					retainedBytes = builder.dom.retainedSize.toLong(),
					retainedCount = builder.dom.retainedCount.toLong(),
					children = builder.childIds.map { frozen.getValue(it) },
				)
		}

		if (dropped) {
			logger.debug(
				"Heap dominator tree pruned to {} frames (cap {}); " +
					"small subtrees fold into their parent's self.",
				discovered.size,
				MAX_NODES,
			)
		}

		return HeapObjectNode(
			label = ROOT_LABEL,
			shallowBytes = 0,
			retainedBytes = totalBytes,
			retainedCount = totalCount,
			children = rootChildIds.map { frozen.getValue(it) },
		)
	}

	private fun referencesOf(
		graph: HeapGraph,
		id: Long,
	): Sequence<Long> =
		when (val obj = graph.findObjectById(id)) {
			is HeapInstance -> obj.readFields().mapNotNull { it.value.asNonNullObjectId }
			is HeapObjectArray -> obj.readElements().mapNotNull { it.asNonNullObjectId }
			is HeapClass -> obj.readStaticFields().mapNotNull { it.value.asNonNullObjectId }
			is HeapPrimitiveArray -> emptySequence()
		}.filter { graph.objectExists(it) }

	private fun shallowSizeOf(
		graph: HeapGraph,
		id: Long,
	): Int =
		when (val obj = graph.findObjectById(id)) {
			is HeapInstance -> obj.byteSize
			is HeapObjectArray -> obj.byteSize
			is HeapPrimitiveArray -> obj.byteSize

			// classes are histogrammed as roots with no shallow size (see HeapDumpAnalyzer)
			is HeapClass -> 0
		}

	private fun labelOf(
		graph: HeapGraph,
		id: Long,
	): String =
		when (val obj = graph.findObjectById(id)) {
			is HeapClass -> "class ${obj.name}"
			is HeapInstance -> obj.instanceClassName
			is HeapObjectArray -> obj.arrayClassName
			is HeapPrimitiveArray -> obj.arrayClassName
		}
}
