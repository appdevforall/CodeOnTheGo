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
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

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
    private val toolsForPrompt = LocalLlmTools.allTools

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
                        listOf("</tool_call>", "user:", "assistant:")
                    }
                    val initial = engine.runInference(
                        simplifiedPrompt,
                        stopStrings = stopStrings,
                        clearCache = history.isEmpty()
                    )
                    if (initial.isBlank()) {
                        log.warn("Simplified workflow: empty response, retrying with no stop strings.")
                        val retry = try {
                            engine.updateSampling(temperature = 0.2f, topP = 0.9f, topK = 40)
                            engine.runInference(
                                simplifiedPrompt,
                                clearCache = history.isEmpty()
                            )
                        } finally {
                            engine.resetSamplingDefaults()
                        }
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
                val resultMessage = formatToolResult(toolResult).ifBlank {
                    "Tool completed."
                }

                addMessage(resultMessage, Sender.AGENT)
                onStateUpdate?.invoke(AgentState.Idle)
            } else {
                // No tool call - treat as direct response
                log.info("Simplified workflow: direct response (no tool needed)")
                val cleanResponse = stripToolBlocks(cleanedResponse).trim()
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

        val keywordMap = linkedMapOf(
            "list_files" to listOf("list", "files", "directory", "folder"),
            "read_file" to listOf("read", "open file", "show file"),
            "search_project" to listOf("search", "find"),
            "create_file" to listOf("create", "new file", "add file"),
            "update_file" to listOf("update", "edit", "modify", "replace"),
            "add_dependency" to listOf("dependency", "gradle"),
            "get_build_output" to listOf("build", "compile"),
            "run_app" to listOf("run", "install", "launch"),
            "get_current_datetime" to listOf("time", "date"),
            "get_device_battery" to listOf("battery"),
            "get_weather" to listOf("weather"),
        )

        for ((toolName, keywords) in keywordMap) {
            if (keywords.any { lowerQuery.contains(it) }) {
                addTool(toolName)
            }
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
            val basePromptText = "You are a helpful assistant. Answer the user's question directly."
            val conversationHistory =
                buildHistoryWithBudget(historyLines, clippedUserMessage, basePromptText)
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
                    Sender.AGENT -> "assistant"
                    Sender.TOOL -> "tool"
                    else -> "system"
                }
                val clipped = clipMessage(msg.text, MAX_MESSAGE_CHARS)
                "$senderName: $clipped"
            }
        }
        val basePromptText = """
You are a helpful assistant. Use a tool only if needed.

Tools:
[
  $toolsJson
]

If using a tool, respond with ONLY:
<tool_call>{"name":"tool_name","args":{...}}</tool_call>
Include all required args. If you don't know required args, ask the user instead of calling a tool.
No extra text before or after.

Conversation:""".trimIndent()
        val conversationHistory =
            buildHistoryWithBudget(historyLines, clippedUserMessage, basePromptText)

        return """
$basePromptText
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

    private fun stripToolBlocks(text: String): String {
        val toolPattern =
            Regex("<tool_call>.*?</tool_call>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return text.replace(toolPattern, "").trim()
    }

    private suspend fun buildHistoryWithBudget(
        historyLines: List<String>,
        userPrompt: String,
        basePromptText: String
    ): String {
        if (historyLines.isEmpty()) return ""

        val contextSize = engine.getConfiguredContextSize().coerceAtLeast(1024)
        val inputBudget = (contextSize * 0.7f).toInt().coerceAtLeast(256)
        val baseTokens = engine.countTokens("$basePromptText\nuser: $userPrompt\nassistant:")
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
                if (updated["path"].isNullOrBlank()) {
                    val legacy = updated["file_path"]?.takeIf { it.isNotBlank() }
                    if (!legacy.isNullOrBlank()) {
                        updated["path"] = legacy
                    } else {
                        inferFilePath(userPrompt)?.let { updated["path"] = it }
                    }
                }
            }

            "add_dependency" -> {
                if (updated["dependency_string"].isNullOrBlank()) {
                    inferDependency(userPrompt)?.let { updated["dependency_string"] = it }
                }
                if (updated["build_file_path"].isNullOrBlank()) {
                    inferBuildFilePath(userPrompt)?.let { updated["build_file_path"] = it }
                }
            }

            "search_project" -> {
                val inferred = inferSearchQuery(userPrompt)
                if (updated["query"].isNullOrBlank()) {
                    if (!inferred.isNullOrBlank()) {
                        updated["query"] = inferred
                    }
                }
                if (updated["query"].isNullOrBlank()) {
                    val fallback = userPrompt.trim()
                    if (fallback.isNotEmpty()) {
                        updated["query"] = fallback
                    }
                }
                log.debug(
                    "fillMissingArgs(search_project): prompt='{}', inferred='{}', final='{}'",
                    userPrompt,
                    inferred,
                    updated["query"]
                )
            }
        }
        return toolCall.copy(args = updated)
    }

    private fun inferFilePath(text: String): String? {
        fun sanitizeCandidate(candidate: String?): String? {
            if (candidate.isNullOrBlank()) return null
            if (candidate.contains("..")) return null
            return resolvePathWithinProject(candidate)
        }

        val backtick = firstGroup(text, Regex("`([^`]+)`"))
        if (!backtick.isNullOrBlank() && backtick.contains('.')) {
            return sanitizeCandidate(backtick)
        }

        val singleQuote = firstGroup(text, Regex("'([^']+)'"))
        if (!singleQuote.isNullOrBlank() && singleQuote.contains('.')) {
            return sanitizeCandidate(singleQuote)
        }

        val doubleQuote = firstGroup(text, Regex("\"([^\"]+)\""))
        if (!doubleQuote.isNullOrBlank() && doubleQuote.contains('.')) {
            return sanitizeCandidate(doubleQuote)
        }

        val extMatch = firstGroup(
            text,
            Regex("([\\w./-]+\\.(?:kts|kt|gradle|xml|json|md|txt|yml|yaml|properties))")
        )
        return sanitizeCandidate(extMatch)
    }

    private fun firstGroup(text: String, regex: Regex): String? {
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun firstGroup(text: String, vararg patterns: Regex): String? {
        for (pattern in patterns) {
            val match = firstGroup(text, pattern)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    private fun resolvePathWithinProject(candidate: String): String? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val baseDir = IProjectManager.getInstance().projectDir.canonicalFile
            val basePath = baseDir.canonicalPath
            val candidateFile = File(baseDir, trimmed)
            val candidatePath = candidateFile.canonicalPath
            val isInside = candidatePath == basePath ||
                candidatePath.startsWith(basePath + File.separator)
            if (isInside) trimmed else null
        }.getOrNull()
    }

    private fun inferDependency(text: String): String? {
        val implMatch = firstGroup(text, Regex("implementation\\(([^)]+)\\)"))
        val apiMatch = firstGroup(text, Regex("api\\(([^)]+)\\)"))
        val raw = implMatch ?: apiMatch ?: firstGroup(text, Regex("'([^']+:[^']+:[^']+)'"))
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
        val quoted = firstGroup(text, Regex("\"([^\"]+)\""), Regex("'([^']+)'"))
        if (!quoted.isNullOrBlank()) {
            val trimmed = quoted.trim()
            val fromQuoted = extractFilenameQuery(trimmed)
            return fromQuoted ?: trimmed
        }

        val fileExt = Regex(
            "([\\w\\-./]+\\.(?:ini|csv|json|xml|yaml|yml|txt|md|kts|gradle|kt|java))",
            RegexOption.IGNORE_CASE
        ).let { firstGroup(text, it) }
        if (!fileExt.isNullOrBlank()) return fileExt.trim()

        val fileNamed = firstGroup(text, Regex("file\\s+named\\s+([\\w.\\-/]+)", RegexOption.IGNORE_CASE))
        if (!fileNamed.isNullOrBlank()) return fileNamed.trim()

        val afterSearchFor = firstGroup(text, Regex("search\\s+for\\s+(.+)", RegexOption.IGNORE_CASE))
            ?.trim()
            ?.trimEnd('.', '?')
        if (!afterSearchFor.isNullOrBlank()) {
            val fromSearch = extractFilenameQuery(afterSearchFor)
            return fromSearch ?: afterSearchFor
        }
        return null
    }

    private fun extractFilenameQuery(text: String): String? {
        val match = Regex(
            "([\\w\\-./]+\\.(?:ini|csv|json|xml|yaml|yml|txt|md|kts|gradle|kt|java))",
            RegexOption.IGNORE_CASE
        ).find(text)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private fun getMissingRequiredArgs(toolCall: LocalLLMToolCall): List<String> {
        val requiredArgs = Executor.requiredArgsForTool(toolCall.name)
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
