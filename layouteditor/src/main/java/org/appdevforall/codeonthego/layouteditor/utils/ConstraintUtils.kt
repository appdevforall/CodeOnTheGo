package org.appdevforall.codeonthego.layouteditor.utils

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
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
private fun modifyConstraintsForView(constraintSet: ConstraintSet, viewId: Int, startPxMargin: Int, topPxMargin: Int) {
	constraintSet.clear(viewId, ConstraintSet.BOTTOM)
	constraintSet.clear(viewId, ConstraintSet.END)
	constraintSet.connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
	constraintSet.connect(viewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
	constraintSet.setMargin(viewId, ConstraintSet.START, startPxMargin)
	constraintSet.setMargin(viewId, ConstraintSet.TOP, topPxMargin)
}

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
private fun String.toPx(density: Float): Float {
	val trimmed = trim()
	return when {
		trimmed.endsWith("px", true) -> trimmed.dropLast(2).toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
		trimmed.endsWith("dp", true) -> (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * density
		trimmed.endsWith("dip", true) -> (trimmed.dropLast(3).toFloatOrNull() ?: 0f) * density
		else -> (trimmed.toFloatOrNull() ?: 0f) * density // assume dp if no suffix
	}
}

/**
 * Helper class to store a set of constraint changes for a single view.
 */
private data class ViewConstraintChange(
    val viewId: Int,
    val startMargin: Int,
    val topMargin: Int
)

/**
 * Efficiently restores the positions of draggable views by reapplying constraints
 * after the layout has finished rendering.
 *
 * This function uses a two-pass batching mechanism for efficiency:
 *
 * 1.  **Collection Pass:** Iterates [attributeMap], calculates pixel margins,
 * resets [View.translationX]/[View.translationY] to 0, and groups all
 * required changes by their parent [ConstraintLayout].
 * 2.  **Application Pass:** Iterates over each unique parent [ConstraintLayout],
 * clones its state into a *single* [ConstraintSet], applies *all*
 * changes for that group using [modifyConstraintsForView], and then calls
 * [ConstraintSet.applyTo] **once** per container.
 *
 * This ensures only one layout pass is triggered per [ConstraintLayout] container,
 * instead of N passes.
 *
 * @param rootView The root [View] to observe for layout completion.
 * @param attributeMap A [Map] of [View]s to their corresponding [AttributeMap].
 */
fun restorePositionsAfterLoad(rootView: View, attributeMap: Map<View, AttributeMap>) {
	rootView.doOnLayout { container ->
		val density = container.resources.displayMetrics.density

		val changesByContainer = mutableMapOf<ConstraintLayout, MutableList<ViewConstraintChange>>()

		// --- 1. COLLECTION PASS ---
		attributeMap.forEach { (view, attrs) ->
			val constraintContainer = view.parent as? ConstraintLayout ?: return@forEach

			val txStr = attrs.getValue("app:layout_marginStart")
			val tyStr = attrs.getValue("app:layout_marginTop")

			if (txStr.isNotEmpty() || tyStr.isNotEmpty()) {
				val maxX = (constraintContainer.width - view.width).coerceAtLeast(0).toFloat()
				val maxY = (constraintContainer.height - view.height).coerceAtLeast(0).toFloat()

				val txPx = txStr.toPx(density).coerceIn(0f, maxX)
				val tyPx = tyStr.toPx(density).coerceIn(0f, maxY)

				view.translationX = 0f
				view.translationY = 0f

				val changesList = changesByContainer.getOrPut(constraintContainer) { mutableListOf() }

				changesList.add(ViewConstraintChange(
					viewId = view.id,
					startMargin = txPx.toInt(),
					topMargin = tyPx.toInt()
				))
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
