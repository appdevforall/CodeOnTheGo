package org.appdevforall.codeonthego.layouteditor.editor.positioning

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TableLayout
import android.widget.TableRow
import androidx.constraintlayout.widget.ConstraintLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap


/**
 * Ensures the [child] view has valid [TableRow.LayoutParams].
 *
 * This function is crucial for preventing a [View] from becoming invisible
 * when added to a [TableRow]. It converts generic `LayoutParams`
 * (like `LinearLayout.LayoutParams` or `MarginLayoutParams`) into
 * `TableRow.LayoutParams`, ensuring the `weight` is 0f and
 * the `width` is `WRAP_CONTENT` if not otherwise defined.
 *
 * @param child The [View] whose `layoutParams` will be checked and
 * potentially replaced.
 */
private fun ensureTableRowLayoutParams(child: View) {
    val newLp: TableRow.LayoutParams = when (val lp = child.layoutParams) {
        is TableRow.LayoutParams -> {
            if ((lp.width == 0 || lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) && lp.weight == 0f) {
                TableRow.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (lp.height <= 0) ViewGroup.LayoutParams.WRAP_CONTENT else lp.height
                ).apply {
                    setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
                    gravity = lp.gravity
                    weight  = 0f
                }
            } else lp
        }

        is LinearLayout.LayoutParams -> {
            TableRow.LayoutParams(
                if (lp.width == 0 && lp.weight == 0f) ViewGroup.LayoutParams.WRAP_CONTENT else lp.width,
                if (lp.height <= 0) ViewGroup.LayoutParams.WRAP_CONTENT else lp.height
            ).apply {
                setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
                gravity = lp.gravity
                weight  = lp.weight
            }
        }

        is ViewGroup.MarginLayoutParams -> {
            TableRow.LayoutParams(lp).apply {
                if (width == 0)  width  = ViewGroup.LayoutParams.WRAP_CONTENT
                if (height <= 0) height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        else -> {
            TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    if (child.layoutParams !== newLp) child.layoutParams = newLp
}


/**
 * Reorders a [child] within a [container] that supports drag-reordering
 * (like [LinearLayout], [TableLayout], or [TableRow]).
 *
 * The function determines the container's orientation (vertical or horizontal)
 * and uses the [x] or [y] coordinate to find the new index where
 * the [child] should be inserted.
 *
 * If the [container] is a [TableRow], it first calls [ensureTableRowLayoutParams]
 * to prevent the [child] from becoming invisible.
 *
 * @param container The parent [ViewGroup] (must be [LinearLayout],
 * [TableLayout], or [TableRow]).
 * @param child The [View] being moved.
 * @param x The raw X coordinate of the drop (used for horizontal orientation).
 * @param y The raw Y coordinate of the drop (used for vertical orientation).
 */
internal fun applyDragReorder(container: ViewGroup, child: View, x: Float, y: Float) {
    if (container is TableRow) {
        ensureTableRowLayoutParams(child)
    }

    val currentIndex = container.indexOfChild(child)
    var newIndex = -1

    val isVertical = when (container) {
        is TableLayout -> true
        is TableRow -> false
        is LinearLayout -> container.orientation == LinearLayout.VERTICAL
        else -> return
    }

    for (i in 0 until container.childCount) {
        val otherChild = container.getChildAt(i)
        if (otherChild == child) continue

        val center: Float
        val dropCoord: Float

        if (isVertical) {
            center = otherChild.top + (otherChild.height / 2f)
            dropCoord = y
        } else {
            center = otherChild.left + (otherChild.width / 2f)
            dropCoord = x
        }

        if (dropCoord < center) {
            newIndex = i
            break
        }
    }

    if (newIndex == -1) {
        newIndex = container.childCount
    }

    if (currentIndex == -1) {
        container.addView(child, newIndex)
    } else {
        if (currentIndex == newIndex) return

        val targetIndex = if (newIndex > currentIndex) newIndex - 1 else newIndex

        if (currentIndex == targetIndex) return

        container.removeViewAt(currentIndex)
        container.addView(child, targetIndex)
    }

    container.requestLayout()

    if (container is TableRow) {
        (container.parent as? TableLayout)?.requestLayout()
        container.invalidate()
    }
}


/**
 * Applies positioning attributes for a [ConstraintLayout] to the [map].
 *
 * Anchors the view to the parent's top-start and uses margins.
 *
 * **Attributes Written:**
 * - `app:layout_constraintStart_toStartOf`
 * - `app:layout_constraintTop_toTopOf`
 * - `app:layout_marginStart`
 * - `app:layout_marginTop`
 *
 * @param map The [AttributeMap] to write attributes to.
 * @param coords The [DpCoordinates] containing the margins to apply.
 */
fun applyConstraintLayoutAttributes(
    map: AttributeMap,
    coords: DpCoordinates
) {
    map.putValue("app:layout_constraintStart_toStartOf", "parent")
    map.putValue("app:layout_constraintTop_toTopOf", "parent")
    map.putValue("app:layout_marginStart", "${coords.xDp}dp")
    map.putValue("app:layout_marginTop", "${coords.yDp}dp")
}

/**
 * Applies positioning attributes for layouts that use gravity and margins (e.g., [FrameLayout], [androidx.coordinatorlayout.widget.CoordinatorLayout])
 *
 * Uses `layout_gravity` to lock to the top-start and standard margins.
 *
 * **Attributes Written:**
 * - `android:layout_gravity`
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 *
 * @param map The [AttributeMap] to write attributes to.
 * @param coords The [DpCoordinates] containing the margins to apply.
 */
internal fun applyGravityMarginAttributes(
    map: AttributeMap,
    coords: DpCoordinates
) {
    map.putValue("android:layout_gravity", "top|start")
    map.putValue("android:layout_marginLeft", "${coords.xDp}dp")
    map.putValue("android:layout_marginTop", "${coords.yDp}dp")
}

/**
 * Applies positioning attributes for a [RelativeLayout] to the [map].
 *
 * Aligns the view to the parent's top-start and uses standard margins.
 *
 * **Attributes Written:**
 * - `android:layout_alignParentStart`
 * - `android:layout_alignParentTop`
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 *
 * @param map The [AttributeMap] to write attributes to.
 * @param coords The [DpCoordinates] containing the margins to apply.
 */
internal fun applyRelativeLayoutAttributes(
    map: AttributeMap,
    coords: DpCoordinates
) {
    map.putValue("android:layout_alignParentStart", "true")
    map.putValue("android:layout_alignParentTop", "true")
    map.putValue("android:layout_marginLeft", "${coords.xDp}dp")
    map.putValue("android:layout_marginTop", "${coords.yDp}dp")
}

internal fun applyGridLayoutAttributes(
	container: GridLayout,
	attributes: AttributeMap,
	x: Float,
	y: Float
) {
    val colCount = container.columnCount.takeIf { it > 0 } ?: 1
    val rowCount = container.rowCount.takeIf { it > 0 } ?: 1

    val colWidth = (container.width.toFloat() / colCount).coerceAtLeast(1f)
    val rowHeight = (container.height.toFloat() / rowCount).coerceAtLeast(1f)

    val targetCol = (x / colWidth).toInt().coerceIn(0, colCount - 1)
    val targetRow = (y / rowHeight).toInt().coerceIn(0, rowCount - 1)

    attributes.putValue("android:layout_row", "$targetRow")
    attributes.putValue("android:layout_column", "$targetCol")

    attributes.putValue("android:layout_gravity", "center")
}

/**
 * A generic fallback that applies standard margins to the [map].
 *
 * This is used for parent layouts that are not explicitly handled but
 * still support [android.view.ViewGroup.MarginLayoutParams].
 *
 * **Attributes Written:**
 * - `android:layout_marginLeft`
 * - `android:layout_marginTop`
 *
 * @param map The [AttributeMap] to write attributes to.
 * @param coords The [DpCoordinates] containing the margins to apply.
 */
internal fun applyGenericLayoutAttributes(
    map: AttributeMap,
    coords: DpCoordinates
) {
    map.putValue("android:layout_marginLeft", "${coords.xDp}dp")
    map.putValue("android:layout_marginTop", "${coords.yDp}dp")
}