
package com.itsaky.androidide.floating.model

import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * A content-provided action shown in a floating window's title bar (e.g. Save, Run).
 *
 * Actions are how a window advertises its capabilities: a file editor contributes Save/Run, a
 * plugin tab may contribute none. The window chrome renders one button per action, so what a window
 * can do is driven entirely by its [DockableContent].
 *
 * @property iconRes A drawable resource id (from any module on the classpath) for the button icon.
 * @property confirmIconRes If non-null and [onInvoke] returns `true`, the button briefly swaps to
 *   this icon (e.g. a checkmark) as success feedback, then reverts.
 * @property activeIconRes Icon shown while [active] emits `true` (e.g. a stop icon during a build).
 * @property active Optional observable that, when `true`, marks this action as running.
 * @property onLongPress Optional long-press handler given the anchor [View], e.g. to show a tooltip.
 * @property onInvoke Performs the action; returns `true` to play the [confirmIconRes] confirmation.
 */
class DockAction(
	val id: String,
	val label: String,
	val iconRes: Int,
	val confirmIconRes: Int? = null,
	val activeIconRes: Int? = null,
	val active: StateFlow<Boolean>? = null,
	val onLongPress: ((View) -> Unit)? = null,
	val onInvoke: suspend () -> Boolean,
)
