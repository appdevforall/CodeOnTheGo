package com.itsaky.androidide.lsp.debug.model

/**
 * Result of a single breakpoint request.
 */
sealed interface BreakpointResult {

    /**
     * The [BreakpointDefinition] this result is associated with.
     */
    val definition: BreakpointDefinition

    /**
     * A breakpoint was added.
     *
     * @property isDeferred Whether the breakpoint resolution was deferred.
     */
    data class Added(
        override val definition: BreakpointDefinition,
        val isDeferred: Boolean,
    ): BreakpointResult

    /**
     * A breakpoint was removed.
     */
    data class Removed(
        override val definition: BreakpointDefinition,
    ): BreakpointResult

    /**
     * The breakpoint action failed.
     *
     * @property cause The cause of the failure.
     */
    data class Failure(
        override val definition: BreakpointDefinition,
        val cause: Throwable?,
    ): BreakpointResult
}

/**
 * Defines the response to a [BreakpointRequest].
 *
 * @property results The results of individual breakpoint requests.
 * @author Akash Yadav
 */
data class BreakpointResponse(
    val results: List<BreakpointResult>
): DapResponse {
    companion object {
        val EMPTY = BreakpointResponse(emptyList())
    }
}
