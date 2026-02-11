package com.itsaky.androidide.agent.repository

import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import kotlinx.coroutines.flow.StateFlow

interface GeminiRepository {
    var onStateUpdate: ((AgentState) -> Unit)?

    val messages: StateFlow<List<ChatMessage>>

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

    fun stop()

    /**
     * Release any resources held by the repository.
     */
    fun destroy() {
        // Default no-op to keep implementations lightweight.
    }
}
