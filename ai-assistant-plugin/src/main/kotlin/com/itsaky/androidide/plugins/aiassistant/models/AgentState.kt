package com.itsaky.androidide.plugins.aiassistant.models

/**
 * Represents the current state of the AI agent.
 */
sealed class AgentState {
    object Idle : AgentState()
    data class Processing(val message: String) : AgentState()
    object Cancelling : AgentState()
    data class Error(val message: String) : AgentState()
}
