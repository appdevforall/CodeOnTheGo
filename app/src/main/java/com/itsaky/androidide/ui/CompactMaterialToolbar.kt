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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Let the toolbar size itself naturally based on its children
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
