/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.annotation.FloatRange
import androidx.annotation.IdRes
import androidx.customview.widget.ViewDragHelper
import com.google.android.material.shape.MaterialShapeDrawable
import com.itsaky.androidide.R
import kotlin.math.max
import kotlin.math.min

/**
 * A layout which can be dragged vertically to reveal a hidden content.
 *
 * @author Akash Yadav
 */
open class SwipeRevealLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
  defStyleRes: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr, defStyleRes) {

  /**
   * Interface for listening to drag events.
   */
  interface OnDragListener {

    /**
     * Called when the drag state changes.
     */
    fun onDragStateChanged(swipeRevealLayout: SwipeRevealLayout, state: Int)

    /**
     * Called when the drag progress changes.
     */
    fun onDragProgress(swipeRevealLayout: SwipeRevealLayout, progress: Float)
  }

  private val leftDragHelper: ViewDragHelper
  private val rightDragHelper: ViewDragHelper

  private var leftDragProgress = 0f
  private var rightDragProgress = 0f

  init {
    leftDragHelper = ViewDragHelper.create(this, 1f, LeftDragCallback())
    rightDragHelper = ViewDragHelper.create(this, 1f, RightDragCallback())
  }

  private val dragHelperCallback = object : ViewDragHelper.Callback() {
    override fun tryCaptureView(child: View, pointerId: Int): Boolean {
      return child === overlappingContent
    }

    override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
      draggingViewTop = top
      onDragProgress(min(1f, top.toFloat() / dragHeightMax.toFloat()))
    }

    override fun getViewVerticalDragRange(child: View): Int {
      return dragHeightMax
    }

    override fun getOrderedChildIndex(index: Int): Int {
      return OVERLAPPING_CONTENT_INDEX
    }

    override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
      return min(max(top, paddingTop), dragHeightMax)
    }

    override fun onViewDragStateChanged(state: Int) {
      if (state == draggingState) {
        return
      }

      if (isDragging && state == ViewDragHelper.STATE_IDLE) {
        isOpen = draggingViewTop >= dragHeightMax
      }

      onDragStateChanged(state)
    }

    override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
      if (draggingViewTop == 0) {
        isOpen = false
        return
      }

      if (draggingViewTop >= dragHeightMax) {
        isOpen = true
        return
      }

      // whether the view should settle to open or close
      val settleDestY = if (yvel > AUTO_OPEN_VELOCITY_LIM || draggingViewTop > dragHeightMax / 2) {
        dragHeightMax
      } else {
        paddingTop
      }

      if (dragHelper.settleCapturedViewAt(0, settleDestY)) {
        this@SwipeRevealLayout.postInvalidateOnAnimation()
      }
    }
  }

  private val hiddenContent: View
    get() = getChildAt(HIDDEN_CONTENT_INDEX)!!

  private val overlappingContent: View
    get() = getChildAt(OVERLAPPING_CONTENT_INDEX)!!

  private var draggingState = -1
  private var draggingViewTop = 0
  private val dragHeightMax
    get() = hiddenContent.height

  private lateinit var dragHelper: ViewDragHelper

  /**
   * Whether the view is currently in 'dragging' state.
   */
  val isDragging: Boolean
    get() = draggingState == ViewDragHelper.STATE_DRAGGING ||
            draggingState == ViewDragHelper.STATE_SETTLING

  /**
   * The ID of the view which will be dragged to reveal the content.
   */
  @IdRes
  var dragHandleViewId = 0

  /**
   * The current drag progress.
   */
  @FloatRange(from = 0.0, to = 1.0)
  var dragProgress = 0.0f
    private set

  /**
   * Listener for drag events.
   */
  var dragListener: OnDragListener? = null

  /**
   * Whether the view is open.
   */
  var isOpen = false
    protected set

  companion object {

    private const val HIDDEN_CONTENT_INDEX = 0
    private const val OVERLAPPING_CONTENT_INDEX = 1

    @Suppress("UNUSED")
    const val STATE_IDLE = ViewDragHelper.STATE_IDLE

    @Suppress("UNUSED")
    const val STATE_DRAGGING = ViewDragHelper.STATE_DRAGGING

    @Suppress("UNUSED")
    const val STATE_SETTLING = ViewDragHelper.STATE_SETTLING

    const val AUTO_OPEN_VELOCITY_LIM = 800.0
  }

  init {
    if (attrs != null) {
      val typedArray = context.obtainStyledAttributes(attrs, R.styleable.SwipeRevealLayout,
        defStyleAttr, defStyleRes)
      dragHandleViewId = typedArray.getResourceId(R.styleable.SwipeRevealLayout_dragHandle,
        dragHandleViewId)
      typedArray.recycle()
    }
  }

  override fun onFinishInflate() {
    super.onFinishInflate()
    this.dragHelper = ViewDragHelper.create(this, dragHelperCallback)
    this.isOpen = false

    check(childCount == 2) {
      "SwipeRevealLayout must have exactly two children; the hidden content and the overlapping content"
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    measureChildren(widthMeasureSpec, heightMeasureSpec)

    val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
    val maxHeight = MeasureSpec.getSize(heightMeasureSpec)

    setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, 0),
      resolveSizeAndState(maxHeight, heightMeasureSpec, 0))
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    hiddenContent.layout(0, paddingTop, r, paddingTop + hiddenContent.measuredHeight)

    val olapTop = paddingTop + (hiddenContent.height * dragProgress).toInt()
    overlappingContent.layout(0, olapTop, r, b)
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    val action = ev.actionMasked
    if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
      leftDragHelper.cancel()
      rightDragHelper.cancel()
      return false
    }
    val isLeft = leftDragHelper.shouldInterceptTouchEvent(ev)
    val isRight = rightDragHelper.shouldInterceptTouchEvent(ev)
    return isLeft || isRight
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    leftDragHelper.processTouchEvent(event)
    rightDragHelper.processTouchEvent(event)
    return true
  }

  override fun computeScroll() {
    if (leftDragHelper.continueSettling(true) or rightDragHelper.continueSettling(true)) {
      postInvalidateOnAnimation()
    }
  }

  /**
   * Internal callback. Invoked when the drag state changes.
   */
  @CallSuper
  protected open fun onDragStateChanged(state: Int) {
    draggingState = state
    dragListener?.onDragStateChanged(this, state)
  }

  /**
   * Internal callback. Invoked when the drag progress changes.
   */
  @CallSuper
  protected open fun onDragProgress(progress: Float) {
    if (dragProgress == progress) {
      return
    }

    dragProgress = progress
    applyDragProgress(progress)
    dragListener?.onDragProgress(this, progress)
  }

  /**
   * Applies the drag progress to the content.
   */
  protected open fun applyDragProgress(progress: Float) {
    val min = 0.97f
    val max = 1f
    val scale = min + (max - min) * (1 - progress)
    overlappingContent.scaleX = scale
    overlappingContent.scaleY = scale
    (overlappingContent.background as? MaterialShapeDrawable?)?.interpolation = progress
  }

  /**
   * Toggles the state of the view.
   */
  fun toggleState(isOpen: Boolean) {
    if (isOpen) {
      open()
    } else {
      close()
    }
  }

  /**
   * Opens the view.
   */
  fun open() {
    if (isOpen) {
      return
    }
    smoothSlideTo(1f)
  }

  /**
   * Closes the view.
   */
  fun close() {
    if (!isOpen) {
      return
    }

    smoothSlideTo(0f)
  }

  private fun smoothSlideTo(offset: Float) {
    val y = paddingTop + offset * dragHeightMax
    if (dragHelper.smoothSlideViewTo(overlappingContent, overlappingContent.left, y.toInt())) {
      postInvalidateOnAnimation()
    }
  }

  private inner class LeftDragCallback : ViewDragHelper.Callback() {
    override fun tryCaptureView(child: View, pointerId: Int): Boolean {
      return child.id == R.id.drawer_sidebar // Your left drawer ID
    }

    override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
      leftDragProgress = left.toFloat() / changedView.width
      dragListener?.onDragProgress(this@SwipeRevealLayout, leftDragProgress)
      invalidate()
    }

    override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
      return max(0, min(left, width - child.width))
    }
  }

  private inner class RightDragCallback : ViewDragHelper.Callback() {
    override fun tryCaptureView(child: View, pointerId: Int): Boolean {
      return child.id == R.id.right_drawer_sidebar
    }

    override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
      rightDragProgress = (width - left).toFloat() / changedView.width
      dragListener?.onDragProgress(this@SwipeRevealLayout, rightDragProgress)
      invalidate()
    }

    override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
      return max(width - child.width, min(left, width))
    }
  }
}