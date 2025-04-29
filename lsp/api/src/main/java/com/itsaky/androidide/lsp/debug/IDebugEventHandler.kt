package com.itsaky.androidide.lsp.debug

import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse

/**
 * A debug event handler consumes debug events for debugging applications.
 *
 * @author Akash Yadav
 */
interface IDebugEventHandler {

    /**
     * Called when a breakpoint is hit in the target application.
     *
     * @param event The parameters describing the breakpoint event.
     */
    fun onBreakpointHit(event: BreakpointHitEvent): BreakpointHitResponse
}