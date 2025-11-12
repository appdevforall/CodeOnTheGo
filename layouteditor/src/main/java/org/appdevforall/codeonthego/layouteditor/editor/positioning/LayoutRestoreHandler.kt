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
import androidx.constraintlayout.widget.ConstraintSet
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.doOnLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap


/**
 * Restores the saved positions of all views in the [attributeMap] after a layout pass.
 *
 * This function must run within [doOnLayout] to ensure parent and child view
 * dimensions are measured, which is necessary for clamping coordinates correctly.
 *
 * It uses a "dynamic strategy" approach based on the parent [ViewGroup] type:
 *
 * 1.  **Collection Pass:**
 * - Resets all [View.translationX] and [View.translationY] to `0f`.
 * - Iterates through the [attributeMap].
 * - Dispatches to a layout-specific helper (e.g., [collectConstraintLayoutChange]
 * or [restoreFrameLayoutPosition]) based on the parent's type.
 *
 * 2.  **Application Pass (for ConstraintLayout):**
 * - Applies all collected [ConstraintLayout] changes in a single batch
 * using [ConstraintSet.applyTo] for maximum efficiency.
 * - Other layout types (Frame, Relative) are restored immediately
 * during the collection pass as setting `layoutParams` is cheap.
 *
 * @param rootView The root [View] to observe for layout completion.
 * @param attributeMap A [Map] of [View]s to their corresponding [AttributeMap]
 * containing the saved positioning attributes.
 */
fun restorePositionsAfterLoad(rootView: View, attributeMap: Map<View, AttributeMap>) {
    rootView.doOnLayout { container ->
        val density = container.resources.displayMetrics.density

        val changesByContainer = mutableMapOf<ConstraintLayout, MutableList<ViewConstraintChange>>()

        // --- 1. COLLECTION PASS ---
        attributeMap.forEach { (view, attrs) ->
            val parent = view.parent as? ViewGroup ?: return@forEach

            view.translationX = 0f
            view.translationY = 0f

            when (parent) {
                is ConstraintLayout -> collectConstraintLayoutChange(parent, view, attrs, density, changesByContainer)
                is FrameLayout -> restoreFrameLayoutPosition(parent, view, attrs, density)
                is RelativeLayout -> restoreRelativeLayoutPosition(parent, view, attrs, density)
                is CoordinatorLayout -> restoreCoordinatorLayoutPosition(parent, view, attrs, density)
                is TableLayout, is TableRow, is LinearLayout -> {}
                is GridLayout -> restoreGridLayoutPosition(view, attrs)
                else -> restoreGenericMarginPosition(parent, view, attrs, density)
            }
        }

        // --- 2. APPLICATION PASS ---
        changesByContainer.forEach { (container, changeList) ->
            val constraintSet = ConstraintSet()
            constraintSet.clone(container)
            changeList.forEach { change ->
                modifyConstraintsForView(
                    constraintSet,
                    change.viewId,
                    change.startMargin,
                    change.topMargin
                )
            }
            constraintSet.applyTo(container)
        }
    }
}
