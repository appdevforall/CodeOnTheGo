package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.model.Variable
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
    abstract fun <N: DebuggerTreeNode<T>> createTreeNode(
        parent: TreeNode<N>,
        tree: AbstractTree<N>
    ): TreeNode<N>

    /**
     * Create child nodes for the current node.
     */
    abstract suspend fun <N: DebuggerTreeNode<T>> createChildNodes(
        target: TreeNode<N>
    ): Set<N>
}

class VariableTreeNodeGenerator private constructor(
    private val rootNodes: Set<Variable<*>> = emptySet()
) : TreeNodeGenerator<Variable<*>> {

    companion object {
        fun newInstance(
            rootNodes: Set<Variable<*>> = emptySet()
        ): TreeNodeGenerator<Variable<*>> = VariableTreeNodeGenerator(rootNodes)
    }

    override fun createNode(
        parentNode: TreeNode<Variable<*>>,
        currentData: Variable<*>,
        tree: AbstractTree<Variable<*>>
    ): TreeNode<Variable<*>> = TreeNode(
        id = Tree.generateId(),
        data = currentData,
        depth = parentNode.depth + 1,
        name = currentData.name,
        expand = false,
        isBranch = true,
    )

    override suspend fun fetchChildData(targetNode: TreeNode<Variable<*>>): Set<Variable<*>> {
        if (targetNode.id == Tree.ROOT_NODE_ID) {
            return rootNodes
        }

        val data = targetNode.data ?: return emptySet()
        val members = data.objectMembers()
        return members
    }

    override fun createRootNode(): TreeNode<Variable<*>> {
        return TreeNode(
            id = Tree.ROOT_NODE_ID,
            data = null,
            depth = -1,
            name = Tree.ROOT_NODE_ID.toString(),
            expand = true,
            isBranch = false,
        )
    }
}