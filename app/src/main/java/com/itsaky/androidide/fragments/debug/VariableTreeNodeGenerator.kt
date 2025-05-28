package com.itsaky.androidide.fragments.debug

import io.github.dingyi222666.view.treeview.AbstractTree
import io.github.dingyi222666.view.treeview.Tree
import io.github.dingyi222666.view.treeview.TreeNode
import io.github.dingyi222666.view.treeview.TreeNodeGenerator

class VariableTreeNodeGenerator private constructor(
    private val rootNodes: Set<EagerVariable<*>> = emptySet()
) : TreeNodeGenerator<EagerVariable<*>> {

    companion object {
        fun newInstance(
            rootNodes: Set<EagerVariable<*>> = emptySet()
        ): TreeNodeGenerator<EagerVariable<*>> = VariableTreeNodeGenerator(rootNodes)
    }

    override fun createNode(
        parentNode: TreeNode<EagerVariable<*>>,
        currentData: EagerVariable<*>,
        tree: AbstractTree<EagerVariable<*>>
    ): TreeNode<EagerVariable<*>> = TreeNode(
        id = Tree.generateId(),
        data = currentData,
        depth = parentNode.depth + 1,
        name = currentData.name,
        expand = false,
        isBranch = true,
    )

    override suspend fun fetchChildData(targetNode: TreeNode<EagerVariable<*>>): Set<EagerVariable<*>> {
        if (targetNode.id == Tree.ROOT_NODE_ID) {
            return rootNodes
        }

        val data = targetNode.data ?: return emptySet()
        val members = data.objectMembers()
        return members
    }

    override fun createRootNode(): TreeNode<EagerVariable<*>> {
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