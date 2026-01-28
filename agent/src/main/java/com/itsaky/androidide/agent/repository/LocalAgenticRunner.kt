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
import com.itsaky.androidide.app.BaseApplication
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
        id = LOCAL_LLM_MODEL_ID,
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

        // If simplified instructions are enabled, use the simpler workflow
        if (shouldUseSimplifiedInstructions()) {
            runSimplifiedWorkflow(prompt, history)
        } else {
            super.generateASimpleResponse(prompt, history)
        }
    }

    /**
     * Simplified workflow that bypasses multi-step planning for simple queries.
     * Makes a single LLM call to either respond directly or select a tool.
     */
    private suspend fun runSimplifiedWorkflow(prompt: String, history: List<ChatMessage>) {
        onStateUpdate?.invoke(AgentState.Initializing("Processing request..."))
        loadHistory(history)
        addMessage(prompt, Sender.USER)

        try {
            // Select relevant tools based on query intent
            val relevantTools = selectToolsForQuery(prompt)

            // Build simplified prompt
            val simplifiedPrompt = buildSimplifiedPrompt(prompt, history, relevantTools)

            // Single LLM call for tool selection or direct response
            onStateUpdate?.invoke(AgentState.Thinking("Analyzing request..."))
            val responseText = engine.runInference(
                simplifiedPrompt,
                stopStrings = listOf(
                    "</tool_call>",  // Stop after closing tool_call tag
                    "\nuser:",        // Stop if model starts hallucinating new user turn
                    "\nUser:",        // Alternative capitalization
                    "\n\n"           // Stop at double newline for direct answers
                )
            )

            // Try to parse as tool call
            val relevantToolNames = relevantTools.map { it.name }.toSet()
            val parsedCall = Util.parseToolCall(responseText, relevantToolNames)

            if (parsedCall != null) {
                // Tool call parsed successfully - execute it
                log.info("Simplified workflow: executing tool '{}'", parsedCall.name)
                onStateUpdate?.invoke(AgentState.Thinking("Using ${parsedCall.name}..."))

                val toolResult = executeToolCall(parsedCall)
                val resultMessage = formatToolResult(parsedCall, toolResult)

                addMessage(resultMessage, Sender.AGENT)
                onStateUpdate?.invoke(AgentState.Idle)
            } else {
                // No tool call - treat as direct response
                log.info("Simplified workflow: direct response (no tool needed)")
                val cleanResponse = responseText.trim().takeWhile { it != '<' }.trim()
                addMessage(cleanResponse, Sender.AGENT)
                onStateUpdate?.invoke(AgentState.Idle)
            }

        } catch (err: Exception) {
            log.error("Simplified workflow failed", err)
            onStateUpdate?.invoke(AgentState.Error("Failed to process request: ${err.message}"))
            addMessage("I encountered an error: ${err.message}", Sender.AGENT)
        }
    }

    /**
     * Select relevant tools based on the user's query intent.
     * Returns only general assistant tools for non-IDE queries.
     */
    private fun selectToolsForQuery(query: String): List<LocalToolDeclaration> {
        val lowerQuery = query.lowercase()

        // Keywords that suggest IDE-related tasks
        val ideKeywords = listOf(
            "file", "build", "gradle", "dependency", "dependencies", "run", "debug",
            "code", "create", "edit", "modify", "delete", "class", "function",
            "project", "compile", "install", "add", "remove", "import"
        )

        val isIdeQuery = ideKeywords.any { lowerQuery.contains(it) }

        return if (isIdeQuery) {
            // Show all tools for IDE-related queries
            toolsForPrompt
        } else {
            // Show only general assistant tools for simple queries
            val generalToolNames =
                setOf("get_current_datetime", "get_device_battery", "get_weather")
            toolsForPrompt.filter { it.name in generalToolNames }
        }
    }

    /**
     * Build a simplified prompt for direct tool calling without multi-step planning.
     * Uses the structure from the "desired" behavior with examples.
     */
    private fun buildSimplifiedPrompt(
        userMessage: String,
        history: List<ChatMessage>,
        tools: List<LocalToolDeclaration>
    ): String {
        val toolsJson = tools.joinToString(",\n  ") { tool ->
            val escapedDescription = tool.description.replace("\"", "\\\"")
            val args = if (tool.parameters.isEmpty()) {
                ""
            } else {
                val formattedArgs = tool.parameters.entries.joinToString(", ") { (name, desc) ->
                    val escaped = desc.replace("\"", "\\\"")
                    "\"$name\": \"$escaped\""
                }
                ", \"args\": { $formattedArgs }"
            }
            "{ \"name\": \"${tool.name}\", \"description\": \"$escapedDescription\"$args }"
        }

        // Include recent conversation history for context
        val conversationHistory = if (history.isEmpty()) {
            ""
        } else {
            history.takeLast(5).joinToString("\n") { msg ->
                val senderName = when (msg.sender) {
                    Sender.USER -> "user"
                    Sender.AGENT -> "model"
                    Sender.TOOL -> "tool"
                    else -> "system"
                }
                "$senderName: ${msg.text}"
            } + "\n"
        }

        return """
You are a helpful assistant. You can answer questions directly or use tools when needed.

Available tools:
[
  $toolsJson
]

To use a tool, respond with a single <tool_call> XML tag containing a JSON object.
If no tool is needed, answer the user's question directly.

Respond ONLY with a single <tool_call> tag OR your direct text answer. Do not add any other text before or after.

EXAMPLE:
user: What is the weather like in Paris?
model: <tool_call>{"name": "get_weather", "args": {"city": "Paris"}}</tool_call>

user: What time is it?
model: <tool_call>{"name": "get_current_datetime", "args": {}}</tool_call>

user: Hello!
model: Hello! How can I help you today?

**CONVERSATION:**
${conversationHistory}user: $userMessage
model: """.trimIndent()
    }

    /**
     * Execute a tool call and return the result.
     */
    private suspend fun executeToolCall(toolCall: LocalLLMToolCall): String {
        return try {
            // Convert LocalLLMToolCall to FunctionCall
            val functionCall = FunctionCall.builder()
                .name(toolCall.name)
                .args(toolCall.args)
                .build()

            // Execute using the base class executor
            val results = executor.execute(listOf(functionCall))

            // Format the results
            if (results.isEmpty()) {
                "Tool executed successfully."
            } else {
                results.joinToString("\n") { part ->
                    when {
                        part.text().isPresent -> part.text().get()
                        part.functionResponse().isPresent -> {
                            val response = part.functionResponse().get()
                            response.response().toString()
                        }

                        else -> "Tool executed."
                    }
                }
            }
        } catch (err: Exception) {
            log.error("Tool execution failed for '{}'", toolCall.name, err)
            "Error executing tool: ${err.message}"
        }
    }

    /**
     * Format tool result into a user-friendly message.
     */
    private fun formatToolResult(toolCall: LocalLLMToolCall, result: String): String {
        return when (toolCall.name) {
            "get_current_datetime" -> result
            "get_device_battery" -> result
            "get_weather" -> result
            else -> "Result: $result"
        }
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

        // Don't catch exceptions here - let them propagate so retry logic can handle them
        // without polluting conversation history with error messages
        val responseText = engine.runInference(prompt, stopStrings = listOf("</tool_call>"))
        val parsedCall = Util.parseToolCall(responseText, toolNames)

        return if (parsedCall != null) {
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
    }

    /**
     * Always use simplified workflow for Local LLM to avoid "max steps" errors.
     * The multi-step planning loop is designed for more capable cloud models (Gemini).
     * Local LLMs work better with the single-call simplified workflow.
     */
    override fun shouldUseSimplifiedInstructions(): Boolean {
        // Force simplified workflow for Local LLM - the multi-step loop
        // with maxSteps=20 is too complex for on-device models and often
        // results in step retry exhaustion errors.
        return true
    }

    override fun buildSimplifiedInstructionOverride(): String {
        val toolDescriptions = toolsForPrompt.joinToString(",\n  ") { tool ->
            val escapedDescription = tool.description.replace("\"", "\\\"")
            val args = if (tool.parameters.isEmpty()) {
                ""
            } else {
                val formattedArgs = tool.parameters.entries.joinToString(", ") { (name, desc) ->
                    val escaped = desc.replace("\"", "\\\"")
                    "\"$name\": \"$escaped\""
                }
                ", \"arguments\": { $formattedArgs }"
            }
            "{ \"name\": \"${tool.name}\", \"description\": \"$escapedDescription\"$args }"
        }

        return buildString {
            append("You are a helpful assistant with access to the following tools:\n")
            append("[\n  $toolDescriptions\n]\n\n")
            append("To use a tool, respond with a single `<tool_call>` XML tag containing a JSON object with the tool's \"name\" and \"args\".\n")
            append("If no tool is needed, answer the user's question directly.\n")
        }.trimEnd()
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
