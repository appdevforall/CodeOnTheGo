package com.itsaky.androidide.agent.repository

data class LocalLLMToolCall(
    val name: String,
    val args: Map<String, String>
)
