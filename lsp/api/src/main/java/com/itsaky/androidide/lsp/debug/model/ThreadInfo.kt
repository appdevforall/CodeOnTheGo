package com.itsaky.androidide.lsp.debug.model

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

    /**
     * Set the value of the variable.
     */
    fun setValue(value: String)
}

/**
 * A stack frame in the call stack.
 */
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

    /**
     * Set the value of the given variable.
     *
     * @param variable The variable to set the value of.
     * @param value The value to set the variable to.
     */
    fun setValue(variable: LocalVariable, value: String)
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
