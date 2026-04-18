package com.itsaky.androidide.dnd

import android.view.DragEvent
import android.view.View

/**
 * Callback interface for handling routed drag-and-drop events on a specific target view.
 */
interface DropTargetCallback {
    /**
     * Determines whether the current [event] contains data that this target can handle.
     */
    fun canAcceptDrop(event: DragEvent): Boolean

    /**
     * Called when a drag operation begins. Useful for applying initial visual cues.
     */
    fun onDragStarted(view: View) {}

    /**
     * Called when a valid dragged item enters the bounds of the target [view].
     */
    fun onDragEntered(view: View)

    /**
     * Called when a dragged item exits the target [view], or when the drag operation ends/is canceled.
     */
    fun onDragExited(view: View)

    /**
     * Called when the user successfully drops a valid item on the target.
     * @return True if the drop was successfully consumed and handled.
     */
    fun onDrop(event: DragEvent): Boolean
}
