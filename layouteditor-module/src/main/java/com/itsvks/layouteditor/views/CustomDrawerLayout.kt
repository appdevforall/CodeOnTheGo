package com.itsvks.layouteditor.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.drawerlayout.widget.DrawerLayout

/** Allows horizontally scrolling child of drawer layouts to intercept the touch event.  */
class CustomDrawerLayout : DrawerLayout {
  private val rect = Rect()

  constructor(context: Context) : super(context)

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

  constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
    context,
    attrs,
    defStyleAttr
  )

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    val scrollingChild = findScrollingChild(this, ev.x, ev.y)
    if (scrollingChild != null) {
      return false
    }
    return super.onInterceptTouchEvent(ev)
  }

  /**
   * Recursively finds the view that can scroll horizontally to the end
   *
   * @param parent The starting parent to search
   * @param x The x point in the screen
   * @param y The y point in the screen
   * @return The scrolling view, null if no view is found
   */
  private fun findScrollingChild(parent: ViewGroup, x: Float, y: Float): View? {
    val n = parent.childCount
    if (parent === this && n <= 1) {
      return null
    }

    var start = 0
    if (parent === this) {
      start = 1
    }

    for (i in start until n) {
      val child = parent.getChildAt(i)
      if (child.visibility != VISIBLE) {
        continue
      }
      child.getHitRect(rect)
      if (rect.contains(x.toInt(), y.toInt())) {
        if (child.canScrollHorizontally(1)) {
          return child
        } else if (child is ViewGroup) {
          val v = findScrollingChild(child, x - rect.left, y - rect.top)
          if (v != null) {
            return v
          }
        }
      }
    }
    return null
  }
}
