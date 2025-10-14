package com.itsaky.androidide.agent.repository

import com.itsaky.androidide.agent.data.ToolCall
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage

interface GeminiRepository {
    var onStateUpdate: ((AgentState) -> Unit)?
    var onToolCall: ((ToolCall) -> Unit)?
    var onToolMessage: ((String) -> Unit)?
    var onAskUser: ((question: String, options: List<String>) -> Unit)?
    var onProgressUpdate: ((message: ChatMessage) -> Unit)?

    fun getPartialReport(): String
    suspend fun generateASimpleResponse(prompt: String, history: List<ChatMessage>): AgentResponse
    suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String

    fun stop()
}