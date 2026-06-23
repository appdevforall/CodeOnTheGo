
package com.itsaky.androidide.floating.model

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import com.itsaky.androidide.floating.window.FloatingWindowHost

/**
 * The contract a tab must satisfy to be hosted in a floating overlay window.
 *
 * Implementations live in the `app` module (e.g. an adapter over a `CodeEditorView`/`IEditorPanel`
 * or a plugin Fragment); the floating window system renders them without knowing anything about
 * editors or plugins.
 *
 * [onCreateView] receives the window-scoped, long-lived themed [Context] — built from
 * [Context.createWindowContext] and therefore NOT tied to the IDE activity. Implementations should
 * build their content view against this context so it survives the activity being destroyed while
 * the window floats over another app.
 */
interface DockableContent {
	/** Stable identifier, shared with the docked tab this content was undocked from. */
	val id: String

	/** Human-readable title shown in the window chrome. */
	val title: String

	/** Optional icon shown in the window chrome. */
	val icon: Drawable?
		get() = null

	/**
	 * Optional content actions (e.g. Save, Run) shown in the title bar. The window is "capability
	 * aware": it renders exactly the actions its content advertises, and none if this is empty.
	 */
	val actions: List<DockAction>
		get() = emptyList()

	/** Build the content view for the window, against the supplied window-scoped themed context. */
	fun onCreateView(
		context: Context,
		host: FloatingWindowHost,
	): View

	/** Called when the window is being torn down (closed or re-docked). */
	fun onDestroyView() {}
}
