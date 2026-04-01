package com.itsaky.androidide.dnd

import android.view.DragEvent
import android.view.View

/**
 * A [View.OnDragListener] implementation that delegates the complex Android drag state machine
 * into a clean [DropTargetCallback].
 */
class DragEventRouter(
    private val callback: DropTargetCallback
) : View.OnDragListener {
    override fun onDrag(view: View, event: DragEvent): Boolean {
        val canHandle = callback.canAcceptDrop(event)

        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (canHandle) callback.onDragStarted(view)
                canHandle
            }

            DragEvent.ACTION_DRAG_ENTERED -> {
                if (canHandle) callback.onDragEntered(view)
                true
            }

            DragEvent.ACTION_DRAG_LOCATION -> {
                canHandle
            }

            DragEvent.ACTION_DRAG_EXITED -> {
                callback.onDragExited(view)
                true
            }

            DragEvent.ACTION_DROP -> {
                callback.onDragExited(view)
                if (canHandle) {
                    callback.onDrop(event)
                } else {
                    false
                }
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                callback.onDragExited(view)
                canHandle
            }

            else -> false
        }
    }
}
