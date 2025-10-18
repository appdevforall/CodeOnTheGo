package com.itsaky.androidide.agent.repository

import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ApprovalId
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.events.ExecCommandEvent
import com.itsaky.androidide.agent.model.ReviewDecision
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight repository that replays the stored chat history so the UI can display messages even
 * before a full agent backend is created.
 */
internal class SessionHistoryRepository : GeminiRepository {
    override var onStateUpdate: ((AgentState) -> Unit)? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _plan = MutableStateFlow<Plan?>(null)
    override val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private val _execEvents = MutableSharedFlow<ExecCommandEvent>(replay = 0)
    override val execEvents: SharedFlow<ExecCommandEvent> = _execEvents

    override fun loadHistory(history: List<ChatMessage>) {
        _messages.value = history
    }

    override fun getPartialReport(): String = ""

    override suspend fun generateASimpleResponse(prompt: String, history: List<ChatMessage>) {
        error("SessionHistoryRepository cannot generate responses.")
    }

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String = error("SessionHistoryRepository cannot generate code.")

    override fun submitApprovalDecision(id: ApprovalId, decision: ReviewDecision) {
        // Nothing to do – session replay doesn't handle approvals.
    }

    override fun stop() {
        // Nothing to stop for the in-memory session replay.
    }
}
