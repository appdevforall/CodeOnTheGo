package org.appdevforall.cotg.corpus.mixedcyclic.core;

/**
 * J -> K: renders a Kotlin-declared type whose own method body calls this renderer - the
 * back edge that makes TreeNode <-> NodeRenderer a cycle rather than a one-directional
 * reference like the mixed-lang app's.
 */
public final class NodeRenderer {

	public static String render(TreeNode node) {
		return "Node(" + node.getLabel() + ") QB_CYCLIC_J_MARKER_V2";
	}

	private NodeRenderer() {
	}
}
