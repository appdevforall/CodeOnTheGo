package com.itsaky.androidide.lsp.debug

import com.itsaky.androidide.lsp.debug.events.BreakpointHitEvent
import com.itsaky.androidide.lsp.debug.events.BreakpointHitResponse
import com.itsaky.androidide.lsp.debug.events.StepEvent
import com.itsaky.androidide.lsp.debug.events.StepEventResponse

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

    /**
     * Called when a step request is successful in the target application.
     *
     * @param event The event model describing the event.
     */
    fun onStep(event: StepEvent): StepEventResponse
}