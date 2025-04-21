package com.itsaky.androidide.lsp.debug.model

enum class SuspendPolicy {
    /**
     * Do not suspend,
     */
    None,

    /**
     * Suspend on the thread where the breakpoint is hit.
     */
    Thread,

    /**
     * Suspend the whole program.
     */
    All
}

/**
 * Defines the request to set breakpoints in source code.
 *
 * @property breakpoints The breakpoints to set.
 * @author Akash Yadav
 */
data class BreakpointRequest(
    val breakpoints: List<SourceBreakpoint>,
)

/**
 * Defines a breakpoint in source code.
 *
 * @property source The source of the breakpoint.
 * @property line The line number of the breakpoint.
 * @property column The column number of the breakpoint.
 * @property condition The condition to evaluate for the breakpoint.
 * @property hitCondition The condition to evaluate when the breakpoint is hit.
 * @property logMessage The message to log when the breakpoint is hit.
 */
data class SourceBreakpoint(
    val source: Source,
    val line: Int,
    val column: Int,
    val condition: String?,
    val hitCondition: String?,
    val logMessage: String?,
)
