package com.itsaky.androidide.agent.repository

import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

/**
 * The "thinker" of the agent. It uses the Gemini model to decide the next steps.
 * @param client An instance of [GeminiClient] used to make LLM calls.
 * @param tools The list of available tools the planner can choose from.
 */
class Planner(
    private val client: GeminiClient,
    private val tools: List<Tool>
) {

    companion object {
        private val log = LoggerFactory.getLogger(Planner::class.java)

        private const val SYSTEM_PROMPT = """
        You are an expert AI developer agent. Your sole purpose is to analyze the user's request and the conversation history, then select the most appropriate tool and parameters to call next. 
        You MUST respond with only a valid JSON object for a tool call. Do not provide any conversational text, explanations, or markdown formatting.

        **CRITICAL RULE**: If a tool call has failed in the previous step, do NOT call the exact same tool with the exact same parameters again. You must try a different tool or different parameters to debug the problem.
    """

        private const val PLAN_PROMPT = """
        You are an expert Android developer agent. Break the conversation history and user request into a concise, ordered list of high-level tasks the agent should perform.
        Respond ONLY with a valid JSON array. Each array item must be an object with a "description" field (string).
        Optionally include "status" (default is "PENDING") and "result" (leave null or omit if unknown).
        Example:
        [
          {"description": "Inspect relevant project files"},
          {"description": "Apply required code changes"},
          {"description": "Summarize the modifications for the user"}
        ]
        """
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun generatePlan(history: List<Content>): Plan {
        log.info("Planner: Creating high-level plan...")

        val plannerHistory = mutableListOf<Content>()
        val systemInstruction = Content.builder()
            .role("user")
            .parts(Part.builder().text(PLAN_PROMPT).build())
            .build()
        plannerHistory.add(systemInstruction)
        plannerHistory.addAll(history)

        val response =
            client.generateContent(plannerHistory, tools = emptyList(), forceToolUse = false)
        val planText = response.text()?.trim()
        if (planText.isNullOrBlank()) {
            log.warn("Planner returned an empty plan response. Falling back to a single generic step.")
            return Plan(mutableListOf(TaskStep("Address the user's request.")))
        }

        val steps = parsePlanSteps(planText)
        val normalizedSteps = steps.map { it.copy(status = StepStatus.PENDING, result = null) }
        log.info("Planner produced ${normalizedSteps.size} plan steps.")
        return Plan(normalizedSteps.toMutableList())
    }

    /**
     * Given the conversation history, generate the next step (the plan).
     * The plan is the raw Content object returned by the model, typically containing a function call.
     *
     * @param history The full conversation history.
     * @return The model's response as a [Content] object.
     */
    fun plan(history: List<Content>): Content {
        log.info("Planner: Devising a plan...")

        // NEW: Create a new history list that starts with our specific instruction.
        val plannerHistory = mutableListOf<Content>()

        // NEW: Add the system prompt as the very first message.
        val systemInstruction = Content.builder()
            .role("user") // System instructions are often sent with the "user" role.
            .parts(Part.builder().text(SYSTEM_PROMPT).build())
            .build()
        plannerHistory.add(systemInstruction)

        // NEW: Add the rest of the actual conversation history after our instruction.
        plannerHistory.addAll(history)

        // Pass the new, augmented history to the client.
        val response = client.generateContent(plannerHistory, tools, forceToolUse = true)

        val candidate = response.candidates().getOrNull()?.firstOrNull()
            ?: throw IllegalStateException("API response did not contain any candidates.")

        val content = candidate.content().getOrNull()
            ?: throw IllegalStateException("Candidate did not contain any content.")

        return content
    }

    private fun parsePlanSteps(raw: String): List<TaskStep> {
        val cleaned = sanitizePlanText(raw)
        val parsed = runCatching {
            val root = jsonParser.parseToJsonElement(cleaned)
            when {
                root is JsonArray -> root.mapNotNull { parseTaskObject(it) }
                root is JsonObject && root["steps"] != null -> {
                    root["steps"]!!.jsonArray.mapNotNull { parseTaskObject(it) }
                }

                else -> fallbackPlanSteps(cleaned)
            }
        }.onFailure {
            log.warn("Failed to parse structured plan response. Falling back to text parsing.", it)
        }.getOrNull().orEmpty()

        return if (parsed.isNotEmpty()) parsed else fallbackPlanSteps(raw)
    }

    private fun parseTaskObject(element: JsonElement): TaskStep? {
        if (element !is JsonObject) return null
        val description = element["description"]?.jsonPrimitive?.contentOrNull
            ?: element["step"]?.jsonPrimitive?.contentOrNull
            ?: element["task"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val statusValue = element["status"]?.jsonPrimitive?.contentOrNull
        val resultValue = element["result"]?.jsonPrimitive?.contentOrNull
        return TaskStep(
            description = description.trim(),
            status = parseStatus(statusValue),
            result = resultValue
        )
    }

    private fun parseStatus(rawStatus: String?): StepStatus {
        if (rawStatus.isNullOrBlank()) return StepStatus.PENDING
        return runCatching { StepStatus.valueOf(rawStatus.trim().uppercase()) }
            .getOrElse { StepStatus.PENDING }
    }

    private fun sanitizePlanText(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
            cleaned = cleaned.removeSuffix("```")
        }
        return cleaned.trim()
    }

    private fun fallbackPlanSteps(raw: String): List<TaskStep> {
        val lines = raw.lines()
            .map { it.trim() }
            .map { line ->
                line.removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("• ")
                    .replace(Regex("^\\d+\\.\\s*"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            val fallback = raw.trim().ifEmpty { "Address the user's request." }
            return listOf(TaskStep(fallback))
        }
        return lines.map { TaskStep(it) }
    }
}
