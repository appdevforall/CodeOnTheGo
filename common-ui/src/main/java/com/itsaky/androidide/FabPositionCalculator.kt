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
        val fabMarginPx = layoutParams?.topMargin?.toFloat() ?: SizeUtils.dp2px(16f).toFloat()

        // Get system window insets (status bar, navigation bar, etc.)
        val insets = ViewCompat.getRootWindowInsets(parentView)
        val systemBarsInsets = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
        // Calculate safe minimum Y position
        // Start with system bars top inset (status bar), add a safety margin
        val minY = (systemBarsInsets?.top?.toFloat() ?: 0f) + fabMarginPx

        // Calculate safe bounds
        bounds.left = 0
        bounds.top = minY.toInt()
        bounds.right = (parentView.width - fabView.width).coerceAtLeast(0)
        bounds.bottom = (parentView.height - fabView.height).coerceAtLeast(0)

        return bounds
    }

    /**
     * Validates if the given position is within safe bounds.
     * If not, returns a safe default position (bottom-left with margins).
     */
    fun validateAndCorrectPosition(
        x: Float,
        y: Float,
        parentView: ViewGroup,
        fabView: FloatingActionButton
    ): Pair<Float, Float> {
        val safeBounds = getSafeDraggingBounds(parentView, fabView)
        // Check if position is within safe bounds
        val isXValid = x >= safeBounds.left && x <= safeBounds.right
        val isYValid = y >= safeBounds.top && y <= safeBounds.bottom

        return if (isXValid && isYValid) {
            // Position is valid, return as-is
            x to y
        } else {
            // Get margins from layout params, or use default 16dp if not available
            val layoutParams = fabView.layoutParams as? ViewGroup.MarginLayoutParams
            val marginStart = layoutParams?.marginStart?.toFloat() ?: SizeUtils.dp2px(16f).toFloat()
            val marginBottom = layoutParams?.bottomMargin?.toFloat() ?: SizeUtils.dp2px(16f).toFloat()

            // Position is invalid, return default position (bottom-left)
            val defaultX = marginStart
            val defaultY = parentView.height - fabView.height - marginBottom
            defaultX to defaultY
        }
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
