package com.itsaky.androidide.activities.editor

import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import com.itsaky.androidide.utils.forEachViewRecursively
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Scales the text in plugin views by the editor's font-size preference (ADFA-4212).
 *
 * The scale never goes below 1 (ADFA-7142). The editor's size goes down to 6sp, often by pinching
 * the code, and plugin tabs followed it to under half their size. Views a plugin adds after its
 * view was built (list rows, chips) are scaled too; without that, one panel mixed two sizes.
 *
 * Sizes are px, scaled from the size each [TextView] was given by its plugin, so applying the same
 * scale twice changes nothing. A size the plugin sets again later becomes that view's new base.
 * Auto-sized text is left alone: [TextView.setTextSize] does nothing to it.
 */
internal class PluginTextScaler(
	private val editorFontScale: () -> Float,
) {
	private class Sizing(
		val base: Float,
		var applied: Float,
	)

	private val sizings = WeakHashMap<TextView, Sizing>()
	private val trackedRoots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

	// Until some view is scaled, none can be stale, so the default skips the walk.
	private var scaledAny = false

	fun scale(): Float = max(1f, editorFontScale())

	/**
	 * Scales the text under [root] now, and in the views added under it later. A root tracked
	 * again, as a fragment that reuses its view causes, keeps its one watcher.
	 */
	fun track(root: View) {
		scaleNewText(root)
		if (trackedRoots.add(root)) root.addOnAttachStateChangeListener(AddedViewWatcher(root))
	}

	/** Applies the current [scale] to all the text under [root], once the preference has changed. */
	fun rescale(root: View) {
		val scale = scale()
		root.forEachViewRecursively { view ->
			if (view is TextView && !view.isAutoSized()) apply(view, sizingFor(view), scale)
		}
	}

	/**
	 * Brings every text view under [root] to the current scale: new ones, ones the plugin resized,
	 * and ones detached while the preference changed (a pooled row, say). Returns whether any changed.
	 */
	private fun scaleNewText(root: View): Boolean {
		val scale = scale()
		if (scale == 1f && !scaledAny) return false
		var changed = false
		root.forEachViewRecursively { view ->
			if (view !is TextView || view.isAutoSized()) return@forEachViewRecursively
			val sizing = sizingFor(view)
			if (sizing.applied != scale) {
				apply(view, sizing, scale)
				changed = true
			}
		}
		return changed
	}

	// Re-recorded when the size is no longer the one this class set: the plugin has set its own.
	private fun sizingFor(view: TextView): Sizing {
		val known = sizings[view]
		if (known != null && view.textSize == known.base * known.applied) return known
		return Sizing(view.textSize, 1f).also { sizings[view] = it }
	}

	private fun apply(
		view: TextView,
		sizing: Sizing,
		scale: Float,
	) {
		if (scale != 1f) scaledAny = true
		view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.base * scale)
		sizing.applied = scale
	}

	private fun TextView.isAutoSized() = autoSizeTextType != TextView.AUTO_SIZE_TEXT_TYPE_NONE

	/**
	 * Catches views added under [root] before the frame that would draw them. A pre-draw listener,
	 * not a layout one: a scrolling RecyclerView or ListView adds rows without any layout pass. A
	 * frame in which something was resized is skipped, so its text is never drawn at the old size;
	 * the next one is laid out for the new size. Never two in a row, though: a plugin that sets its
	 * own size on every layout would otherwise have every frame skipped, and the window would stop
	 * drawing. The listener lives on the window's observer only while [root] is attached: once
	 * detached, [View.getViewTreeObserver] returns a different observer, which could no longer
	 * remove it.
	 */
	private inner class AddedViewWatcher(
		private val root: View,
	) : View.OnAttachStateChangeListener,
		ViewTreeObserver.OnPreDrawListener {
		private var observer: ViewTreeObserver? = null
		private var skippedLast = false

		init {
			if (root.isAttachedToWindow) onViewAttachedToWindow(root)
		}

		override fun onViewAttachedToWindow(v: View) {
			observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
		}

		override fun onViewDetachedFromWindow(v: View) {
			observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
			observer = null
		}

		// Hidden text is caught on the frame that first shows it.
		override fun onPreDraw(): Boolean {
			val skip = root.isShown && scaleNewText(root) && !skippedLast
			skippedLast = skip
			return !skip
		}
	}
}
