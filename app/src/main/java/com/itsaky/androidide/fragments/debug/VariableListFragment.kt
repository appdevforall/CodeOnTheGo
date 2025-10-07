package com.itsaky.androidide.fragments.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import io.github.dingyi222666.view.treeview.TreeView
import kotlinx.coroutines.launch

/**
 * @author Akash Yadav
 */
class VariableListFragment : Fragment() {

    private var treeView: TreeView<ResolvableVariable<*>>? = null

    private val viewModel by activityViewModels<DebuggerViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = TreeView<ResolvableVariable<*>>(requireContext())
        treeView = view
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        treeView?.apply {
            supportHorizontalScroll = true
            supportDragging = false
            tree = viewModel.variablesTree.value
            binder = VariableListBinder(viewLifecycleOwner.lifecycleScope, viewModel)

            bindCoroutineScope(viewLifecycleOwner.lifecycleScope)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.variablesTree.collect { tree ->
                    treeView?.tree = tree
                    treeView?.refresh()
                }
            }
        }

    }

    override fun onDestroyView() {
        treeView = null
        super.onDestroyView()
    }
}
