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
import androidx.core.view.children
import androidx.core.view.doOnLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap
import kotlin.sequences.forEach


private fun ConstraintLayout.cloneWithIds(): ConstraintSet {
	children.forEach { child ->
		if (child.id == View.NO_ID) {
			child.id = View.generateViewId()
		}
	}
	return ConstraintSet().apply { clone(this@cloneWithIds) }
}

/**
 * Executes the core positioning logic for a **single view**.
 *
 * This function resets the view's [View.translationX] and [View.translationY] to `0f`,
 * as positions are restored using layout parameters (like margins) rather than translations.
 *
 * It then dispatches to a layout-specific helper (e.g., [collectConstraintLayoutChange]
 * or [restoreFrameLayoutPosition]) based on the [view]'s parent [ViewGroup] type.
 *
 * If the parent is a [ConstraintLayout], this function populates the [constraintChanges]
 * map for batch application in the "Application Pass."
 *
 * @param view The [View] to be repositioned.
 * @param attrs The [AttributeMap] containing the saved positional attributes for the [view].
 * @param density The screen's display density for DP/PX calculations.
 * @param constraintChanges An **output map**. If the [view]'s parent is a [ConstraintLayout],
 * a [ViewConstraintChange] object will be added to this map, keyed by the parent container.
 */
private fun applyPositioningLogic(
    view: View,
    attrs: AttributeMap,
    density: Float,
    constraintChanges: MutableMap<ConstraintLayout, MutableList<ViewConstraintChange>>
) {
    val parent = view.parent as? ViewGroup ?: return

    view.translationX = 0f
    view.translationY = 0f

    when (parent) {
        is ConstraintLayout -> collectConstraintLayoutChange(parent, view, attrs, density, constraintChanges)
        is FrameLayout -> restoreFrameLayoutPosition(parent, view, attrs, density)
        is RelativeLayout -> restoreRelativeLayoutPosition(parent, view, attrs, density)
        is CoordinatorLayout -> restoreCoordinatorLayoutPosition(parent, view, attrs, density)
        is TableLayout, is TableRow, is LinearLayout -> {} // No-op
        is GridLayout -> restoreGridLayoutPosition(view, attrs)
        else -> restoreGenericMarginPosition(parent, view, attrs, density)
    }
}


/**
 * Applies a batch of collected [ConstraintLayout] changes.
 *
 * This function implements the **Application Pass**. It iterates through each
 * [ConstraintLayout] container that has pending changes, clones its [ConstraintSet],
 * modifies it with all the changes in the `changeList`, and finally applies the
 * modified set back to the container using [ConstraintSet.applyTo].
 *
 * This batch approach is significantly more efficient than applying changes one by one.
 *
 * @param changesByContainer A [Map] where each key is a [ConstraintLayout] and the
 * value is a [List] of [ViewConstraintChange] objects to be applied to it.
 */
private fun applyConstraintChanges(changesByContainer: Map<ConstraintLayout, List<ViewConstraintChange>>) {
    changesByContainer.forEach { (container, changeList) ->
        if (changeList.isEmpty()) return@forEach

        val constraintSet = container.cloneWithIds()
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


/**
 * Restores the saved positions of **all views** in the [attributeMap] after an initial layout pass.
 *
 * This function is intended for loading a complete layout from scratch.
 *
 * This function must run within [doOnLayout] to ensure parent and child view
 * dimensions are measured, which is necessary for clamping coordinates correctly.
 *
 * It uses a "dynamic strategy" approach based on the parent [ViewGroup] type,
 * implemented by [applyPositioningLogic] and [applyConstraintChanges]:
 *
 * 1.  **Collection Pass:**
 * - Iterates through the entire [attributeMap].
 * - For each view, it calls [applyPositioningLogic] to reset translations and
 * collect [ConstraintLayout] changes.
 *
 * 2.  **Application Pass (for ConstraintLayout):**
 * - Calls [applyConstraintChanges] to apply all collected [ConstraintLayout]
 * changes in a single batch for maximum efficiency.
 *
 * @param rootView The root [View] to observe for layout completion.
 * @param attributeMap A [Map] of [View]s to their corresponding [AttributeMap]
 * containing the saved positioning attributes.
 */
fun restorePositionsAfterLoad(rootView: View, attributeMap: Map<View, AttributeMap>) {
    rootView.doOnLayout { container ->
        val density = container.resources.displayMetrics.density

        val changesByContainer = mutableMapOf<ConstraintLayout, MutableList<ViewConstraintChange>>()

        // --- 1. COLLECTION PASS
        attributeMap.forEach { (view, attrs) ->
            applyPositioningLogic(view, attrs, density, changesByContainer)
        }

        // --- 2. APPLICATION PASS ---
        applyConstraintChanges(changesByContainer)
    }
}


/**
 * Restores the saved position for a **single view** after a layout pass.
 *
 * This is an optimized version of [restorePositionsAfterLoad], designed to be called
 * after a single view is added or moved (e.g., from a drag-and-drop operation).
 *
 * Like its counterpart, this function **must run within [doOnLayout]** to ensure
 * coordinates are clamped correctly against measured parent/child dimensions.
 *
 * It performs the same Collection and Application pass, but **only for the
 * specified [viewToRestore]**, providing a significant performance boost by not
 * iterating over all other views.
 *
 * @param rootView The root [View] to observe for layout completion.
 * @param viewToRestore The specific [View] whose position needs to be restored.
 * @param attributeMap The complete [Map] of views to attributes, used to look up
 * the data for [viewToRestore].
 */
fun restoreSingleViewPosition(
    rootView: View,
    viewToRestore: View,
    attributeMap: Map<View, AttributeMap>
) {
    rootView.doOnLayout { container ->
        val density = container.resources.displayMetrics.density

        val attrs = attributeMap[viewToRestore] ?: return@doOnLayout

        val changesByContainer = mutableMapOf<ConstraintLayout, MutableList<ViewConstraintChange>>()

        // --- 1. COLLECTION PASS ---
        applyPositioningLogic(viewToRestore, attrs, density, changesByContainer)

        // --- 2. APPLICATION PASS ---
        applyConstraintChanges(changesByContainer)
    }
}
