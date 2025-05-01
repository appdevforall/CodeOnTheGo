package com.itsaky.androidide.lsp.debug.events

import com.itsaky.androidide.lsp.debug.RemoteClient
import com.itsaky.androidide.lsp.debug.model.Source

/**
 * Parameters for when a breakpoint is hit in a target application.
 *
 * @property client The client in which the breakpoint as hit.
 * @property descriptor The descriptor describing the breakpoint event.
 * @author Akash Yadav
 */
data class BreakpointHitEvent(
    override val remoteClient: RemoteClient,
    override val threadInfo: ThreadInfo,
    val descriptor: BreakpointDescriptor,
): EventOrResponse, HasThreadInfo

/**
 * Describes a breakpoint that was hit.
 *
 * @property source The source file in which the breakpoint was hit.
 * @property line The line number in source file.
 * @property column The column number in line.
 */
data class BreakpointDescriptor(
    val source: Source,
    val line: Int,
    val column: Int? = null,
)

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
