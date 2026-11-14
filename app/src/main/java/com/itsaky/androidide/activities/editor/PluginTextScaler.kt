package com.itsaky.androidide.activities.editor

import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import com.itsaky.androidide.utils.forEachViewRecursively
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Scales the text in plugin views by the editor's font-size preference (ADFA-4212).
 *
 * The scale never goes below 1 (ADFA-7142). The editor's size goes down to 6sp, often by pinching
 * the code, and plugin tabs followed it to under half their size. Views a plugin adds after its
 * view was built (list rows, chips) are scaled too; without that, one panel mixed two sizes.
 *
 * Sizes are px, scaled from the size each [TextView] had when first seen, so applying the same
 * scale twice changes nothing. A plugin that resets a size it already set (say, on rebinding a
 * recycled row) gets its own size back until the preference next changes.
 */
internal class PluginTextScaler(
	private val editorFontScale: () -> Float,
) {
	private val baseSizes = WeakHashMap<TextView, Float>()

	fun scale(): Float = max(1f, editorFontScale())

	/** Scales the text under [root] now, and in the views added under it later. */
	fun track(root: View) {
		scaleNewText(root)
		root.addOnAttachStateChangeListener(AddedViewWatcher(root))
	}

	/** Applies the current [scale] to all the text under [root], once the preference has changed. */
	fun rescale(root: View) {
		val scale = scale()
		root.forEachViewRecursively { view ->
			if (view is TextView) {
				val base = baseSizes.getOrPut(view) { view.textSize }
				view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale)
			}
		}
	}

	private fun scaleNewText(root: View) {
		val scale = scale()
		// At 1 nothing needs scaling, and a view left unrecorded still has its own size for rescale().
		if (scale == 1f) return
		root.forEachViewRecursively { view ->
			if (view is TextView && view !in baseSizes) {
				baseSizes[view] = view.textSize
				view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * scale)
			}
		}
	}

	/**
	 * Catches views added under [root] at the layout pass that follows. The listener lives on the
	 * window's observer only while [root] is attached: once detached, [View.getViewTreeObserver]
	 * returns a different observer, which could no longer remove it.
	 */
	private inner class AddedViewWatcher(
		private val root: View,
	) : View.OnAttachStateChangeListener,
		ViewTreeObserver.OnGlobalLayoutListener {
		private var observer: ViewTreeObserver? = null

		init {
			if (root.isAttachedToWindow) onViewAttachedToWindow(root)
		}

		override fun onViewAttachedToWindow(v: View) {
			observer = v.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
		}

		override fun onViewDetachedFromWindow(v: View) {
			observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
			observer = null
		}

		override fun onGlobalLayout() = scaleNewText(root)
	}
}
