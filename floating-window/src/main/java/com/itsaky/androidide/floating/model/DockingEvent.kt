
package com.itsaky.androidide.floating.model

/**
 * A one-shot intent emitted when a floating window leaves the floating set, telling the editor
 * activity what to do with the underlying tab. Removal from [DockingManager.windows] only says the
 * window is gone; this says *why*.
 */
sealed interface DockingEvent {
	val content: DockableContent

	/** The tab should return to the docked editor strip. */
	data class Redock(
		override val content: DockableContent,
	) : DockingEvent

	/** The tab should be closed entirely, as if its docked close button were pressed. */
	data class Close(
		override val content: DockableContent,
	) : DockingEvent
}
