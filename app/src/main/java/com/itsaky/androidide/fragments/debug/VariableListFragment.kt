package com.itsaky.androidide.fragments.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import io.github.dingyi222666.view.treeview.TreeView
import kotlinx.coroutines.Dispatchers

/**
 * @author Akash Yadav
 */
class VariableListFragment : Fragment() {

    private lateinit var treeView: TreeView<EagerVariable<*>>

    private val viewModel by activityViewModels<DebuggerViewModel>()

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
            tree = viewModel.variablesTree.value
            binder = VariableListBinder()

            bindCoroutineScope(viewLifecycleOwner.lifecycleScope)
        }

        viewModel.observeLatestVariablesTree(
            notifyOn = Dispatchers.Main
        ) { tree ->
            treeView.tree = tree
            treeView.refresh()
        }
    }
}
