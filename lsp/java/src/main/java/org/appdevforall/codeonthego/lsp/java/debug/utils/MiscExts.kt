package org.appdevforall.codeonthego.lsp.java.debug.utils

import org.appdevforall.codeonthego.lsp.debug.model.StepType
import org.appdevforall.codeonthego.lsp.debug.model.SuspendPolicy
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest

/**
 * Converts a [SuspendPolicy] to a [EventRequest] suspend policy integer.
 */
fun SuspendPolicy.asJdiInt() = when (this) {
    SuspendPolicy.None -> EventRequest.SUSPEND_NONE
    SuspendPolicy.Thread -> EventRequest.SUSPEND_EVENT_THREAD
    SuspendPolicy.All -> EventRequest.SUSPEND_ALL
}

/**
 * Converts a [StepType] to a [StepRequest] integer.
 */
fun StepType.asDepthInt() = when (this) {
    StepType.Over -> StepRequest.STEP_OVER
    StepType.Into -> StepRequest.STEP_INTO
    StepType.Out -> StepRequest.STEP_OUT
}
