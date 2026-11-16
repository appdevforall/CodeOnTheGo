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
	editorFontScale: Float,
) {
	private class Sizing(
		val base: Float,
		var applied: Float,
	)

	private val sizings = WeakHashMap<TextView, Sizing>()
	private val trackedRoots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

	// Keyed by the window's observer; an entry lasts while some tracked root is in that window.
	private val windowWatchers = HashMap<ViewTreeObserver, WindowWatcher>()

	private var scale = max(1f, editorFontScale)

	/**
	 * Scales the text under [root] now, and in the views added under it later. A root tracked
	 * again, as a fragment that reuses its view causes, is still watched once.
	 */
	fun track(root: View) {
		scaleNewText(root)
		if (trackedRoots.add(root)) {
			root.addOnAttachStateChangeListener(attachListener)
			if (root.isAttachedToWindow) startWatching(root)
		}
	}

	/**
	 * Follows a change to the editor's font scale. Unless the plugin text scale stays the same (any
	 * size below the editor's default leaves it at 1), the text under every tracked root is rescaled,
	 * and so is every text view seen before that is now off the window, such as a pooled list row.
	 * After that no text is left at an old scale, so at scale 1 no frame needs a walk.
	 */
	fun onEditorFontScaleChanged(editorFontScale: Float) {
		val newScale = max(1f, editorFontScale)
		if (newScale == scale) return
		scale = newScale
		for (root in trackedRoots.toList()) {
			root.forEachViewRecursively { view ->
				if (view is TextView && !view.isAutoSized()) apply(view, sizingFor(view), scale)
			}
		}
		for (view in sizings.keys.toList()) {
			if (view.isAutoSized()) continue
			val sizing = sizingFor(view)
			if (sizing.applied != scale) apply(view, sizing, scale)
		}
	}

	/**
	 * Brings every text view under [root] to the current scale: new ones, and ones the plugin
	 * resized. At scale 1 there is nothing to do: plugin text is drawn at its own size, and a scale
	 * change leaves no view at an old one. Returns whether any changed.
	 */
	private fun scaleNewText(root: View): Boolean {
		if (scale == 1f) return false
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
		view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.base * scale)
		sizing.applied = scale
	}

	private fun TextView.isAutoSized() = autoSizeTextType != TextView.AUTO_SIZE_TEXT_TYPE_NONE

	private val attachListener =
		object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(v: View) = startWatching(v)

			override fun onViewDetachedFromWindow(v: View) = stopWatching(v)
		}

	private fun startWatching(root: View) {
		val observer = root.viewTreeObserver
		val watcher =
			windowWatchers.getOrPut(observer) {
				WindowWatcher().also { observer.addOnPreDrawListener(it) }
			}
		watcher.roots.add(root)
	}

	// Found by its roots: once a view has left its window, root.viewTreeObserver is a different
	// observer, which could not remove the listener.
	private fun stopWatching(root: View) {
		val entry = windowWatchers.entries.firstOrNull { root in it.value.roots } ?: return
		val (observer, watcher) = entry
		watcher.roots.remove(root)
		if (watcher.roots.isEmpty()) {
			if (observer.isAlive) observer.removeOnPreDrawListener(watcher)
			windowWatchers.remove(observer)
		}
	}

	/**
	 * Catches views added under the tracked roots in one window before the frame that would draw
	 * them. A pre-draw listener, not a layout one: a scrolling RecyclerView or ListView adds rows
	 * without any layout pass. A frame in which something was resized is skipped, so its text is
	 * never drawn at the old size; the next one is laid out for the new size. Never two in a row,
	 * though: a plugin that sets its own size on every layout would otherwise have every frame
	 * skipped, and the window would stop drawing.
	 *
	 * One per window, not one per root: the window skips a frame if any of its listeners asks it
	 * to, so two roots each skipping every other frame, a frame apart, would skip them all. Plugin
	 * dialogs and bottom sheets are windows of their own, so each window decides for itself.
	 */
	private inner class WindowWatcher : ViewTreeObserver.OnPreDrawListener {
		val roots = LinkedHashSet<View>()
		private var skippedLast = false

		// Hidden text is caught on the frame that first shows it.
		override fun onPreDraw(): Boolean {
			var changed = false
			for (root in roots) {
				if (root.isShown && !isInsideAnotherRoot(root) && scaleNewText(root)) changed = true
			}
			val skip = changed && !skippedLast
			skippedLast = skip
			return !skip
		}

		// A plugin fragment nested in another's view is tracked too; the outer walk covers it.
		private fun isInsideAnotherRoot(root: View): Boolean {
			var parent = root.parent
			while (parent is View) {
				if (parent in roots) return true
				parent = parent.parent
			}
			return false
		}
	}
}
