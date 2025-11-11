package org.appdevforall.codeonthego.layouteditor.editor.positioning

import android.view.View
import android.view.ViewGroup
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap

/**
 * A set of all positioning-related attributes that should be cleared
 * before applying new ones to prevent layout conflicts.
 */
internal val POSITIONING_KEYS_TO_REMOVE = setOf(
    // ConstraintLayout
    "app:layout_constraintBottom_toBottomOf", "app:layout_constraintEnd_toEndOf",
    "app:layout_constraintStart_toStartOf", "app:layout_constraintTop_toTopOf",
    "app:layout_marginStart", "app:layout_marginTop", "app:layout_marginEnd", "app:layout_marginBottom",

    // GridLayout
    "android:layout_row",
    "android:layout_column",
    "android:layout_rowSpan",
    "android:layout_columnSpan",
    "android:layout_gravity",

    // FrameLayout and GridLayout
    "android:layout_gravity",

    // RelativeLayout
    "android:layout_alignParentStart", "android:layout_alignParentTop",
    "android:layout_alignParentEnd", "android:layout_alignParentBottom",

    // Most common
    "android:layout_marginLeft", "android:layout_marginTop",
    "android:layout_marginEnd", "android:layout_marginBottom"
)

/**
 * Holds calculated X and Y coordinates in Density-Independent Pixels (dp).
 */
data class DpCoordinates(val xDp: Float, val yDp: Float)

/**
 * Calculates the final (x, y) coordinates in Dp for the dropped view.
 * This function clamps the pixel values to ensure the [child] view remains
 * entirely within the [container] bounds, then converts the clamped
 * pixel values to Dp.
 *
 * @param container The parent ViewGroup.
 * @param child The view being dropped.
 * @param x The raw X coordinate of the drop in pixels.
 * @param y The raw Y coordinate of the drop in pixels.
 * @param density The screen density for px-to-dp conversion.
 * @return A [DpCoordinates] object holding the final, safe coordinates in Dp.
 */
internal fun calculateDropCoordinatesInDp(
    container: ViewGroup,
    child: View,
    x: Float,
    y: Float,
    density: Float
): DpCoordinates {
    val maxX = (container.width - child.width).coerceAtLeast(0).toFloat()
    val maxY = (container.height - child.height).coerceAtLeast(0).toFloat()

    val xPx = x.coerceIn(0f, maxX)
    val yPx = y.coerceIn(0f, maxY)

    return DpCoordinates(
        xDp = xPx / density,
        yDp = yPx / density
    )
}

/**
 * Clears all known positioning attributes from the [attributes] map.
 * This is crucial to prevent conflicts, e.g., having both
 * `app:layout_marginStart` (from ConstraintLayout) and
 * `android:layout_marginLeft` (from RelativeLayout) defined at the same time.
 *
 * @param attributes The AttributeMap for the view to be cleaned.
 */
internal fun clearPositioningAttributes(attributes: AttributeMap) {
    POSITIONING_KEYS_TO_REMOVE.forEach { key ->
        if (attributes.contains(key)) {
            attributes.removeValue(key)
        }
    }
}

/**
 * Helper class to store a set of constraint changes for a single view.
 */
data class ViewConstraintChange(
    val viewId: Int,
    val startMargin: Int,
    val topMargin: Int
)

/**
 * Converts a dimension string (e.g., `"12px"`, `"8dp"`, `"10dip"`, or `"14"`)
 * into pixels using the given [density].
 *
 * Supported suffixes:
 * - `"px"` → interpreted as raw pixels.
 * - `"dp"` or `"dip"` → multiplied by display density.
 * - No suffix → assumed to be dp and multiplied by density.
 *
 * @receiver The dimension string to convert.
 * @param density The display density for dp-to-px conversion.
 * @return The equivalent pixel value, or `0f` if parsing fails.
 */
internal fun String.toPx(density: Float): Float {
    val value = trim().lowercase()
    val number = when {
        value.endsWith("px") -> value.removeSuffix("px").toFloatOrNull()
        value.endsWith("dp") -> value.removeSuffix("dp").toFloatOrNull()?.times(density)
        value.endsWith("dip") -> value.removeSuffix("dip").toFloatOrNull()?.times(density)
        else -> value.toFloatOrNull()?.times(density)
    }

    return number?.takeIf { it.isFinite() } ?: 0f
}


/**
 * Converts dimension strings (like "10dp" or "100px") into final, safe pixel values.
 *
 * This utility function performs two key actions:
 * 1.  Converts the string dimensions to pixels using [toPx].
 * 2.  Clamps the resulting pixel values to ensure the [view] remains
 * entirely within the bounds of the [container].
 *
 * @param container The parent [ViewGroup] used to determine bounds.
 * @param view The child [View] used to determine bounds.
 * @param txStr The horizontal dimension string (e.g., `layout_marginStart`).
 * @param tyStr The vertical dimension string (e.g., `layout_marginTop`).
 * @param density The display density for dp-to-px conversion.
 * @return A [Pair] containing the clamped (X, Y) pixel values.
 */
internal fun calculateClampedPx(
	container: ViewGroup, view: View, txStr: String, tyStr: String, density: Float
): Pair<Float, Float> {
    val maxX = (container.width - view.width).coerceAtLeast(0).toFloat()
    val maxY = (container.height - view.height).coerceAtLeast(0).toFloat()
    val txPx = txStr.toPx(density).coerceIn(0f, maxX)
    val tyPx = tyStr.toPx(density).coerceIn(0f, maxY)
    return Pair(txPx, tyPx)
}
