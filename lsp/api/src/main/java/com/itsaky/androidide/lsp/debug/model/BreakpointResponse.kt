package com.itsaky.androidide.lsp.debug.model

/**
 * Defines the response to a [BreakpointRequest].
 *
 * @author Akash Yadav
 */
data class BreakpointResponse(
    val breakpoints: List<Breakpoint>
)

/**
 * Defines a **created** breakpoint in source code.
 *
 * @property id The id of the breakpoint.
 * @property verified Whether the breakpoint has been verified i.e. whether the breakpoint could be set.
 * @property source The source of the breakpoint.
 * @property line The start line number of the breakpoint.
 * @property column An optional start column number of the breakpoint.
 * @property endLine An optional end line number of the breakpoint.
 * @property endColumn An optional end column number of the breakpoint.
 */
data class Breakpoint(
    val id: Int,
    val verified: Boolean,
    val source: Source,
    val line: Int,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null
)