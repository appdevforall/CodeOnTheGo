package com.itsaky.androidide.lsp.java.debug.utils

import com.itsaky.androidide.lsp.debug.model.SuspendPolicy
import com.sun.jdi.request.EventRequest

/**
 * Converts a [SuspendPolicy] to a [EventRequest] suspend policy integer.
 */
fun SuspendPolicy.asJdiInt() = when (this) {
    SuspendPolicy.None -> EventRequest.SUSPEND_NONE
    SuspendPolicy.Thread -> EventRequest.SUSPEND_EVENT_THREAD
    SuspendPolicy.All -> EventRequest.SUSPEND_ALL
}