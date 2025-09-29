package com.itsaky.androidide.models

import com.itsaky.androidide.agent.data.SimplerToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class PlanStep(
    val stepId: Int,
    val objective: String,
    val toolToUse: String,
    val parameters: Map<String, JsonElement>,
    val expectedOutputFormat: String
) {
    /**
     * Converts this PlanStep into a SimplerToolCall object.
     * This is used to reconstruct the model's JSON output for storing
     * in the conversation history, allowing the agent to remember the
     * action it just took.
     */
    fun toSimplerToolCall(): SimplerToolCall {
        return SimplerToolCall(
            name = this.toolToUse,
            parameters = JsonObject(this.parameters)
        )
    }
}