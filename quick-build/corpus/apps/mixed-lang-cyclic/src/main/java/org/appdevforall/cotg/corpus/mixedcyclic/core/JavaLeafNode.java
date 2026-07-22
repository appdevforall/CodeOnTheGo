package org.appdevforall.cotg.corpus.mixedcyclic.core;

/**
 * Java subclass of a Kotlin class that is being compiled in the same pass - the sharpest
 * form of the cycle, since kotlinc must resolve this class (TreeNode.leaf returns it)
 * while its own supertype is still only a Kotlin source.
 */
public class JavaLeafNode extends TreeNode {

	public JavaLeafNode(String label) {
		super(label);
	}

	@Override
	public String describe() {
		return "Leaf[" + getLabel() + "]";
	}
}
