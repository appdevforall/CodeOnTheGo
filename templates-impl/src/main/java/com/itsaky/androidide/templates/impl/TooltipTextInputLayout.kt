package com.itsaky.androidide.templates.impl

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.google.android.material.textfield.TextInputLayout

/**
 * A custom TextInputLayout that uses a GestureDetector to reliably detect
 * a long-press gesture anywhere within its bounds, without interfering
 * with child views like the TextInputEditText.
 */
class TooltipTextInputLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextInputLayout(context, attrs, defStyleAttr) {

    // A callback to be triggered when a long press is detected.
    var onLongPress: ((View) -> Unit)? = null

    // The standard Android gesture detector.
    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // When a long press is detected, invoke our callback.
                onLongPress?.invoke(this@TooltipTextInputLayout)
            }
        })

    /**
     * This is the key method. It's called for all touch events before they are dispatched
     * to any child views.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // We feed every touch event to our gesture detector.
        gestureDetector.onTouchEvent(ev)

        // CRUCIAL: We return 'false' to indicate that we have NOT consumed the event.
        // This allows the touch event to continue its normal journey to child views
        // (like the TextInputEditText), so that tapping to type still works perfectly.
        return false
    }
}