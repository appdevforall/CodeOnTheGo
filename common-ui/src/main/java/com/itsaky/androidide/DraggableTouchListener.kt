package com.itsaky.androidide

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.sqrt

internal class DraggableTouchListener(
    context: Context,
    private val calculator: FabPositionCalculator,
    private val onSavePosition: (x: Float, y: Float) -> Unit,
    private val onShowTooltip: () -> Unit
) : View.OnTouchListener {

    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isLongPressed = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!isDragging) {
                    isLongPressed = true
                    onShowTooltip()
                }
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val parentView = v.parent as? ViewGroup ?: return false
        val fab = v as? FloatingActionButton ?: return false

        gestureDetector.onTouchEvent(event)

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> handleActionDown(fab, event)
            MotionEvent.ACTION_MOVE -> handleActionMove(fab, parentView, event)
            MotionEvent.ACTION_UP -> handleActionUp(fab)
            MotionEvent.ACTION_CANCEL -> handleActionCancel()
            else -> false
        }
    }

    private fun handleActionDown(fab: FloatingActionButton, event: MotionEvent): Boolean {
        initialX = fab.x
        initialY = fab.y
        initialTouchX = event.rawX
        initialTouchY = event.rawY
        isDragging = false
        isLongPressed = false
        return true
    }

    private fun handleActionMove(fab: FloatingActionButton, parentView: ViewGroup, event: MotionEvent): Boolean {
        val dX = event.rawX - initialTouchX
        val dY = event.rawY - initialTouchY

        if (!isDragging && isDraggingThresholdReached(dX, dY)) {
            isDragging = true
        }

        if (isDragging) {
            val safeBounds = calculator.getSafeDraggingBounds(parentView, fab)
            fab.x = (initialX + dX).coerceIn(safeBounds.left.toFloat(), safeBounds.right.toFloat())
            fab.y = (initialY + dY).coerceIn(safeBounds.top.toFloat(), safeBounds.bottom.toFloat())
        }
        return true
    }

    private fun handleActionUp(fab: FloatingActionButton): Boolean {
        if (isDragging) {
            onSavePosition(fab.x, fab.y)
        } else if (!isLongPressed) {
            fab.performClick()
        }
        return true
    }

    private fun handleActionCancel(): Boolean {
        isDragging = false
        isLongPressed = false
        return true
    }

    private fun isDraggingThresholdReached(dX: Float, dY: Float): Boolean {
        return sqrt((dX * dX + dY * dY).toDouble()) > touchSlop
    }
}
