package com.itsaky.androidide

import android.graphics.Rect
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.blankj.utilcode.util.SizeUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton

internal class FabPositionCalculator {

    /**
     * Calculate safe bounds for FAB positioning, accounting for system UI elements.
     * Returns a Rect with the safe dragging area (left, top, right, bottom).
     */
    fun getSafeDraggingBounds(parentView: ViewGroup, fabView: FloatingActionButton): Rect {
        val bounds = Rect()
        // Get margin from layout params, or use default 16dp if not available
        val layoutParams = fabView.layoutParams as? ViewGroup.MarginLayoutParams
        val defaultMargin = SizeUtils.dp2px(16f)

        val marginStart = layoutParams?.marginStart ?: defaultMargin
        val marginTop = layoutParams?.topMargin ?: defaultMargin
        val marginEnd = layoutParams?.marginEnd ?: defaultMargin
        val marginBottom = layoutParams?.bottomMargin ?: defaultMargin

        // Get system window insets (status bar, navigation bar, etc.)
        val insets = ViewCompat.getRootWindowInsets(parentView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())

        val insetLeft = insets?.left ?: 0
        val insetTop = insets?.top ?: 0
        val insetRight = insets?.right ?: 0
        val insetBottom = insets?.bottom ?: 0

        // Calculate safe bounds
        return Rect(
            insetLeft + marginStart,
            insetTop + marginTop,
            (parentView.width - fabView.width - insetRight - marginEnd).coerceAtLeast(insetLeft + marginStart),
            (parentView.height - fabView.height - insetBottom - marginBottom).coerceAtLeast(insetTop + marginTop)
        )
    }

    /**
     * Validates if the given position is within safe bounds.
     * If not, clamps it to the nearest valid position within the safe area.
     */
    fun validateAndCorrectPosition(
        x: Float,
        y: Float,
        parentView: ViewGroup,
        fabView: FloatingActionButton
    ): Pair<Float, Float> {
        val safeBounds = getSafeDraggingBounds(parentView, fabView)

        val correctedX = x.coerceIn(safeBounds.left.toFloat(), safeBounds.right.toFloat())
        val correctedY = y.coerceIn(safeBounds.top.toFloat(), safeBounds.bottom.toFloat())

        return correctedX to correctedY
    }

    fun toRatio(value: Float, min: Int, availableSpace: Float): Float {
        return if (availableSpace > 0f) {
            ((value - min) / availableSpace).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    fun fromRatio(ratio: Float, min: Int, availableSpace: Float): Float {
        return if (availableSpace > 0f) {
            min + (availableSpace * ratio.coerceIn(0f, 1f))
        } else {
            min.toFloat()
        }
    }
}
