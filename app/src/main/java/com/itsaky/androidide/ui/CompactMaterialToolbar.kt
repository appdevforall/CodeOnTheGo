package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
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

    /**
     * Measures the toolbar and, when a title TextView is present, constrains the toolbar's height to match that TextView's measured height.
     *
     * If no TextView child is found the measured dimensions remain as determined by the superclass.
     *
     * @param widthMeasureSpec horizontal space requirements as imposed by the parent.
     * @param heightMeasureSpec vertical space requirements as imposed by the parent.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // First, measure children to get their desired height
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        // Find the TextView child (title_text)
        var textViewHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is TextView) {
                textViewHeight = child.measuredHeight
                break
            }
        }
        
        // If we found a TextView, force the toolbar height to match it exactly
        if (textViewHeight > 0) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, textViewHeight)
        }
    }

    /**
     * Reapplies zero padding and content insets, and aligns the navigation icon's top edge with the toolbar title's top.
     *
     * If both a title TextView and the navigation ImageButton (identified by matching `navigationContentDescription`) are present,
     * the navigation button is repositioned so its top equals the title's top while preserving its measured height.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Ensure padding is still 0 after layout
        setPadding(0, 0, 0, 0)
        setContentInsetsRelative(0, 0)
        
        // Find the TextView and navigation icon, then align them at the top
        var textView: TextView? = null
        var navButton: View? = null
        
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            when {
                child is TextView -> textView = child
                child is android.widget.ImageButton && child.contentDescription == navigationContentDescription -> navButton = child
            }
        }
        
        // If we have both, align the navigation icon with the TextView at the top
        if (textView != null && navButton != null) {
            val textViewTop = textView.top
            val navButtonHeight = navButton.height
            navButton.layout(
                navButton.left,
                textViewTop,
                navButton.right,
                textViewTop + navButtonHeight
            )
        }
    }
}
