package com.itsaky.androidide.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.itsaky.androidide.databinding.FragmentEmptyStateBinding
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.EmptyStateFragmentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class EmptyStateFragment<T : ViewBinding> : FragmentWithBinding<T> {
	constructor(layout: Int, bind: (View) -> T) : super(layout, bind)
	constructor(inflate: (LayoutInflater, ViewGroup?, Boolean) -> T) : super(inflate)

	protected var emptyStateBinding: FragmentEmptyStateBinding? = null
		private set

	protected val emptyStateViewModel by viewModels<EmptyStateFragmentViewModel>()

	private var gestureDetector: GestureDetector? = null

	// Cache the last known empty state to avoid returning incorrect default when detached
	// Volatile ensures thread-safe visibility and atomicity for boolean reads/writes
	@Volatile
	private var cachedIsEmpty: Boolean = true

	@Volatile
	private var cachedIsSourceEmpty: Boolean = true

	open val currentEditor: IDEEditor? get() = null

	/**
	 * Called when a long press is detected on the fragment's root view.
	 * Subclasses must implement this to define the action (e.g., show a tooltip).
	 */
	open fun onFragmentLongPressed(
		x: Float = -1f,
		y: Float = -1f,
	) {
		currentEditor?.let { editor ->
			if (x >= 0 && y >= 0) {
				editor.setSelectionFromPoint(x, y)
			}
		}
		onFragmentLongPressed()
	}

	open fun onFragmentLongPressed() {
		val currentEditor = currentEditor ?: return
		currentEditor.selectWordOrOperatorAtCursor()
	}

	private val gestureListener =
		object : GestureDetector.SimpleOnGestureListener() {
			override fun onLongPress(e: MotionEvent) {
				if (currentEditor?.isReadOnlyContext == true) return
				onFragmentLongPressed(e.x, e.y)
			}
		}

	/**
	 * Whether the underlying data source has no content at all, independent of any filter UI.
	 * This is the signal to gate content-dependent actions (share, clear, search, filter) on;
	 * [isEmpty] only says which layout the [android.widget.ViewFlipper] shows.
	 */
	internal val isSourceEmptyFlow: StateFlow<Boolean>?
		get() {
			return if (isAdded && !isDetached) {
				emptyStateViewModel.isSourceEmpty
			} else {
				null
			}
		}

	internal val isSourceEmpty: Boolean
		get() {
			return if (isAdded && !isDetached) {
				emptyStateViewModel.isSourceEmpty.value.also { cachedIsSourceEmpty = it }
			} else {
				cachedIsSourceEmpty
			}
		}

	internal var isEmpty: Boolean
		get() {
			return if (isAdded && !isDetached) {
				// Update cache when attached and return current value
				emptyStateViewModel.isEmpty.value.also { cachedIsEmpty = it }
			} else {
				// Return cached value when detached to avoid UI inconsistencies
				cachedIsEmpty
			}
		}
		set(value) {
			// Always update cache to preserve intended state even when detached
			cachedIsEmpty = value
			cachedIsSourceEmpty = value
			// Update ViewModel only when attached
			if (isAdded && !isDetached) {
				emptyStateViewModel.setEmpty(value)
			}
		}

	/**
	 * Centralized empty-state updater for log and output fragments.
	 *
	 * Empty state is set to `true` only when the underlying data source has no content AT ALL
	 * and no filter / filter bar is active. When an active filter query returns zero matches for
	 * non-empty source history, empty state remains `false` so the content layout (with the filter bar)
	 * stays visible. [isSourceEmpty] is tracked separately so action buttons can still be gated
	 * on actual content.
	 */
	fun updateEmptyState(
		isSourceEmpty: Boolean,
		isFilterActive: Boolean,
	) {
		val isEmpty = isSourceEmpty && !isFilterActive
		cachedIsEmpty = isEmpty
		cachedIsSourceEmpty = isSourceEmpty
		if (isAdded && !isDetached) {
			emptyStateViewModel.setEmptyState(isEmpty = isEmpty, isSourceEmpty = isSourceEmpty)
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		FragmentEmptyStateBinding
			.inflate(inflater, container, false)
			.also { emptyStateBinding ->
				this.emptyStateBinding = emptyStateBinding
				emptyStateBinding.root.addView(
					super.onCreateView(inflater, emptyStateBinding.root, savedInstanceState),
				)
			}.root

	@SuppressLint("ClickableViewAccessibility")
	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		gestureDetector = GestureDetector(requireContext(), gestureListener)

		// Set a non-consuming touch listener on the root ViewFlipper
		emptyStateBinding?.root?.setOnTouchListener { _, event ->
			gestureDetector?.onTouchEvent(event)
			// Return false to allow children to handle their own touch events (e.g., scrolling)
			false
		}

		// Sync ViewModel with cache when view is created (in case cache was updated while detached)
		// Read cached values into local variables to ensure atomic reads
		val cachedValue = cachedIsEmpty
		val cachedSourceValue = cachedIsSourceEmpty
		if (emptyStateViewModel.isEmpty.value != cachedValue ||
			emptyStateViewModel.isSourceEmpty.value != cachedSourceValue
		) {
			emptyStateViewModel.setEmptyState(isEmpty = cachedValue, isSourceEmpty = cachedSourceValue)
		}

		viewLifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					emptyStateViewModel.isEmpty.collectLatest { isEmpty ->
						withContext(Dispatchers.Main.immediate) {
							cachedIsEmpty = isEmpty
							emptyStateBinding?.root?.displayedChild = if (isEmpty) 0 else 1
						}
					}
				}
				launch {
					emptyStateViewModel.emptyMessage.collect { message ->
						withContext(Dispatchers.Main.immediate) {
							emptyStateBinding?.emptyView?.message = message
						}
					}
				}
			}
		}
	}

	override fun onDestroyView() {
		this.emptyStateBinding = null
		gestureDetector = null
		super.onDestroyView()
	}

	fun showTooltipDialog(tooltipTag: String) {
		val anchorView = activity?.window?.decorView ?: return
		TooltipManager.showIdeCategoryTooltip(
			context = requireContext(),
			anchorView = anchorView,
			tag = tooltipTag,
		)
	}
}
