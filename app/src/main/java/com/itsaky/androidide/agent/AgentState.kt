package com.itsaky.androidide.agent

/**
 * Represents the various states of the AI agent during an operation.
 */
sealed class AgentState {
    object Idle : AgentState()
    data class Processing(val message: String) : AgentState()

    /**
     * A new state to indicate that a cancellation has been requested.
     * The UI will observe this to disable the stop button immediately.
     */
    object Cancelling : AgentState()

    data class Error(val message: String) : AgentState()
}