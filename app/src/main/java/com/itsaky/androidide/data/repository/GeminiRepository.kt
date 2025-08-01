package com.itsaky.androidide.data.repository

import com.google.firebase.ai.type.FunctionCallPart
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage

data class AgentResponse(val text: String, val report: String)

interface GeminiRepository {
    var onStateUpdate: ((AgentState) -> Unit)?
    var onToolCall: ((FunctionCallPart) -> Unit)?
    var onToolMessage: ((String) -> Unit)?
    var onAskUser: ((question: String, options: List<String>) -> Unit)?
    fun getPartialReport(): String
    suspend fun generateASimpleResponse(prompt: String, history: List<ChatMessage>): AgentResponse
    suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String
}