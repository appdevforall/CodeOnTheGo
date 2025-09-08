package com.itsaky.androidide.agent.data

import kotlinx.serialization.json.JsonElement

/**
 * A simple data class to represent a tool call request from the model.
 * This replaces the Firebase SDK's `FunctionCallPart`.
 */
data class ToolCall(
    val name: String,
    val args: Map<String, JsonElement>
)