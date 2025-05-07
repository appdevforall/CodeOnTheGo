package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.model.HasThreadInfo
import com.itsaky.androidide.lsp.debug.model.LocatableEvent
import com.itsaky.androidide.lsp.debug.model.Location
import com.itsaky.androidide.lsp.debug.model.ResumePolicy

/**
 * Parameters for when a breakpoint is hit in a target application.
 *
 * @author Akash Yadav
 */
data class BreakpointHitEvent(
    override val remoteClient: RemoteClient,
    override val threadId: String,
    override val location: Location,
): EventOrResponse, LocatableEvent, HasThreadInfo

/**
 * Response to a breakpoint hit event.
 *
 * @property remoteClient The client in which the breakpoint was hit.
 * @property resumePolicy The policy to resume the execution.
 */
data class BreakpointHitResponse(
    override val remoteClient: RemoteClient,
    val resumePolicy: ResumePolicy,
): DebugEventResponse
