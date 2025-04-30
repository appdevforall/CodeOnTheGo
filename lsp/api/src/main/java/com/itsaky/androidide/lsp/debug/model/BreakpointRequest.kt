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
    val breakpoints: List<BreakpointDefinition>,
)

/**
 * Defines the response to a request to set breakpoints.
 */
interface BreakpointDefinition {

    /**
     * The source file to set the breakpoint in.
     */
    val source: Source

    /**
     * The policy to use when suspending the program.
     */
    val suspendPolicy: SuspendPolicy
        get() = SuspendPolicy.All

    /**
     * The condition to evaluate for the breakpoint.
     */
    val condition: String?
        get() = null

    /**
     * The condition to evaluate when the breakpoint is hit.
     */
    val hitCondition: String?
        get() = null

    /**
     * The message to log when the breakpoint is hit.
     */
    val logMessage: String?
        get() = null
}

/**
 * Defines a positional breakpoint in source code.
 *
 * @property line The line number of the breakpoint.
 * @property column The column number of the breakpoint.
 * @property condition The condition to evaluate for the breakpoint.
 * @property hitCondition The condition to evaluate when the breakpoint is hit.
 * @property logMessage The message to log when the breakpoint is hit.
 */
data class PositionalBreakpoint(
    override val source: Source,
    val line: Int,
    val column: Int = 0,
    override val condition: String? = null,
    override val hitCondition: String? = null,
    override val logMessage: String? = null,
): BreakpointDefinition

/**
 * Defines a method breakpoint in source code.
 *
 * @property methodId The ID of the method to breakpoint.
 * @property methodArgs The arguments of the method to breakpoint.
 * @property condition The condition to evaluate for the breakpoint.
 * @property hitCondition The condition to evaluate when the breakpoint is hit.
 * @property logMessage The message to log when the breakpoint is hit.
 */
data class MethodBreakpoint(
    override val source: Source,
    val methodId: String,
    val methodArgs: List<String>,
    override val condition: String? = null,
    override val hitCondition: String? = null,
    override val logMessage: String? = null,
): BreakpointDefinition
