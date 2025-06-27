package com.itsaky.androidide.fragments.debug

import io.github.dingyi222666.view.treeview.AbstractTree
import io.github.dingyi222666.view.treeview.Tree
import io.github.dingyi222666.view.treeview.TreeNode
import io.github.dingyi222666.view.treeview.TreeNodeGenerator
import org.slf4j.LoggerFactory

class VariableTreeNodeGenerator private constructor(
    private val rootNodes: Set<ResolvableVariable<*>> = emptySet()
) : TreeNodeGenerator<ResolvableVariable<*>> {

    companion object {
        private val logger = LoggerFactory.getLogger(VariableTreeNodeGenerator::class.java)
        fun newInstance(
            rootNodes: Set<ResolvableVariable<*>> = emptySet()
        ): TreeNodeGenerator<ResolvableVariable<*>> = VariableTreeNodeGenerator(rootNodes)
    }

    override fun createNode(
        parentNode: TreeNode<ResolvableVariable<*>>,
        currentData: ResolvableVariable<*>,
        tree: AbstractTree<ResolvableVariable<*>>
    ): TreeNode<ResolvableVariable<*>> = TreeNode(
        id = Tree.generateId(),
        data = currentData,
        depth = parentNode.depth + 1,
        name = currentData.resolvedOrNull?.name ?: "<unknown>",
        isExpanded = false,
        isBranch = true,
    )

    override suspend fun fetchChildData(targetNode: TreeNode<ResolvableVariable<*>>): Set<ResolvableVariable<*>> {
        if (targetNode.id == Tree.ROOT_NODE_ID) {
            return rootNodes
        }

        val data = targetNode.data ?: return emptySet()
        return data.objectMembers()
    }

    override fun createRootNode(): TreeNode<ResolvableVariable<*>> {
        return TreeNode(
            id = Tree.ROOT_NODE_ID,
            data = null,
            depth = -1,
            name = Tree.ROOT_NODE_ID.toString(),
            isExpanded = true,
            isBranch = false,
        )
    }
}