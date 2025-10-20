package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
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
import com.itsaky.androidide.idetooltips.TooltipManager
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

    private var treeView: TreeView<ResolvableVariable<*>>? = null
    private var gestureDetector: GestureDetector? = null

    private val viewModel by activityViewModels<DebuggerViewModel>()

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            treeView?.let {
              TooltipManager.showIdeCategoryTooltip(requireContext(), it, fragmentTooltipTag)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = TreeView<ResolvableVariable<*>>(requireContext())
        treeView = view
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        treeView?.apply {
            supportHorizontalScroll = true
            supportDragging = false
            tree = viewModel.variablesTree.value
            binder = VariableListBinder(viewLifecycleOwner.lifecycleScope, viewModel)

            bindCoroutineScope(viewLifecycleOwner.lifecycleScope)
        }

        gestureDetector = GestureDetector(requireContext(), gestureListener)



        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observeLatestVariablesTree(
                    scope = this,
                    notifyOn = Dispatchers.Main
                ) { tree ->
                    treeView?.tree = tree
                    treeView?.refresh()

                    treeView?.setOnTouchListener { _, event ->
                        gestureDetector?.onTouchEvent(event)
                        false
                    }
                }
            }
        }

    }

    override fun onDestroyView() {
        treeView = null
        gestureDetector = null
        super.onDestroyView()
    }
}
