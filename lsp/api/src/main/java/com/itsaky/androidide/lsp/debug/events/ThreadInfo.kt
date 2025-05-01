package com.itsaky.androidide.lsp.debug.events

/**
 * Reference to an object in the debugger.
 *
 * This interface is as marker interface, and doesn't provide any properties or functions. It is
 * expected to be passed to and used by the debug adapter to resolve references.
 */
interface ObjectReference

/**
 * Any [DebugEvent] that has a thread associated with it.
 */
interface HasThreadInfo {

    /**
     * The thread information.
     */
    val threadInfo: ThreadInfo
}

/**
 * Information about a local variable.
 */
interface LocalVariable {

    /**
     * The name of the variable.
     */
    val name: String

    /**
     * The type of the variable.
     */
    val type: String

    /**
     * Get the value of the variable.
     */
    fun getValue(): String
}

/**
 * Information about a thread.
 */
interface ThreadInfo {

    /**
     * The name of the thread.
     */
    fun getName(): String

    /**
     * Get the call frames of this thread.
     */
    fun getFrames(): List<StackFrame>
}

interface StackFrame {

    /**
     * Get the visible variables in this call frame.
     */
    fun getVariables(): List<LocalVariable>

    /**
     * Get the values of the visible variables in this call frame.
     */
    fun getVariableValues(): Map<LocalVariable, String>

    /**
     * Get the value of the given variable.
     */
    fun getValue(variable: LocalVariable): String
}
