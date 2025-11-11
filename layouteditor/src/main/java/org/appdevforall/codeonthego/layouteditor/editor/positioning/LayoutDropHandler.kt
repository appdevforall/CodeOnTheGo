package org.appdevforall.codeonthego.layouteditor.editor.positioning

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap


/**
 * Updates the stored attributes for a [child] view after it is "dropped"
 * at a new position (x, y) within its parent [ViewGroup].
 *
 * This function orchestrates the process of:
 * 1. Calculating the final, clamped coordinates in Dp.
 * 2. Clearing any previous positioning attributes to prevent conflicts.
 * 3. Applying the correct new attributes based on the parent's layout type.
 *
 * @param child The view being positioned.
 * @param x The raw target X coordinate in container pixels.
 * @param y The raw target Y coordinate in container pixels.
 */
fun positionAtDrop(child: View, x: Float, y: Float, viewAttributeMap: HashMap<View, AttributeMap>) {
    val container = child.parent as? ViewGroup ?: return

    val density = container.resources.displayMetrics.density
    val coords = calculateDropCoordinatesInDp(container, child, x, y, density)

    val attributes = viewAttributeMap[child] ?: return

    clearPositioningAttributes(attributes)

    applyLayoutAttributes(container, child, attributes, coords, x, y)
}

/**
 * Acts as a "dynamic mapper" or "strategy" function.
 * It detects the type of the [container] and calls the appropriate
 * helper function to apply layout-specific attributes.
 *
 * @param container The parent ViewGroup.
 * @param attributes The AttributeMap for the child view.
 * @param coords The final Dp coordinates to apply.
 */
internal fun applyLayoutAttributes(
    container: ViewGroup,
    child: View,
    attributes: AttributeMap,
    coords: DpCoordinates,
    x: Float,
    y: Float
) {
    when (container) {
        is ConstraintLayout -> applyConstraintLayoutAttributes(attributes, coords)
        is FrameLayout, is CoordinatorLayout -> applyGravityMarginAttributes(attributes, coords)
        is RelativeLayout -> applyRelativeLayoutAttributes(attributes, coords)
        is LinearLayout -> applyDragReorder(container, child, x, y)
        is GridLayout -> applyGridLayoutAttributes(container, attributes, x, y)
        else -> applyGenericLayoutAttributes(attributes, coords)
    }
}