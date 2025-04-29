package com.itsaky.androidide.lsp.debug.utils

/**
 * Thrown/returned when the debug adapter cannot find a given breakpoint.
 *
 * @author Akash Yadav
 */
class BreakpointNotFoundException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)