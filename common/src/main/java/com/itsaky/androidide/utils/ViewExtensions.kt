package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView

fun View.applyLongPressRecursively(listener: (View) -> Boolean) {

    if (this is ListView) return

    setOnLongClickListener { listener(it) }
    isLongClickable = true
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyLongPressRecursively(listener)
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
fun View.setupGestureHandling(
    onLongPress: (View) -> Unit,
    onDrag: (View) -> Unit
) {
    val handler = Handler(Looper.getMainLooper())
    var isTooltipStarted = false
    var startTime = 0L

    setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTooltipStarted = false
                startTime = System.currentTimeMillis()
                
                // Trigger long press after 800ms
                handler.postDelayed({
                    if (!isTooltipStarted) {
                        isTooltipStarted = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress(view)
                    }
                }, 800)
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacksAndMessages(null)
                val holdDuration = System.currentTimeMillis() - startTime
                
                if (!isTooltipStarted) {
                    if (holdDuration >= 600) {
                        // Medium hold for drag (600-800ms)
                        onDrag(view)
                    } else {
                        view.performClick()
                    }
                }
            }
        }
        true
    }
}