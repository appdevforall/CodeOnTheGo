package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.Part
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.prompt.ModelFamily
import com.itsaky.androidide.agent.prompt.SystemPromptProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class LocalAgenticRunner(
    context: Context,
    private val engine: LlmInferenceEngine,
    maxSteps: Int = 20,
) : BaseAgenticRunner(
    context = context,
    modelFamily = ModelFamily(
        id = "local-llm",
        baseInstructions = SystemPromptProvider.get(context),
        supportsParallelToolCalls = false,
        needsSpecialApplyPatchInstructions = false
    ),
    maxSteps = maxSteps
) {

    private val log = LoggerFactory.getLogger(LocalAgenticRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val toolsForPrompt = LocalLlmTools.allTools
    private val toolNames = toolsForPrompt.map { it.name }.toSet()

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ) {
        if (!engine.isModelLoaded) {
            log.error("Local model is not loaded; aborting run.")
            onStateUpdate?.invoke(AgentState.Error("Local LLM model is not loaded."))
            return
        }
        super.generateASimpleResponse(prompt, history)
    }

    override fun buildInitialContent(): Content {
        val transcript = messages.value.joinToString(separator = "\n") { message ->
            val prefix = when (message.sender) {
                Sender.USER -> "User"
                Sender.AGENT -> "Assistant"
                Sender.TOOL -> "Tool"
                Sender.SYSTEM, Sender.SYSTEM_DIFF -> "System"
            }
            "$prefix: ${message.text}"
        }.ifBlank { "User: " }

        return Content.builder()
            .role("user")
            .parts(Part.builder().text(transcript).build())
            .build()
    }

    override suspend fun createInitialPlan(history: List<Content>): Plan {
        val latestUserRequest = messages.value.lastOrNull { it.sender == Sender.USER }?.text?.trim()
        if (latestUserRequest.isNullOrBlank()) {
            log.warn("No user prompt available for planning; defaulting to single-step plan.")
            return Plan(mutableListOf(TaskStep("Address the user's request.")))
        }

        val planPrompt = """
            You are an expert Android developer assistant planning a task for an IDE agent.
            Break the following user request into a concise, ordered list of high-level steps.
            Respond ONLY with a JSON array. Each entry must be an object with a "description" string.

            User Request: "$latestUserRequest"

            Plan:
        """.trimIndent()

        return try {
            val raw = engine.runInference(planPrompt)
            val cleaned = extractJsonArray(raw)
            parsePlanSteps(cleaned)
        } catch (err: Exception) {
            log.error("Failed to create plan with local model.", err)
            Plan(mutableListOf(TaskStep(latestUserRequest)))
        }
    }

    override suspend fun planForStep(
        history: List<Content>,
        plan: Plan,
        stepIndex: Int
    ): Content {
        val prompt = buildToolSelectionPrompt(plan, stepIndex)

        return try {
            val responseText = engine.runInference(prompt, stopStrings = listOf("</tool_call>"))
            val parsedCall = Util.parseToolCall(responseText, toolNames)
            if (parsedCall != null) {
                val functionCall = FunctionCall.builder()
                    .name(parsedCall.name)
                    .args(parsedCall.args.mapValues { it.value })
                    .build()
                Content.builder()
                    .role("model")
                    .parts(
                        Part.builder()
                            .functionCall(functionCall)
                            .build()
                    )
                    .build()
            } else {
                Content.builder()
                    .role("model")
                    .parts(Part.builder().text(responseText.trim()).build())
                    .build()
            }
        } catch (err: Exception) {
            log.error("Failed to select tool for step {}", stepIndex, err)
            Content.builder()
                .role("model")
                .parts(
                    Part.builder()
                        .text("I encountered an error while choosing a tool: ${err.message}")
                        .build()
                )
                .build()
        }
    }

    private fun buildToolSelectionPrompt(plan: Plan, stepIndex: Int): String {
        val planSummary = plan.steps.mapIndexed { idx, step ->
            val status = step.status.name
            val resultSuffix = step.result?.let { " -> $it" } ?: ""
            "${idx + 1}. [$status] ${step.description}$resultSuffix"
        }.joinToString("\n")

        val currentStep = plan.steps.getOrNull(stepIndex)
            ?: TaskStep("Address the user's request.")

        val historyBlock = messages.value.joinToString(separator = "\n") { message ->
            val prefix = when (message.sender) {
                Sender.USER -> "User"
                Sender.AGENT -> "Assistant"
                Sender.TOOL -> "Tool"
                Sender.SYSTEM, Sender.SYSTEM_DIFF -> "System"
            }
            "$prefix: ${message.text}"
        }

        val toolList = toolsForPrompt.joinToString("\n") { tool ->
            buildString {
                append("- ${tool.name}: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    append(" (")
                    append(
                        tool.parameters.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                    )
                    append(")")
                }
            }
        }

        return """
            You are executing an IDE automation plan. Analyze the conversation history and the current step.

            Plan so far:
            $planSummary

            Current step (${stepIndex + 1}/${plan.steps.size}): "${currentStep.description}"

            Conversation History:
            $historyBlock

            Available tools:
            $toolList

            If a tool is needed, respond with a <tool_call> tag containing a JSON object with "tool_name" and "args".
            If no tool is required, reply directly with the final text answer.
        """.trimIndent()
    }

    private fun parsePlanSteps(raw: String): Plan {
        val cleaned = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        val jsonElement = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()

        if (jsonElement is JsonArray) {
            val steps = jsonElement.mapNotNull { element ->
                extractDescription(element)?.let { TaskStep(description = it) }
            }
            if (steps.isNotEmpty()) {
                return Plan(steps.toMutableList())
            }
        }

        val fallback = cleaned.lines().firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
        return Plan(mutableListOf(TaskStep(fallback ?: "Address the user's request.")))
    }

    private fun extractDescription(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null
        return obj["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: obj["step"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    private fun extractJsonArray(raw: String): String {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        return if (start != -1 && end != -1 && end > start) {
            raw.substring(start, end + 1)
        } else {
            raw
        }
    }

    override fun onRunnerStopped() {
        engine.stop()
    }

    override fun destroy() {
        super.destroy()
        CoroutineScope(Dispatchers.IO).launch {
            if (engine.isModelLoaded) {
                engine.unloadModel()
            }
        }
    }
}
