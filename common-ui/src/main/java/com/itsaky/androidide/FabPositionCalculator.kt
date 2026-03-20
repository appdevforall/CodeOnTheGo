package com.itsaky.androidide

import android.graphics.Rect
import android.view.View
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
        val defaultMargin = SizeUtils.dp2px(16f)
        val margins = resolvePhysicalMargins(parentView, fabView, defaultMargin)

        val insets = ViewCompat.getRootWindowInsets(parentView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())

        val insetLeft = insets?.left ?: 0
        val insetTop = insets?.top ?: 0
        val insetRight = insets?.right ?: 0
        val insetBottom = insets?.bottom ?: 0

        return Rect(
            insetLeft + margins.left,
            insetTop + margins.top,
            (parentView.width - fabView.width - insetRight - margins.right)
                .coerceAtLeast(insetLeft + margins.left),
            (parentView.height - fabView.height - insetBottom - margins.bottom)
                .coerceAtLeast(insetTop + margins.top)
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
        if (availableSpace > 0f) {
            return ((value - min) / availableSpace).coerceIn(0f, 1f)
        }
        return 0f
    }

    fun fromRatio(ratio: Float, min: Int, availableSpace: Float): Float {
        if (availableSpace > 0f) {
            return min + (availableSpace * ratio.coerceIn(0f, 1f))
        }
        return min.toFloat()
    }

    private fun resolvePhysicalMargins(
        parentView: ViewGroup,
        fabView: FloatingActionButton,
        defaultMargin: Int
    ): PhysicalMargins {
        val layoutParams = fabView.layoutParams as? ViewGroup.MarginLayoutParams
            ?: return PhysicalMargins(
                left = defaultMargin,
                top = defaultMargin,
                right = defaultMargin,
                bottom = defaultMargin
            )

        val isRtl = parentView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = layoutParams.marginStart.takeIf { it >= 0 }
        val end = layoutParams.marginEnd.takeIf { it >= 0 }

        val (resolvedLeft, resolvedRight) = if (isRtl) {
            end to start
        } else {
            start to end
        }

        return PhysicalMargins(
            left = resolvedLeft ?: layoutParams.leftMargin,
            top = layoutParams.topMargin,
            right = resolvedRight ?: layoutParams.rightMargin,
            bottom = layoutParams.bottomMargin
        )
    }

    private data class PhysicalMargins(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
