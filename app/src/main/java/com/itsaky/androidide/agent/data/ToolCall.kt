package com.itsaky.androidide.agent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A simple data class to represent a tool call request from the model.
 * This replaces the Firebase SDK's `FunctionCallPart`.
 */
@Serializable
data class ToolCall(
    val name: String,
    @SerialName("parameters")
    val args: Map<String, JsonElement>?
)


@Serializable
data class SimplerToolCall(
    val name: String,
    val parameters: JsonElement
)