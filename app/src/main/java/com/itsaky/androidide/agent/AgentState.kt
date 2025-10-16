package com.itsaky.androidide.agent

import com.itsaky.androidide.agent.repository.Plan

/**
 * Represents the various states of the AI agent during an operation.
 */
sealed interface AgentState {
    object Idle : AgentState
    data class Initializing(val message: String) : AgentState
    data class Thinking(val thought: String) : AgentState
    data class Executing(val plan: Plan, val currentStepIndex: Int) : AgentState
    data class AwaitingApproval(val command: String) : AgentState
    data class Error(val message: String) : AgentState
}
