package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.Part
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.model.ExplorationKind
import com.itsaky.androidide.agent.model.ExplorationMetadata
import com.itsaky.androidide.agent.model.ToolResult
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
        id = LOCAL_LLM_MODEL_ID,
        baseInstructions = SystemPromptProvider.get(context),
        supportsParallelToolCalls = false,
        needsSpecialApplyPatchInstructions = false
    ),
    maxSteps = maxSteps
) {
    private companion object {
        private const val MAX_HISTORY_ITEMS = 2
        private const val MAX_MESSAGE_CHARS = 400
        private const val MAX_USER_PROMPT_CHARS = 800
    }

    private val log = LoggerFactory.getLogger(LocalAgenticRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val toolsForPrompt = LocalLlmTools.allTools
    private val toolNames = toolsForPrompt.map { it.name }.toSet()

    @VisibleForTesting
    internal var testResponseOverride: String? = null

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
            val responseText = testResponseOverride?.also { testResponseOverride = null }
                ?: run {
                    val stopStrings = if (relevantTools.isEmpty()) {
                        emptyList()
                    } else {
                        listOf("</tool_call>", "```", "user:", "assistant:")
                    }
                    val initial = engine.runInference(
                        simplifiedPrompt,
                        stopStrings = stopStrings,
                        clearCache = history.isEmpty()
                    )
                    if (initial.isBlank()) {
                        log.warn("Simplified workflow: empty response, retrying with no stop strings.")
                        engine.updateSampling(temperature = 0.2f, topP = 0.9f, topK = 40)
                        val retry = engine.runInference(
                            simplifiedPrompt,
                            clearCache = history.isEmpty()
                        )
                        engine.resetSamplingDefaults()
                        retry
                    } else {
                        initial
                    }
                }

            // Try to parse as tool call (only if tools are available)
            val cleanedResponse = trimAfterFirstToolCall(responseText)
            val parsedCall = if (relevantTools.isEmpty()) {
                null
            } else {
                val relevantToolNames = relevantTools.map { it.name }.toSet()
                Util.parseToolCall(cleanedResponse, relevantToolNames)
            }

            if (parsedCall != null) {
                // Tool call parsed successfully - execute it
                log.info("Simplified workflow: executing tool '{}'", parsedCall.name)
                onStateUpdate?.invoke(AgentState.Thinking("Using ${parsedCall.name}..."))

                val normalizedCall = fillMissingArgs(parsedCall, prompt)
                val missingArgs = getMissingRequiredArgs(normalizedCall)
                if (missingArgs.isNotEmpty()) {
                    addMessage(
                        "Please provide required argument(s): ${missingArgs.joinToString(", ")}.",
                        Sender.AGENT
                    )
                    onStateUpdate?.invoke(AgentState.Idle)
                    return
                }

                val toolResult = executeToolCall(normalizedCall)
                val resultMessage = formatToolResult(toolResult)

                addMessage(resultMessage, Sender.AGENT)
                onStateUpdate?.invoke(AgentState.Idle)
            } else {
                // No tool call - treat as direct response
                log.info("Simplified workflow: direct response (no tool needed)")
                val cleanResponse = cleanedResponse.trim().takeWhile { it != '<' }.trim()
                val finalResponse = if (cleanResponse.isBlank()) {
                    "Sorry, I didn’t get a response from the local model. Please try again."
                } else {
                    cleanResponse
                }
                addMessage(finalResponse, Sender.AGENT)
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

        val toolByName = toolsForPrompt.associateBy { it.name }
        val selected = linkedSetOf<LocalToolDeclaration>()

        fun addTool(name: String) {
            toolByName[name]?.let { selected.add(it) }
        }

        if (lowerQuery.contains("list") || lowerQuery.contains("files") || lowerQuery.contains("directory") || lowerQuery.contains(
                "folder"
            )
        ) {
            addTool("list_dir")
        }
        if (lowerQuery.contains("read") || lowerQuery.contains("open file") || lowerQuery.contains("show file")) {
            addTool("read_file")
        }
        if (lowerQuery.contains("search") || lowerQuery.contains("find")) {
            addTool("search_project")
        }
        if (lowerQuery.contains("create") || lowerQuery.contains("new file") || lowerQuery.contains(
                "add file"
            )
        ) {
            addTool("create_file")
        }
        if (lowerQuery.contains("update") || lowerQuery.contains("edit") || lowerQuery.contains("modify") || lowerQuery.contains(
                "replace"
            )
        ) {
            addTool("update_file")
        }
        if (lowerQuery.contains("dependency") || lowerQuery.contains("gradle")) {
            addTool("add_dependency")
        }
        if (lowerQuery.contains("build") || lowerQuery.contains("compile")) {
            addTool("get_build_output")
        }
        if (lowerQuery.contains("run") || lowerQuery.contains("install") || lowerQuery.contains("launch")) {
            addTool("run_app")
        }
        if (lowerQuery.contains("time") || lowerQuery.contains("date")) {
            addTool("get_current_datetime")
        }
        if (lowerQuery.contains("battery")) {
            addTool("get_device_battery")
        }
        if (lowerQuery.contains("weather")) {
            addTool("get_weather")
        }

        if (selected.isEmpty()) {
            return emptyList()
        }

        return selected.toList()
    }

    /**
     * Build a simplified prompt for direct tool calling without multi-step planning.
     * Uses the structure from the "desired" behavior with examples.
     */
    private suspend fun buildSimplifiedPrompt(
        userMessage: String,
        history: List<ChatMessage>,
        tools: List<LocalToolDeclaration>
    ): String {
        if (tools.isEmpty()) {
            val clippedUserMessage = clipMessage(userMessage, MAX_USER_PROMPT_CHARS)
            val historyLines = if (history.isEmpty()) {
                emptyList()
            } else {
                history.takeLast(MAX_HISTORY_ITEMS).map { msg ->
                    val senderName = when (msg.sender) {
                        Sender.USER -> "user"
                        Sender.AGENT -> "assistant"
                        Sender.TOOL -> "tool"
                        else -> "system"
                    }
                    val clipped = clipMessage(msg.text, MAX_MESSAGE_CHARS)
                    "$senderName: $clipped"
                }
            }
            val conversationHistory =
                buildHistoryWithBudgetNoTools(historyLines, clippedUserMessage)
            return """
You are a helpful assistant. Answer the user's question directly.

${conversationHistory}user: $clippedUserMessage
assistant:
""".trimIndent()
        }

        val toolsJson = tools.joinToString(",\n  ") { tool ->
            val shortDescription = tool.description.lineSequence().firstOrNull().orEmpty()
                .take(120)
            val escapedDescription = shortDescription.replace("\"", "\\\"")
            val args = if (tool.parameters.isEmpty()) {
                ""
            } else {
                val formattedArgs = tool.parameters.entries.joinToString(", ") { (name, desc) ->
                    val escaped = desc.replace("\"", "\\\"").take(80)
                    "\"$name\": \"$escaped\""
                }
                ", \"args\": { $formattedArgs }"
            }
            "{ \"name\": \"${tool.name}\", \"description\": \"$escapedDescription\"$args }"
        }

        val clippedUserMessage = clipMessage(userMessage, MAX_USER_PROMPT_CHARS)
        val historyLines = if (history.isEmpty()) {
            emptyList()
        } else {
            history.takeLast(MAX_HISTORY_ITEMS).map { msg ->
                val senderName = when (msg.sender) {
                    Sender.USER -> "user"
                    Sender.AGENT -> "model"
                    Sender.TOOL -> "tool"
                    else -> "system"
                }
                val clipped = clipMessage(msg.text, MAX_MESSAGE_CHARS)
                "$senderName: $clipped"
            }
        }
        val conversationHistory =
            buildHistoryWithBudget(historyLines, clippedUserMessage, toolsJson)

        return """
You are a helpful assistant. Use a tool only if needed.

Tools:
[
  $toolsJson
]

If using a tool, respond with ONLY:
<tool_call>{"name":"tool_name","args":{...}}</tool_call>
Include all required args. If you don't know required args, ask the user instead of calling a tool.
No extra text before or after.

Conversation:
${conversationHistory}user: $clippedUserMessage
assistant:
""".trimIndent()
    }

    private fun trimAfterFirstToolCall(text: String): String {
        val start = text.indexOf("<tool_call>")
        if (start == -1) return text
        val end = text.indexOf("</tool_call>", start)
        if (end == -1) return text
        return text.substring(0, end + "</tool_call>".length)
    }

    private suspend fun buildHistoryWithBudget(
        historyLines: List<String>,
        userPrompt: String,
        toolsJson: String
    ): String {
        if (historyLines.isEmpty()) return ""

        val contextSize = engine.getConfiguredContextSize().coerceAtLeast(1024)
        val inputBudget = (contextSize * 0.7f).toInt().coerceAtLeast(256)
        val basePrompt = """
You are a helpful assistant. Use a tool only if needed.

Tools:
[
  $toolsJson
]

If using a tool, respond with ONLY:
<tool_call>{"name":"tool_name","args":{...}}</tool_call>
Include all required args. If you don't know required args, ask the user instead of calling a tool.
No extra text before or after.

Conversation:
""".trimIndent()
        val baseTokens = engine.countTokens("$basePrompt\nuser: $userPrompt\nassistant:")
        val remaining = (inputBudget - baseTokens).coerceAtLeast(0)
        if (remaining == 0) return ""

        var tokenCount = 0
        val kept = ArrayList<String>()
        for (line in historyLines.asReversed()) {
            val lineTokens = engine.countTokens(line) + 1
            if (tokenCount + lineTokens > remaining) break
            kept.add(line)
            tokenCount += lineTokens
        }
        if (kept.isEmpty()) return ""
        return kept.asReversed().joinToString("\n") + "\n"
    }

    private suspend fun buildHistoryWithBudgetNoTools(
        historyLines: List<String>,
        userPrompt: String
    ): String {
        if (historyLines.isEmpty()) return ""

        val contextSize = engine.getConfiguredContextSize().coerceAtLeast(1024)
        val inputBudget = (contextSize * 0.7f).toInt().coerceAtLeast(256)
        val basePrompt = """
You are a helpful assistant. Answer the user's question directly.
""".trimIndent()
        val baseTokens = engine.countTokens("$basePrompt\nuser: $userPrompt\nassistant:")
        val remaining = (inputBudget - baseTokens).coerceAtLeast(0)
        if (remaining == 0) return ""

        var tokenCount = 0
        val kept = ArrayList<String>()
        for (line in historyLines.asReversed()) {
            val lineTokens = engine.countTokens(line) + 1
            if (tokenCount + lineTokens > remaining) break
            kept.add(line)
            tokenCount += lineTokens
        }
        if (kept.isEmpty()) return ""
        return kept.asReversed().joinToString("\n") + "\n"
    }

    private fun fillMissingArgs(
        toolCall: LocalLLMToolCall,
        userPrompt: String
    ): LocalLLMToolCall {
        val updated = toolCall.args.toMutableMap()
        when (toolCall.name) {
            "read_file" -> {
                if (updated["file_path"].isNullOrBlank()) {
                    inferFilePath(userPrompt)?.let { updated["file_path"] = it }
                }
            }

            "add_dependency" -> {
                if (updated["dependency"].isNullOrBlank()) {
                    inferDependency(userPrompt)?.let { updated["dependency"] = it }
                }
                if (updated["build_file_path"].isNullOrBlank()) {
                    inferBuildFilePath(userPrompt)?.let { updated["build_file_path"] = it }
                }
            }

            "search_project" -> {
                if (updated["query"].isNullOrBlank()) {
                    inferSearchQuery(userPrompt)?.let { updated["query"] = it }
                }
            }
        }
        return toolCall.copy(args = updated)
    }

    private fun inferFilePath(text: String): String? {
        val backtick = Regex("`([^`]+)`").find(text)?.groupValues?.getOrNull(1)
        if (!backtick.isNullOrBlank() && backtick.contains('.')) return backtick

        val singleQuote = Regex("'([^']+)'").find(text)?.groupValues?.getOrNull(1)
        if (!singleQuote.isNullOrBlank() && singleQuote.contains('.')) return singleQuote

        val doubleQuote = Regex("\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
        if (!doubleQuote.isNullOrBlank() && doubleQuote.contains('.')) return doubleQuote

        val extMatch = Regex("([\\w./-]+\\.(?:kts|kt|gradle|xml|json|md|txt|yml|yaml|properties))")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        return extMatch
    }

    private fun inferDependency(text: String): String? {
        val implMatch = Regex("implementation\\(([^)]+)\\)").find(text)?.groupValues?.getOrNull(1)
        val apiMatch = Regex("api\\(([^)]+)\\)").find(text)?.groupValues?.getOrNull(1)
        val raw = implMatch ?: apiMatch ?: Regex("'([^']+:[^']+:[^']+)'")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        return raw?.trim()?.trim('"')?.trim('\'')?.takeIf { it.contains(':') }
    }

    private fun inferBuildFilePath(text: String): String? {
        val file = inferFilePath(text)
        return when {
            file != null && (file.endsWith(".gradle.kts") || file.endsWith(".gradle")) -> file
            text.contains("build.gradle.kts") -> "build.gradle.kts"
            text.contains("build.gradle") -> "build.gradle"
            else -> null
        }
    }

    private fun inferSearchQuery(text: String): String? {
        val quoted = Regex("\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
            ?: Regex("'([^']+)'").find(text)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()

        val fileNamed = Regex("file\\s+named\\s+([\\w.\\-/]+)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        if (!fileNamed.isNullOrBlank()) return fileNamed.trim()

        val afterSearchFor = Regex("search\\s+for\\s+(.+)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trimEnd('.', '?')
        return afterSearchFor?.takeIf { it.isNotBlank() }
    }

    private fun getMissingRequiredArgs(toolCall: LocalLLMToolCall): List<String> {
        val requiredArgs = when (toolCall.name) {
            "read_file" -> listOf("file_path")
            "add_dependency" -> listOf("dependency", "build_file_path")
            "get_build_output" -> listOf("error")
            "read_multiple_files" -> listOf("paths")
            "search_project" -> listOf("query")
            else -> emptyList()
        }

        if (requiredArgs.isEmpty()) return emptyList()
        return requiredArgs.filter { key ->
            val value = toolCall.args[key]?.toString()?.trim().orEmpty()
            value.isBlank()
        }
    }

    private fun clipMessage(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars).trimEnd() + "..."
    }

    /**
     * Execute a tool call and return the result.
     */
    private suspend fun executeToolCall(toolCall: LocalLLMToolCall): ToolResult {
        return try {
            // Convert LocalLLMToolCall to FunctionCall
            val functionCall = FunctionCall.builder()
                .name(toolCall.name)
                .args(toolCall.args)
                .build()

            // Execute using the base class executor
            val results = executor.execute(listOf(functionCall))

            if (results.isEmpty()) {
                ToolResult.success("Tool executed successfully.")
            } else {
                extractToolResult(results) ?: ToolResult.success(
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
                )
            }
        } catch (err: Exception) {
            log.error("Tool execution failed for '{}'", toolCall.name, err)
            ToolResult.failure("Error executing tool: ${err.message}")
        }
    }

    /**
     * Format tool result into a user-friendly message.
     */
    private fun formatToolResult(result: ToolResult): String {
        if (!result.success) {
            return listOfNotNull(result.message, result.error_details).joinToString("\n")
        }

        val exploration = result.exploration
        val formatted = when {
            exploration?.kind == ExplorationKind.LIST && exploration.items.isNotEmpty() ->
                exploration.items.joinToString(separator = "\n") { "• $it" }

            !result.data.isNullOrBlank() -> result.data
            else -> result.message
        }

        return formatted?.trimEnd() ?: "Done."
    }

    private fun extractToolResult(results: List<Part>): ToolResult? {
        val functionResponse = results.firstOrNull { it.functionResponse().isPresent }
            ?.functionResponse()
            ?.orElse(null)
            ?: return null

        val rawResponse = functionResponse.response()
        val payload = when (rawResponse) {
            is java.util.Optional<*> -> rawResponse.orElse(null)
            else -> rawResponse
        }

        val map = payload as? Map<*, *> ?: return null
        val success = map["success"] as? Boolean ?: false
        val message = map["message"]?.toString() ?: ""
        val dataValue = map["data"]
        val data = when (dataValue) {
            is List<*> -> dataValue.mapNotNull { it?.toString() }.joinToString("\n")
            null -> null
            else -> dataValue.toString()
        }
        val errorDetails = map["error_details"]?.toString()
        val exploration = ExplorationMetadata.fromMap(map["exploration"] as? Map<*, *>)

        return ToolResult(
            success = success,
            message = message,
            data = data,
            error_details = errorDetails,
            exploration = exploration
        )
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
            val raw = engine.runInference(planPrompt, clearCache = true)
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
        val responseText = engine.runInference(
            prompt,
            stopStrings = listOf("</tool_call>"),
            clearCache = true
        )
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
