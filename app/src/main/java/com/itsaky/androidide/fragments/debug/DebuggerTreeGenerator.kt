package com.itsaky.androidide.fragments.debug

import io.github.dingyi222666.view.treeview.AbstractTree
import io.github.dingyi222666.view.treeview.Tree
import io.github.dingyi222666.view.treeview.TreeNode
import io.github.dingyi222666.view.treeview.TreeNodeGenerator

abstract class DebuggerTreeNode<T : Any>(
    protected val data: T,
) {

    abstract suspend fun createLabel(): CharSequence

    /**
     * Create a tree node for the current data.
     *
     * @param parent The parent node of the current node.
     * @param tree The tree that the current node belongs to.
     */
    abstract fun createTreeNode(
        parent: TreeNode<DebuggerTreeNode<T>>,
        tree: AbstractTree<DebuggerTreeNode<T>>
    ): TreeNode<DebuggerTreeNode<T>>

    /**
     * Create child nodes for the current node.
     */
    abstract suspend fun createChildNodes(
        target: TreeNode<DebuggerTreeNode<T>>
    ): Set<DebuggerTreeNode<T>>
}

class DebuggerTreeGenerator<T : Any> private constructor(
    private val rootNodes: Set<DebuggerTreeNode<*>> = emptySet()
) : TreeNodeGenerator<DebuggerTreeNode<T>> {

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : DebuggerTreeNode<*>> newInstance(
            rootNodes: Set<T> = emptySet()
        ): TreeNodeGenerator<T> = DebuggerTreeGenerator<T>(rootNodes) as TreeNodeGenerator<T>
    }

    override suspend fun fetchChildData(targetNode: TreeNode<DebuggerTreeNode<T>>): Set<DebuggerTreeNode<T>> =
        targetNode.requireData().createChildNodes(targetNode)

    override fun createNode(
        parentNode: TreeNode<DebuggerTreeNode<T>>,
        currentData: DebuggerTreeNode<T>,
        tree: AbstractTree<DebuggerTreeNode<T>>
    ): TreeNode<DebuggerTreeNode<T>> = currentData.createTreeNode(
        parent = parentNode,
        tree = tree,
    )

    override fun createRootNode(): TreeNode<DebuggerTreeNode<T>> = TreeNode(
        id = Tree.ROOT_NODE_ID,
        data = null,
        depth = -1,
        name = Tree.ROOT_NODE_ID.toString(),
        expand = true,
        isChild = false,
    )
}