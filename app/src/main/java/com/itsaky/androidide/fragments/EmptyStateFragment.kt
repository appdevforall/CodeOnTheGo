package com.itsaky.androidide.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.itsaky.androidide.databinding.FragmentEmptyStateBinding
import com.itsaky.androidide.editor.ui.EditorLongPressEvent
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.utils.TooltipUtils
import com.itsaky.androidide.viewmodel.EmptyStateFragmentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

abstract class EmptyStateFragment<T : ViewBinding> : FragmentWithBinding<T> {

  constructor(layout: Int, bind: (View) -> T) : super(layout, bind)
  constructor(inflate: (LayoutInflater, ViewGroup?, Boolean) -> T) : super(inflate)

  protected var emptyStateBinding: FragmentEmptyStateBinding? = null
    private set

  protected val emptyStateViewModel by viewModels<EmptyStateFragmentViewModel>()

  private lateinit var gestureDetector: GestureDetector

  /**
   * Called when a long press is detected on the fragment's root view.
   * Subclasses must implement this to define the action (e.g., show a tooltip).
   */
  protected abstract fun onFragmentLongPressed()

  private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
    override fun onLongPress(e: MotionEvent) {
      onFragmentLongPressed()
    }
  }

  internal var isEmpty: Boolean
    get() = emptyStateViewModel.isEmpty.value ?: false
    set(value) {
      emptyStateViewModel.isEmpty.value = value
    }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                            savedInstanceState: Bundle?): View {

    return FragmentEmptyStateBinding.inflate(inflater, container, false).also { emptyStateBinding ->
      this.emptyStateBinding = emptyStateBinding
      emptyStateBinding.root.addView(
        super.onCreateView(inflater, emptyStateBinding.root, savedInstanceState)
      )
    }.root
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    // Initialize the detector
    gestureDetector = GestureDetector(requireContext(), gestureListener)

    // Set a non-consuming touch listener on the root ViewFlipper
    emptyStateBinding?.root?.setOnTouchListener { _, event ->
      gestureDetector.onTouchEvent(event)
      // Return false to allow children to handle their own touch events (e.g., scrolling)
      false
    }

    emptyStateViewModel.isEmpty.observe(viewLifecycleOwner) { isEmpty ->
      emptyStateBinding?.apply {
        root.displayedChild = if (isEmpty) 0 else 1
      }
    }

    emptyStateViewModel.emptyMessage.observe(viewLifecycleOwner) { message ->
      emptyStateBinding?.emptyView?.message = message
    }
  }

  override fun onDestroyView() {
    this.emptyStateBinding = null
    super.onDestroyView()
  }

  fun showTooltipDialog(tooltipTag: String) {
    activity?.lifecycleScope?.launch {
      try {
        val tooltipData = getTooltipData(TooltipCategory.CATEGORY_IDE, tooltipTag)
        tooltipData?.let {
          activity?.window?.decorView?.let { anchorView ->
            TooltipUtils.showIDETooltip(
              context = requireContext(),
              anchorView = anchorView,
              level = 0,
              tooltipItem = it
            )
          }
        }
      } catch (e: Exception) {
        Log.e("Tooltip", "Error loading tooltip for $tooltipTag", e)
      }
    }
  }

  suspend fun getTooltipData(category: String, tag: String): IDETooltipItem? {
    return withContext(Dispatchers.IO) {
      TooltipManager.getTooltip(requireContext(), category, tag)
    }
  }

  override fun onResume() {
    super.onResume()
    // Register this fragment to receive events
    EventBus.getDefault().register(this)
  }

  override fun onPause() {
    super.onPause()
    // Unregister to avoid memory leaks
    EventBus.getDefault().unregister(this)
  }

  // This method will be called when an EditorLongPressEvent is posted
  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onEditorLongPressed(event: EditorLongPressEvent) {
    onFragmentLongPressed()
  }
}