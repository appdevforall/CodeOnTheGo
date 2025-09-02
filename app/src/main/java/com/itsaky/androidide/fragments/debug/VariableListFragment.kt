package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_OUTPUT_CALLSTACK
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_OUTPUT_VARIABLES
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import io.github.dingyi222666.view.treeview.TreeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * @author Akash Yadav
 */
class VariableListFragment : Fragment() {

    val fragmentTooltipTag: String = DEBUG_OUTPUT_VARIABLES

    private lateinit var treeView: TreeView<ResolvableVariable<*>>
    private var tooltipHost: TooltipHost? = null
    private lateinit var gestureDetector: GestureDetector

    private val viewModel by activityViewModels<DebuggerViewModel>()

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            tooltipHost?.showToolTip(fragmentTooltipTag)
        }
    }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gestureDetector = GestureDetector(requireContext(), gestureListener)

        treeView.apply {
            supportHorizontalScroll = true
            supportDragging = false
            tree = viewModel.variablesTree.value
            binder = VariableListBinder(viewLifecycleOwner.lifecycleScope, viewModel)

            bindCoroutineScope(viewLifecycleOwner.lifecycleScope)
            
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observeLatestVariablesTree(
                    scope = this,
                    notifyOn = Dispatchers.Main
                ) { tree ->
                    treeView.tree = tree
                    treeView.refresh()
                }
            }
        }

    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        tooltipHost = parentFragment as? TooltipHost
    }

    override fun onDetach() {
        super.onDetach()
        tooltipHost = null
    }
}
