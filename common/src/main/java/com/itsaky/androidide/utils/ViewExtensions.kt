package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.slider.Slider
import kotlin.math.abs

/**
 * Traverses a view hierarchy and applies a given action to each view.
 * @param action The lambda to execute for each view in the hierarchy.
 */
fun View.forEachViewRecursively(action: (View) -> Unit) {
    action(this)
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).forEachViewRecursively(action)
        }
    }
}

fun View.applyLongPressRecursively(listener: (View) -> Boolean) {
    if (this is ListView) return

    setOnLongClickListener { listener(it) }
    isLongClickable = true
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val currentView = getChildAt(i)
            if (currentView is Slider) {
                currentView.setupGestureHandling(
                    onLongPress = { view -> listener(view) },
                    onDrag = {}
                )
            } else {
                currentView.applyLongPressRecursively(listener)
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
fun View.setupGestureHandling(
    onLongPress: (View) -> Unit,
    onDrag: (View) -> Unit
) {
    val handler = Handler(Looper.getMainLooper())
    var isDragging = false
    var longPressFired = false
    val touchSlop = ViewConfiguration.get(this.context).scaledTouchSlop
    var downX = 0f
    var downY = 0f

    val longPressRunnable = Runnable {
        if (!isDragging) {
            longPressFired = true
            this.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress(this)
        }
    }

    this.setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                longPressFired = false
                downX = event.x
                downY = event.y

                view.parent.requestDisallowInterceptTouchEvent(true)

                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    isDragging = true
                    handler.removeCallbacks(longPressRunnable)
                    onDrag(view)
                }
                isDragging
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.parent.requestDisallowInterceptTouchEvent(false)

                val wasDragging = isDragging
                val wasLongPressed = longPressFired


                if (!wasDragging && !wasLongPressed) {
                    view.performClick()
                }

                handler.removeCallbacks(longPressRunnable)
                isDragging = false
                longPressFired = false
                true
            }

            else -> false
        }
    }
}

/**
 * Sets up a long-press listener on an AlertDialog's decor view to show a tooltip.
 *
 * This extension function allows an AlertDialog to display a tooltip when its content area
 * is long-pressed. It works by recursively attaching a long-press listener to the
 * dialog's decor view and all its children.
 *
 * @param listener A lambda function that will be invoked when a long-press event occurs.
 *                 The lambda receives the [View] that was long-pressed as its argument
 *                 and should return `true` if the listener has consumed the event, `false` otherwise.
 */
fun AlertDialog.onLongPress(listener: (View) -> Boolean) {
    this.setOnShowListener {
        this.window?.decorView?.applyLongPressRecursively(listener)
    }
}


@SuppressLint("ClickableViewAccessibility")
fun View.handleLongClicksAndDrag(
    onLongPress: (View) -> Unit,
    onDrag: (View) -> Unit
) {
    var viewInitialX = 0f
    var viewInitialY = 0f
    var touchInitialRawX = 0f
    var touchInitialRawY = 0f

    var isDragging = false
    var longPressFired = false 

    val handler = Handler(Looper.getMainLooper())
    val longPressTimeout = 800L 

    val longPressRunnable = Runnable {
        if (!isDragging) { 
            longPressFired = true
            this.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress(this) 
        }
    }

    val touchSlop = ViewConfiguration.get(this.context).scaledTouchSlop

    this.setOnTouchListener { view, event -> 
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                longPressFired = false

                touchInitialRawX = event.rawX
                touchInitialRawY = event.rawY
                viewInitialX = view.x 
                viewInitialY = view.y
                
                handler.postDelayed(longPressRunnable, longPressTimeout)
                true 
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = abs(event.rawX - touchInitialRawX)
                val deltaY = abs(event.rawY - touchInitialRawY)

                if (isDragging || deltaX > touchSlop || deltaY > touchSlop) {
                    if (!isDragging && !longPressFired) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    isDragging = true 
                    
                    val newX = viewInitialX + (event.rawX - touchInitialRawX)
                    val newY = viewInitialY + (event.rawY - touchInitialRawY)
                    view.x = newX
                    view.y = newY
                }
                true 
            }
            
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable) 

                val wasDraggingDuringGesture = isDragging
                val wasLongPressFiredDuringGesture = longPressFired
                
                isDragging = false
                longPressFired = false 

                if (wasDraggingDuringGesture) {
                    onDrag(view) 
                    return@setOnTouchListener true
                }
                
                if (wasLongPressFiredDuringGesture) {
                    return@setOnTouchListener true
                }
                
                view.performClick()
                return@setOnTouchListener true
            }
            
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                isDragging = false
                longPressFired = false
                return@setOnTouchListener true
            }
            else -> false
        }
    }
}

