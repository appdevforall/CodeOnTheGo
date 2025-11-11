package org.appdevforall.codeonthego.layouteditor.editor.positioning

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.coordinatorlayout.widget.CoordinatorLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap


/**
 * Modifies a [ConstraintSet] to apply basic constraints to a specific view.
 *
 * - Anchors the view to the parent's START and TOP.
 * - Clears existing END and BOTTOM constraints to avoid conflicts.
 * - Sets START and TOP margins in **pixels** using the provided values.
 *
 * @param constraintSet The [ConstraintSet] instance to modify.
 * @param viewId The ID of the target view.
 * @param startPxMargin The START margin in **pixels**.
 * @param topPxMargin The TOP margin in **pixels**.
 */
fun modifyConstraintsForView(constraintSet: ConstraintSet, viewId: Int, startPxMargin: Int, topPxMargin: Int) {
	constraintSet.clear(viewId, ConstraintSet.BOTTOM)
	constraintSet.clear(viewId, ConstraintSet.END)
	constraintSet.connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
	constraintSet.connect(viewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
	constraintSet.setMargin(viewId, ConstraintSet.START, startPxMargin)
	constraintSet.setMargin(viewId, ConstraintSet.TOP, topPxMargin)
}

/**
 * Reads [ConstraintLayout] positioning attributes and collects them for batch processing.
 *
 * This function does not apply changes directly. Instead, it calculates the pixel
 * margins and adds them to the [changesByContainer] map. This allows all
 * constraint changes for a single [ConstraintLayout] to be applied at once
 * for better performance.
 *
 * **Attributes Read:**
 * - `app:layout_marginStart`
 * - `app:layout_marginTop`
 *
 * @param container The parent [ConstraintLayout].
 * @param view The child view to read attributes for.
 * @param attrs The [AttributeMap] containing the stored attributes.
 * @param density The screen density for px conversion.
 * @param changesByContainer The map to add the [ViewConstraintChange] to.
 */
fun collectConstraintLayoutChange(
    container: ConstraintLayout,
    view: View,
    attrs: AttributeMap,
    density: Float,
    changesByContainer: MutableMap<ConstraintLayout, MutableList<ViewConstraintChange>>
) {
    val txStr = attrs.getValue("app:layout_marginStart")
    val tyStr = attrs.getValue("app:layout_marginTop")

    if (txStr.isNotEmpty() || tyStr.isNotEmpty()) {
        val (txPx, tyPx) = calculateClampedPx(container, view, txStr, tyStr, density)
        val changesList = changesByContainer.getOrPut(container) { mutableListOf() }
        changesList.add(ViewConstraintChange(
            viewId = view.id,
            startMargin = txPx.toInt(),
            topMargin = tyPx.toInt()
        ))
    }
}

/**
 * Restores the position for a child view within a [FrameLayout].
 *
 * This function reads the stored margins, calculates the final clamped pixel values,
 * and applies them directly to the [view]'s [FrameLayout.LayoutParams]. It also
 * sets the `gravity` to `TOP|START` to match the positioning logic.
 *
 * **Attributes Read:**
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 *
 * @param container The parent [FrameLayout].
 * @param view The child view to position.
 * @param attrs The [AttributeMap] containing the stored attributes.
 * @param density The screen density for px conversion.
 */
fun restoreFrameLayoutPosition(
	container: FrameLayout, view: View, attrs: AttributeMap, density: Float
) {
    val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
    val txStr = attrs.getValue("android:layout_marginLeft")
    val tyStr = attrs.getValue("android:layout_marginTop")

    if (txStr.isNotEmpty() || tyStr.isNotEmpty()) {
        val (txPx, tyPx) = calculateClampedPx(container, view, txStr, tyStr, density)
        lp.leftMargin = txPx.toInt()
        lp.topMargin = tyPx.toInt()
        lp.gravity = Gravity.TOP or Gravity.START
        view.layoutParams = lp
    }
}

/**
 * Restores the position for a child view within a [CoordinatorLayout].
 *
 * This function reads the stored margins, calculates the final clamped pixel values,
 * and applies them directly to the [view]'s [CoordinatorLayout.LayoutParams]. It also
 * sets the `gravity` to `TOP|START`.
 *
 * **Attributes Read:**
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 *
 * @param container The parent [CoordinatorLayout].
 * @param view The child view to position.
 * @param attrs The [AttributeMap] containing the stored attributes.
 * @param density The screen density for px conversion.
 */
internal fun restoreCoordinatorLayoutPosition(
	container: CoordinatorLayout, view: View, attrs: AttributeMap, density: Float
) {
    val lp = view.layoutParams as? CoordinatorLayout.LayoutParams ?: return
    val txStr = attrs.getValue("android:layout_marginLeft")
    val tyStr = attrs.getValue("android:layout_marginTop")

    if (txStr.isNotEmpty() || tyStr.isNotEmpty()) {
        val (txPx, tyPx) = calculateClampedPx(container, view, txStr, tyStr, density)
        lp.leftMargin = txPx.toInt()
        lp.topMargin = tyPx.toInt()
        lp.gravity = Gravity.TOP or Gravity.START
        view.layoutParams = lp
    }
}

/**
 * Restores the position for a child view within a [RelativeLayout].
 *
 * This function reads stored margins and alignment rules. It first **clears**
 * all existing parent alignment rules (START, TOP, END, BOTTOM) to prevent
 * conflicts, then applies the new margins and `alignParent` rules directly to the
 * [view]'s [RelativeLayout.LayoutParams].
 *
 * **Attributes Read:**
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 * - `android:layout_alignParentStart`
 * - `android:layout_alignParentTop`
 *
 * @param container The parent [RelativeLayout].
 * @param view The child view to position.
 * @param attrs The [AttributeMap] containing the stored attributes.
 * @param density The screen density for px conversion.
 */
fun restoreRelativeLayoutPosition(
	container: RelativeLayout, view: View, attrs: AttributeMap, density: Float
) {
    val lp = view.layoutParams as? RelativeLayout.LayoutParams ?: return
    val txStr = attrs.getValue("android:layout_marginLeft")
    val tyStr = attrs.getValue("android:layout_marginTop")
    val alignParentStart = attrs.getValue("android:layout_alignParentStart") == "true"
    val alignParentTop = attrs.getValue("android:layout_alignParentTop") == "true"

    if (txStr.isNotEmpty() || tyStr.isNotEmpty() || alignParentStart || alignParentTop) {
        val (txPx, tyPx) = calculateClampedPx(container, view, txStr, tyStr, density)

        lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
        lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)

        lp.leftMargin = txPx.toInt()
        lp.topMargin = tyPx.toInt()

        if (alignParentStart) lp.addRule(RelativeLayout.ALIGN_PARENT_START)
        if (alignParentTop) lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)

        view.layoutParams = lp
    }
}

/**
 * A fallback position restore function for any [ViewGroup] that supports
 * [ViewGroup.MarginLayoutParams].
 *
 * This function reads standard margins and applies them directly to the
 * [view]'s `layoutParams`. It does not handle alignment or flow logic,
 * only margins.
 *
 * **Attributes Read:**
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 *
 * @param container The parent [ViewGroup].
 * @param view The child view to position.
 * @param attrs The [AttributeMap] containing the stored attributes.
 * @param density The screen density for px conversion.
 */
fun restoreGenericMarginPosition(
    container: ViewGroup, view: View, attrs: AttributeMap, density: Float
) {
    val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val txStr = attrs.getValue("android:layout_marginLeft")
    val tyStr = attrs.getValue("android:layout_marginTop")

    if (txStr.isNotEmpty() || tyStr.isNotEmpty()) {
        val (txPx, tyPx) = calculateClampedPx(container, view, txStr, tyStr, density)
        lp.leftMargin = txPx.toInt()
        lp.topMargin = tyPx.toInt()
        view.layoutParams = lp
    }
}
