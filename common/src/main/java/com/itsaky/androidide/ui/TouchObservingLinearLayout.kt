package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

class TouchObservingLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onTouchEventObserved: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onTouchEventObserved?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
