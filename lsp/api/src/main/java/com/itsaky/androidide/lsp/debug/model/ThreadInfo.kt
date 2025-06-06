package com.itsaky.androidide.lsp.debug.model

import java.util.Stack

/**
 * Any [DebugEvent] that has a thread associated with it.
 */
interface HasThreadInfo {

    /**
     * The unique ID of the thread associated with this event.
     */
    val threadId: String
}

/**
 * Information about a stack frame.
 *
 * @property method The name of the method.
 * @property methodSignature The signature of the method.
 * @property sourceFile The name of the source file.
 * @property lineNumber The line number in the source file.
 */
data class StackFrameDescriptor(
    val method: String,
    val methodSignature: String,
    val sourceFile: String,
    val lineNumber: Long,
)

/**
 * A stack frame in the call stack.
 */
interface StackFrame {

    /**
     * Get the descriptor for this stack frame.
     */
    suspend fun descriptor(): StackFrameDescriptor

    /**
     * Get the visible variables in this call frame.
     */
    suspend fun getVariables(): List<Variable<*>>

    /**
     * Set the value of the given variable.
     *
     * @param variable The variable to set the value of.
     * @param value The value to set the variable to.
     */
    suspend fun <Val: Value> setValue(variable: Variable<Val>, value: Val)
}

/**
 * Information about a thread.
 *
 * @property id The unique ID of the thread.
 * @property name The name of the thread.
 * @property group The name of the thread group.
 * @property state The state of the thread.
 */
data class ThreadDescriptor(
    val id: String,
    val name: String,
    val group: String,

    // TODO: Maybe this could be converted to an enum value instead?
    val state: String,
)

/**
 * Information about a thread.
 */
interface ThreadInfo {

    /**
     * Get the descriptor for this thread.
     */
    suspend fun descriptor(): ThreadDescriptor

    /**
     * Get the call frames of this thread.
     */
    suspend fun getFrames(): List<StackFrame>
}
