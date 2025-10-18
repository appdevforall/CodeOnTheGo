package com.itsaky.androidide.agent

import com.itsaky.androidide.agent.repository.Plan

/**
 * Represents the various states of the AI agent during an operation.
 */
typealias ApprovalId = String

sealed interface AgentState {
    object Idle : AgentState
    data class Initializing(val message: String) : AgentState
    data class Thinking(val thought: String) : AgentState
    data class Executing(val plan: Plan, val currentStepIndex: Int) : AgentState
    data class AwaitingApproval(
        val id: ApprovalId,
        val toolName: String,
        val toolArgs: Map<String, Any?>,
        val reason: String = "This action requires your approval to proceed."
    ) : AgentState

    data class Error(val message: String) : AgentState
}
