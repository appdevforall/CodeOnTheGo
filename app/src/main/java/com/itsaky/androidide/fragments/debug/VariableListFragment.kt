package com.itsaky.androidide.fragments.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.common.databinding.LayoutSimpleIconTextBinding
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import io.github.dingyi222666.view.treeview.TreeNode
import io.github.dingyi222666.view.treeview.TreeNodeEventListener
import io.github.dingyi222666.view.treeview.TreeView
import io.github.dingyi222666.view.treeview.TreeViewBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author Akash Yadav
 */
class VariableListFragment : Fragment() {

    private lateinit var treeView: TreeView<Variable<*>>

    private val viewModel by viewModels<DebuggerViewModel>(ownerProducer = { requireActivity() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (::treeView.isInitialized) {
            return treeView
        }

        treeView = TreeView(requireContext())
        return treeView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        treeView.apply {
            supportHorizontalScroll = true
            supportDragging = false
            binder = VariableListBinder(viewLifecycleOwner.lifecycleScope)

            bindCoroutineScope(viewLifecycleOwner.lifecycleScope)

            nodeEventListener = object : TreeNodeEventListener<Variable<*>> {
            }
        }

        viewModel.observeLatestVariablesTree(
            notifyOn = Dispatchers.Default
        ) { tree ->
            treeView.tree = tree
            treeView.refresh()
        }
    }
}

class VariableListBinder(
    private val coroutineScope: CoroutineScope
) : TreeViewBinder<Variable<*>>() {

    override fun createView(parent: ViewGroup, viewType: Int): View {
        val inflater = LayoutInflater.from(parent.context)
        val binding = LayoutSimpleIconTextBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun getItemViewType(node: TreeNode<Variable<*>>): Int = 0

    override fun bindView(
        holder: TreeView.ViewHolder,
        node: TreeNode<Variable<*>>,
        listener: TreeNodeEventListener<Variable<*>>
    ) {
        val binding = LayoutSimpleIconTextBinding.bind(holder.itemView)
        binding.root.apply {
            setPaddingRelative(node.depth * binding.root.context.resources.getDimensionPixelSize(
                R.dimen.content_padding_double), paddingTop, paddingEnd, paddingBottom)
        }
        coroutineScope.launch {
            val label = node.data?.let {
                "${it.name}: ${it.typeName}"
            } ?: "<unknown>"
            withContext(Dispatchers.Main) {
                binding.text.text = label
            }
        }
    }
}
