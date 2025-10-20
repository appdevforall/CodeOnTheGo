package com.itsaky.androidide.agent.prompt

import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.optionals.getOrNull

private val jsonFormatter = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Structured representation of the prompt that will be delivered to the LLM.
 */
class Prompt(
    val input: List<ResponseItem>,
    val tools: List<Tool>,
    val parallelToolCalls: Boolean,
    private val baseInstructionsOverride: String?,
    val outputSchema: JsonObject?
) {

    /**
     * Generates the complete instruction set for the target model, including overrides and
     * model-specific guidance.
     */
    fun getFullInstructions(model: ModelFamily): String {
        val builder = StringBuilder()
        val base = baseInstructionsOverride ?: model.baseInstructions
        builder.append(base.trimEnd())

        outputSchema?.let { schema ->
            builder.append("\n\n[Output Schema]\n")
            builder.append("You MUST respond with a single, valid JSON object that conforms to the following JSON Schema:\n```json\n")
            builder.append(jsonFormatter.encodeToString(JsonObject.serializer(), schema))
            builder.append("\n```\n")
        }

        if (model.needsSpecialApplyPatchInstructions) {
            val hasApplyPatchTool = tools.any { tool ->
                tool.functionDeclarations()
                    .getOrNull()
                    ?.any { declaration -> declaration.name().getOrNull() == "apply_patch" }
                    ?: false
            }

            if (!hasApplyPatchTool) {
                builder.append("\n[Special Instructions]\n")
                builder.append("If you need to modify a file, use the 'update_file' tool and provide the complete new content for the file.\n")
            }
        }

        return builder.toString()
    }

    /**
     * Applies semantic formatting to the conversation input.
     */
    fun getFormattedInput(): List<ResponseItem> {
        if (input.isEmpty()) return emptyList()

        val lastUserIndex = input.indexOfLast { item ->
            item is ResponseItem.Message && item.role == "user"
        }

        return input.mapIndexed { index, item ->
            if (index == lastUserIndex && item is ResponseItem.Message && item.role == "user") {
                item.copy(
                    content = buildString {
                        append("<user_instructions>\n")
                        append(item.content)
                        append("\n</user_instructions>")
                    }
                )
            } else {
                item
            }
        }
    }
}

/**
 * Factory method that assembles a [Prompt] from a turn context and raw conversation input.
 */
fun buildPrompt(turn: TurnContext, input: List<ResponseItem>): Prompt {
    val combinedTools = if (turn.externalTools.isEmpty()) {
        turn.toolsConfig
    } else {
        buildList {
            addAll(turn.toolsConfig)
            addAll(turn.externalTools)
        }
    }

    return Prompt(
        input = input,
        tools = combinedTools,
        parallelToolCalls = turn.modelFamily.supportsParallelToolCalls,
        baseInstructionsOverride = turn.baseInstructionsOverride,
        outputSchema = turn.finalOutputJSONSchema
    )
}

/**
 * Transforms the structured prompt into the `List<Content>` payload expected by the Gemini chat API.
 */
fun buildMessagesForChatAPI(prompt: Prompt, model: ModelFamily): List<Content> {
    val formattedInput = prompt.getFormattedInput()
    require(formattedInput.isNotEmpty()) { "Input cannot be empty." }

    val systemInstruction = prompt.getFullInstructions(model).trimEnd()
    val messages = mutableListOf<Content>()

    val first = formattedInput.first()
    if (first !is ResponseItem.Message || first.role != "user") {
        throw IllegalStateException("Conversation history must start with a user message.")
    }

    messages += Content.builder()
        .role("user")
        .parts(
            Part.builder()
                .text(
                    buildString {
                        append(systemInstruction)
                        if (systemInstruction.isNotBlank()) append("\n\n")
                        append(first.content)
                    }
                )
                .build()
        )
        .build()

    formattedInput.drop(1).forEach { item ->
        messages += when (item) {
            is ResponseItem.Message -> {
                val mappedRole = when (item.role) {
                    "assistant" -> "model"
                    else -> item.role
                }
                Content.builder()
                    .role(mappedRole)
                    .parts(Part.builder().text(item.content).build())
                    .build()
            }

            is ResponseItem.FunctionCall -> {
                Content.builder()
                    .role("model")
                    .parts(
                        Part.fromFunctionCall(item.name, item.arguments)
                    )
                    .build()
            }

            is ResponseItem.FunctionCallOutput -> {
                Content.builder()
                    .role("tool")
                    .parts(
                        Part.fromFunctionResponse(
                            item.name,
                            mapOf("output" to item.output)
                        )
                    )
                    .build()
            }

            is ResponseItem.Reasoning -> {
                Content.builder()
                    .role("model")
                    .parts(Part.builder().text(item.content).build())
                    .build()
            }
        }
    }

    return messages
}
