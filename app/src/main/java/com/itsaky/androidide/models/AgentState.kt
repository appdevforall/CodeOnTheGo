package com.itsaky.androidide.models

/**
 * Represents the various states of the Gemini agent during an operation.
 */
sealed class AgentState {
    /** The agent is idle and ready for a new prompt. */
    data object Idle : AgentState()

    /** The agent is actively working on a request. */
    data class Processing(val message: String) : AgentState()
}