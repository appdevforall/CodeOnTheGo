package com.itsvks.layouteditor.editor.palette.containers

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ScrollView
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.Utils

class ScrollViewDesign @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
  private var drawStrokeEnabled = false
  private var isBlueprint = false

  private val gestureDetector: GestureDetector

  init {
    gestureDetector = GestureDetector(context, ScrollDetector())
  }

  override fun onTouchEvent(ev: MotionEvent): Boolean {
    return gestureDetector.onTouchEvent(ev) || super.onTouchEvent(ev)
  }

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)

    if (drawStrokeEnabled) Utils.drawDashPathStroke(
      this, canvas, if (isBlueprint) Constants.BLUEPRINT_DASH_COLOR else Constants.DESIGN_DASH_COLOR
    )
  }

  override fun draw(canvas: Canvas) {
    if (isBlueprint) Utils.drawDashPathStroke(this, canvas, Constants.BLUEPRINT_DASH_COLOR)
    else super.draw(canvas)
  }

  inner class ScrollDetector : GestureDetector.SimpleOnGestureListener() {
    override fun onScroll(
      e1: MotionEvent?,
      e2: MotionEvent,
      distanceX: Float,
      distanceY: Float
    ): Boolean {
      if (e1 != null) {
        val deltaY = e2.y - e1.y
        val threshold = resources.displayMetrics.heightPixels * 0.2f
        if (deltaY > threshold) {
          smoothScrollTo(0, scrollY + 100)
          return true
        } else if (deltaY < -threshold) {
          smoothScrollTo(0, scrollY - 100)
          return true
        }
      }

      return super.onScroll(e1, e2, distanceX, distanceY)
    }
  }

  fun setStrokeEnabled(enabled: Boolean) {
    drawStrokeEnabled = enabled
    invalidate()
  }

  fun setBlueprint(isBlueprint: Boolean) {
    this.isBlueprint = isBlueprint
    invalidate()
  }
}
