package com.itsaky.androidide.agent.repository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ParsedToolCall(
    @SerialName("tool_name") val name: String,
    val args: Map<String, JsonElement>? = null
)