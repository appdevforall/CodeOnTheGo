package org.appdevforall.cotg.corpus.mixedcyclic.core

/**
 * Kotlin half of a genuine Kotlin<->Java cycle: this class calls [NodeRenderer] (Java),
 * which calls back into this class, and [JavaLeafNode] (Java) extends it. Neither
 * language can be compiled first in isolation - the case sora-editor's :editor module
 * hits at scale (corpus README, Large-real-app tier finding 2).
 */
open class TreeNode(
	val label: String,
) {
	/** Kotlin-only body, so an edit here stays inside the single-language fast path. */
	open fun summary(): String = "TreeNode:" + label + " QB_CYCLIC_K_MARKER_V2"

	/** K -> J: rendering lives in Java, which reads this node back. Return type is
	 * deliberately inferred, so a Java-side signature change propagates into this class'
	 * own ABI - the honest test that a .java edit cannot leave Kotlin bytecode stale. */
	open fun describe() = NodeRenderer.render(this)

	companion object {
		/** K -> J through the type hierarchy: [JavaLeafNode]'s supertype is this class. */
		fun leaf(label: String): TreeNode = JavaLeafNode(label)
	}
}
