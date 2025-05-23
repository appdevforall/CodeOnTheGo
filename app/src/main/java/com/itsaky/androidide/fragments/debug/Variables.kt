package com.itsaky.androidide.fragments.debug

import com.itsaky.androidide.lsp.debug.model.Variable
import io.github.dingyi222666.view.treeview.AbstractTree
import io.github.dingyi222666.view.treeview.TreeNode

class VariablesTreeNode(
    variable: Variable<*>,
): DebuggerTreeNode<Variable<*>>(
    data = variable,
) {
    override suspend fun createLabel(): CharSequence {
        return "${data.name}: ${data.typeName}"
    }

    override fun createTreeNode(
        parent: TreeNode<DebuggerTreeNode<*>>,
        tree: AbstractTree<DebuggerTreeNode<*>>
    ): TreeNode<DebuggerTreeNode<*>> = TreeNode(
        data = this,
        depth = parent.depth + 1,
        path = parent.path + "/" + data.name,
        name = data.name,
        id = tree.generateId(),
    )

    override suspend fun createChildNodes(
        target: TreeNode<DebuggerTreeNode<*>>
    ): Set<DebuggerTreeNode<out Variable<*>>> {
        val data = (target.data as? VariablesTreeNode?).data
        return data.objectMembers().map { variable ->
            VariablesTreeNode(variable)
        }.toSet()
    }
}