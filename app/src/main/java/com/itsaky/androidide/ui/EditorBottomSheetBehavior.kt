package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

class EditorBottomSheetBehavior<V : View>(
	context: Context,
	attrs: AttributeSet?,
) : BottomSheetBehavior<V>(context, attrs) {
	private var gestureStartedInTopPadding = false

	override fun onInterceptTouchEvent(
		parent: CoordinatorLayout,
		child: V,
		event: MotionEvent,
	): Boolean = !isGestureInTopPadding(child, event) && super.onInterceptTouchEvent(parent, child, event)

	override fun onTouchEvent(
		parent: CoordinatorLayout,
		child: V,
		event: MotionEvent,
	): Boolean = !isGestureInTopPadding(child, event) && super.onTouchEvent(parent, child, event)

	private fun isGestureInTopPadding(
		child: V,
		event: MotionEvent,
	): Boolean {
		if (event.actionMasked == MotionEvent.ACTION_DOWN) {
			gestureStartedInTopPadding = event.y < child.top + child.paddingTop
		}
		return gestureStartedInTopPadding
	}
}
