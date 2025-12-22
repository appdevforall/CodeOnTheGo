package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import com.google.android.material.appbar.MaterialToolbar

/**
 * A MaterialToolbar that removes all internal padding to allow precise control
 * of spacing. This is used for the title toolbar to eliminate wasted vertical space.
 */
class CompactMaterialToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialToolbar(context, attrs, defStyleAttr) {

    init {
        // Remove all padding
        setPadding(0, 0, 0, 0)
        setContentInsetsRelative(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Let the toolbar size itself naturally based on its children
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Ensure padding is still 0 after layout
        setPadding(0, 0, 0, 0)
        setContentInsetsRelative(0, 0)
    }
}
