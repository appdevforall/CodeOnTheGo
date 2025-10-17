package com.itsaky.androidide.agent.repository

import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ApprovalId
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.events.ExecCommandEvent
import com.itsaky.androidide.agent.model.ReviewDecision
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface GeminiRepository {
    var onStateUpdate: ((AgentState) -> Unit)?

    val messages: StateFlow<List<ChatMessage>>
    val plan: StateFlow<Plan?>
    val execEvents: SharedFlow<ExecCommandEvent>

    /**
     * Replace the in-memory history with the provided messages so observers immediately reflect the
     * selected chat session.
     */
    fun loadHistory(history: List<ChatMessage>)

    fun getPartialReport(): String
    suspend fun generateASimpleResponse(prompt: String, history: List<ChatMessage>)
    suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String

    fun submitApprovalDecision(id: ApprovalId, decision: ReviewDecision)

    fun stop()

    fun destroy() {
        stop()
    }
}
