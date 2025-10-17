package com.itsaky.androidide.agent.prompt

import com.google.genai.types.Tool
import kotlinx.serialization.json.JsonObject

/**
 * Represents an entry in the conversation transcript that will be fed to the model.
 */
sealed interface ResponseItem {
    data class Message(val role: String, val content: String) : ResponseItem
    data class FunctionCall(val name: String, val arguments: Map<String, Any>) : ResponseItem
    data class FunctionCallOutput(val name: String, val output: String) : ResponseItem
    data class Reasoning(val content: String) : ResponseItem
}

/**
 * Alias used to clarify intent when referring to tools for a given turn.
 */
typealias ToolSpec = Tool

/**
 * Defines model-family level capabilities as well as the default instructions shipped with it.
 */
data class ModelFamily(
    val id: String,
    val baseInstructions: String,
    val supportsParallelToolCalls: Boolean,
    val needsSpecialApplyPatchInstructions: Boolean = false
)

/**
 * Encapsulates everything required to construct the prompt for a single turn.
 */
data class TurnContext(
    val modelFamily: ModelFamily,
    val toolsConfig: List<ToolSpec>,
    val externalTools: List<ToolSpec> = emptyList(),
    val baseInstructionsOverride: String? = null,
    val finalOutputJSONSchema: JsonObject? = null
)
