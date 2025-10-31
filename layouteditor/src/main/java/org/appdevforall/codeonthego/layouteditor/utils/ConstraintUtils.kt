package org.appdevforall.codeonthego.layouteditor.utils

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import org.appdevforall.codeonthego.layouteditor.editor.initializer.AttributeMap


/**
 * Applies basic constraints to a view inside a [ConstraintLayout].
 *
 * - Anchors the view to the parent's START and TOP.
 * - Clears existing END and BOTTOM constraints to avoid conflicts.
 * - Sets START and TOP margins in **pixels** using the provided values.
 * - Calls [ConstraintSet.applyTo] to commit the constraint changes.
 *
 * @param viewId The ID of the target view.
 * @param container The [ConstraintLayout] parent container.
 * @param startPxMargin The START margin in **pixels**.
 * @param topPxMargin The TOP margin in **pixels**.
 */
private fun applyConstraints(viewId: Int, container: ConstraintLayout, startPxMargin: Int, topPxMargin: Int) {
	val constraintSet = ConstraintSet()

	constraintSet.clone(container)
	constraintSet.clear(viewId, ConstraintSet.BOTTOM)
	constraintSet.clear(viewId, ConstraintSet.END)
	constraintSet.connect(viewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
	constraintSet.connect(viewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
	constraintSet.setMargin(viewId, ConstraintSet.START, startPxMargin)
	constraintSet.setMargin(viewId, ConstraintSet.TOP, topPxMargin)
	constraintSet.applyTo(container)
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
 * Restores the positions of draggable views by reapplying constraints
 * after the layout has finished rendering.
 *
 * For each entry in [attributeMap]:
 * - Reads `app:layout_marginStart` and `app:layout_marginTop` (in dp).
 * - Converts them to pixels and clamps them within the container bounds.
 * - Resets [View.translationX] and [View.translationY] to 0 to avoid cumulative shifts.
 * - Calls [applyConstraints] to update margins in the layout.
 *
 * Should be called after layout completion (via [doOnLayout])
 * to ensure container and child sizes are available.
 *
 * @param rootView The root [View] to observe for layout completion.
 * @param attributeMap A [Map] of [View]s to their corresponding [AttributeMap].
 */
fun restorePositionsAfterLoad(rootView: View, attributeMap: Map<View, AttributeMap>) {
	rootView.doOnLayout { container ->
		val density = container.resources.displayMetrics.density
		attributeMap.forEach { (view, attrs) ->
			val constraintContainer = view.parent as? ConstraintLayout ?: return@forEach
			val txStr =
				if (attrs.contains("app:layout_marginStart")) attrs.getValue("app:layout_marginStart") else null
			val tyStr =
				if (attrs.contains("app:layout_marginTop")) attrs.getValue("app:layout_marginTop") else null

			if (txStr != null || tyStr != null) {
				val maxX = (constraintContainer.width - view.width).coerceAtLeast(0).toFloat()
				val maxY = (constraintContainer.height - view.height).coerceAtLeast(0).toFloat()

				val txPx = txStr?.toPx(density)?.coerceIn(0f, maxX) ?: 0f
				val tyPx = tyStr?.toPx(density)?.coerceIn(0f, maxY) ?: 0f

				view.translationX = 0f
				view.translationY = 0f

				applyConstraints(view.id, constraintContainer, txPx.toInt(), tyPx.toInt())
			}
		}
	}
}
