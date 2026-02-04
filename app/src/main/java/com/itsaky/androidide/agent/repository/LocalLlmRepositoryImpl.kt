package com.itsaky.androidide.agent.repository

import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

private const val SYSTEM_PROMPT = """
You are a helpful and smart assistant integrated into an Android application.
You have access to the following tools to get real-time information. Do not make up information for these tools.

[AVAILABLE_TOOLS]

To use a tool, you must respond with a JSON object inside a special <tool_call> tag. Your response should contain nothing else.
The JSON object must have "tool_name" and "args" keys.
"args" must be an object containing the arguments for the tool. If no arguments are needed, use an empty object {}.

Example of a tool call:
<tool_call>
{
  "tool_name": "get_current_datetime",
  "args": {}
}
</tool_call>

After the tool is called, you will receive the result and you must use it to answer the user's original question.
"""

class LocalLlmRepositoryImpl(
    private val context: Context,
    private val engine: LlmInferenceEngine
) : GeminiRepository {

    private val toolTracker = ToolExecutionTracker()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class InferenceMetrics(
        val text: String,
        val durationMs: Long,
        val ttftMs: Long?,
        val responseTokens: Int,
        val tokensPerSec: Double
    )

    override var onStateUpdate: ((AgentState) -> Unit)? = null
    private val messageIdCounter = AtomicLong(0)

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
        )
    )
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    suspend fun loadModel(modelUriString: String): Boolean {
        onStateUpdate?.invoke(AgentState.Processing("Loading local model..."))
        val success = engine.initModelFromFile(context, modelUriString)
        val status =
            if (success) {
                context.getString(R.string.agent_local_model_loaded_success)
            } else {
                context.getString(R.string.agent_local_model_loaded_failure)
            }
        onStateUpdate?.invoke(AgentState.Processing(status))
        onStateUpdate?.invoke(AgentState.Idle)
        return success
    }

    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool(),
        GetWeatherTool(),
        ListFilesTool(),
    ).associateBy { it.name }

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
    }
    private fun buildPromptWithHistory(
        history: List<ChatMessage>,
        isFinalAnswerTurn: Boolean,
        requiredTool: String?,
        forceToolCall: Boolean
    ): String {
        return when (engine.currentModelFamily) {
            ModelFamily.LLAMA3 -> buildLlama3Prompt(history, requiredTool, forceToolCall)
            ModelFamily.QWEN -> {
                if (isFinalAnswerTurn) {
                    buildGemma2FinalAnswerPrompt(history)
                } else {
                    buildQwenStrictToolPrompt(history, requiredTool)
                }
            }
            ModelFamily.H2O -> {
                if (isFinalAnswerTurn) {
                    buildGemma2FinalAnswerPrompt(history)
                } else {
                    if (requiredTool == null) {
                        history.lastOrNull { it.sender == Sender.USER }?.text ?: ""
                    } else {
                        buildH2oToolPrompt(history, requiredTool)
                    }
                }
            }

            ModelFamily.GEMMA3,
            ModelFamily.GEMMA2 -> {
                if (isFinalAnswerTurn) {
                    buildGemma2FinalAnswerPrompt(history)
                } else {
                    buildGemma2Prompt(history, requiredTool, forceToolCall)
                }
            }

            else -> history.lastOrNull { it.sender == Sender.USER }?.text ?: ""
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ) {
        if (!engine.isModelLoaded) {
            return
        }
        loadHistory(history)
        addMessage(prompt, Sender.USER)
        val placeholder = ""
        addMessage(placeholder, Sender.AGENT)

        runAgentLoop()
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun runAgentLoop() {
        toolTracker.startTracking()

        val sessionStartWall = System.currentTimeMillis()
        val sessionStartCpu = Process.getElapsedCpuTime()
        Log.i(
            "AgentPerf",
            "Session start: wall=${sessionStartWall}ms cpu=${sessionStartCpu}ms model=${engine.loadedModelName}"
        )

        val maxTurns = 5
        var currentTurn = 0
        while (currentTurn < maxTurns) {
            Log.d("AgentDebug", "--- [Step ${currentTurn + 1}] ---")
            val currentHistory = _messages.value
            val isFinalAnswerTurn =
                currentHistory.getOrNull(currentHistory.size - 2)?.sender == Sender.TOOL

            val stepStartWall = System.currentTimeMillis()
            val stepStartCpu = Process.getElapsedCpuTime()
            val memStart = getMemSnapshot()
            Log.i(
                "AgentPerf",
                "Step start: turn=${currentTurn + 1} wall=${stepStartWall} cpu=${stepStartCpu} mem=${memStart}"
            )

            // 2. UPDATE THE STOP STRINGS
            val stopStrings = if (isFinalAnswerTurn) {
                // For the final answer, stop before the model hallucinates a new question.
                listOf("Question:", "\n\n")
            } else {
                // For tool selection, we now expect a structured XML tag.
                val toolStops = mutableListOf("</tool_call>")
                when (engine.currentModelFamily) {
                    ModelFamily.LLAMA3 -> {
                        toolStops.add("<|eot_id|>")
                        toolStops.add("<|end_of_text|>")
                    }

                    ModelFamily.QWEN,
                    ModelFamily.H2O -> {
                        Unit
                    }

                    else -> Unit
                }
                toolStops
            }

            val userPrompt = currentHistory.lastOrNull { it.sender == Sender.USER }?.text.orEmpty()
            val requiredTool = if (isFinalAnswerTurn) null else detectRequiredTool(userPrompt)
            val isH2oToolTurn = !isFinalAnswerTurn && engine.currentModelFamily == ModelFamily.H2O
            val formatChat = engine.currentModelFamily == ModelFamily.H2O
            val maxTokensOverride = if (isH2oToolTurn) 128 else null

            val fullPromptHistory = buildPromptWithHistory(
                currentHistory,
                isFinalAnswerTurn,
                requiredTool,
                false
            )
            Log.d("AgentDebug", "Final Prompt Sent:\n$fullPromptHistory")
            val attempt1 = try {
                runInferenceWithMetrics(
                    fullPromptHistory,
                    stopStrings,
                    formatChat,
                    maxTokensOverride
                )
            } catch (e: Exception) {
                Log.e("AgentLoop", "Model inference failed", e)
                InferenceMetrics(
                    text = "Error: Could not get a response from the model.",
                    durationMs = 0,
                    ttftMs = null,
                    responseTokens = 0,
                    tokensPerSec = 0.0
                )
            }
            var modelResponse = attempt1.text
            var totalDurationMs = attempt1.durationMs
            var lastAttempt = attempt1

            var finalResponse = modelResponse.split(stopStrings.first()).first()
            Log.d("AgentDebug", "Raw Model Result: \"$modelResponse\"")
            Log.d("AgentDebug", "Trimmed Final Result: \"$finalResponse\"")

            if (isFinalAnswerTurn) {
                var cleanResponse = finalResponse
                for (stopWord in stopStrings) {
                    if (cleanResponse.contains(stopWord)) {
                        // Take only the text *before* the first occurrence of a stop word
                        cleanResponse = cleanResponse.substringBefore(stopWord).trim()
                    }
                }
                updateLastMessage(cleanResponse)

                val stepEndWall = System.currentTimeMillis()
                val stepEndCpu = Process.getElapsedCpuTime()
                val memEnd = getMemSnapshot()
                Log.i(
                    "AgentPerf",
                    "Step end: turn=${currentTurn + 1} wall=${stepEndWall} cpu=${stepEndCpu} mem=${memEnd} " +
                            "durationMs=${totalDurationMs} responseChars=${modelResponse.length} " +
                            "ttftMs=${lastAttempt.ttftMs ?: -1} responseTokens=${lastAttempt.responseTokens} " +
                            "tokPerSec=${"%.2f".format(lastAttempt.tokensPerSec)}"
                )

                Log.d("AgentDebug", "Final answer received. Concluding.")
                updateLastMessageDuration(totalDurationMs)
                logSessionSummary(sessionStartWall, sessionStartCpu)
                break
            } else {
                val responseForParse =
                    if (requiredTool != null && engine.currentModelFamily == ModelFamily.H2O) {
                        normalizeH2oToolResponse(finalResponse, requiredTool)
                    } else {
                        finalResponse
                    }
                var toolCall = Util.parseToolCall(responseForParse, tools.keys)
                if (toolCall == null && requiredTool != null) {
                    Log.w(
                        "AgentDebug",
                        "Tool required ($requiredTool) but no tool call detected. Retrying with forced tool prompt."
                    )
                    val forcedPrompt = buildPromptWithHistory(
                        currentHistory,
                        false,
                        requiredTool,
                        true
                    )
                    Log.d("AgentDebug", "Forced Tool Prompt Sent:\n$forcedPrompt")
                    val attempt2 = try {
                        runInferenceWithMetrics(
                            forcedPrompt,
                            stopStrings,
                            formatChat,
                            maxTokensOverride
                        )
                    } catch (e: Exception) {
                        Log.e("AgentLoop", "Model inference failed on forced tool retry", e)
                        InferenceMetrics(
                            text = "Error: Could not get a response from the model.",
                            durationMs = 0,
                            ttftMs = null,
                            responseTokens = 0,
                            tokensPerSec = 0.0
                        )
                    }
                    modelResponse = attempt2.text
                    totalDurationMs += attempt2.durationMs
                    lastAttempt = attempt2
                    finalResponse = modelResponse.split(stopStrings.first()).first()
                    Log.d("AgentDebug", "Forced Tool Raw Result: \"$modelResponse\"")
                    Log.d("AgentDebug", "Forced Tool Final Result: \"$finalResponse\"")
                    val forcedResponseForParse =
                        if (engine.currentModelFamily == ModelFamily.H2O) {
                            normalizeH2oToolResponse(finalResponse, requiredTool)
                        } else {
                            finalResponse
                        }
                    toolCall = Util.parseToolCall(forcedResponseForParse, tools.keys)
                }

                val stepEndWall = System.currentTimeMillis()
                val stepEndCpu = Process.getElapsedCpuTime()
                val memEnd = getMemSnapshot()
                Log.i(
                    "AgentPerf",
                    "Step end: turn=${currentTurn + 1} wall=${stepEndWall} cpu=${stepEndCpu} mem=${memEnd} " +
                            "durationMs=${totalDurationMs} responseChars=${modelResponse.length} " +
                            "ttftMs=${lastAttempt.ttftMs ?: -1} responseTokens=${lastAttempt.responseTokens} " +
                            "tokPerSec=${"%.2f".format(lastAttempt.tokensPerSec)}"
                )

                if (toolCall != null) {
                    val tool = tools[toolCall.name]
                    if (tool != null) {
                        Log.d(
                            "AgentDebug",
                            "Tool Call Detected: ${toolCall.name} with args: ${toolCall.args}"
                        )
                        // Display a user-friendly version of the tool call
                        updateLastMessage(
                            "Tool Call: ${toolCall.name}(${ 
                                toolCall.args.map { "${it.key}=${it.value}" }.joinToString()
                            })"
                        )
                        updateLastMessageDuration(totalDurationMs)

                        // Execute the tool with the parsed arguments
                        val result = tool.execute(context, toolCall.args)
                        addMessage(result, Sender.TOOL)
                        if (toolCall.name == "list_files") {
                            updateLastMessage("Files in project root:\n$result")
                            updateLastMessageDuration(totalDurationMs)
                            logSessionSummary(sessionStartWall, sessionStartCpu)
                            break
                        }
                        addMessage("", Sender.AGENT)
                    } else {
                        // This handles the case where the model hallucinates a tool name.
                        val errorMsg = "Error: Model tried to call unknown tool '${toolCall.name}'"
                        updateLastMessage(errorMsg)
                        break
                    }
                } else {
                    // No tool call detected, this is a direct answer.
                    updateLastMessage(finalResponse)
                    updateLastMessageDuration(totalDurationMs)
                    logSessionSummary(sessionStartWall, sessionStartCpu)
                    Log.d("AgentDebug", "No tool call detected. Model gave a direct answer.")
                    break
                }
            }
            currentTurn++
        }
    }

    private fun logSessionSummary(startWall: Long, startCpu: Long) {
        val endWall = System.currentTimeMillis()
        val endCpu = Process.getElapsedCpuTime()
        val mem = getMemSnapshot()
        Log.i(
            "AgentPerf",
            "Session end: wall=${endWall} cpu=${endCpu} elapsedWall=${endWall - startWall} " +
                    "elapsedCpu=${endCpu - startCpu} mem=${mem}"
        )
    }

    private suspend fun runInferenceWithMetrics(
        prompt: String,
        stopStrings: List<String>,
        formatChat: Boolean = false,
        maxTokensOverride: Int? = null
    ): InferenceMetrics {
        val startNs = System.nanoTime()
        var firstTokenNs: Long? = null
        val builder = StringBuilder()

        withContext(Dispatchers.IO) {
            try {
                if (maxTokensOverride != null) {
                    engine.updateMaxTokens(maxTokensOverride)
                }
                engine.runStreamingInference(
                    prompt,
                    stopStrings = stopStrings,
                    formatChat = formatChat
                ).collect { chunk ->
                    if (firstTokenNs == null && chunk.isNotEmpty()) {
                        firstTokenNs = System.nanoTime()
                    }
                    builder.append(chunk)
                }
            } finally {
                if (maxTokensOverride != null) {
                    engine.resetMaxTokens()
                }
            }
        }

        val endNs = System.nanoTime()
        val durationMs = (endNs - startNs) / 1_000_000
        val ttftMs = firstTokenNs?.let { (it - startNs) / 1_000_000 }
        val response = builder.toString()
        val responseTokens = engine.countTokens(response)
        val tokensPerSec =
            if (durationMs > 0) responseTokens.toDouble() / (durationMs / 1000.0) else 0.0

        return InferenceMetrics(
            text = response,
            durationMs = durationMs,
            ttftMs = ttftMs,
            responseTokens = responseTokens,
            tokensPerSec = tokensPerSec
        )
    }

    private fun getMemSnapshot(): String {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return "pssKb=${memInfo.totalPss} privateDirtyKb=${memInfo.totalPrivateDirty}"
    }

    private fun normalizeH2oToolResponse(response: String, requiredTool: String): String {
        val trimmed = response.trim()
        if (trimmed.contains("<tool_call>")) return trimmed
        val prefix = "<tool_call>{\"name\":\"$requiredTool\",\"args\":"
        return if (trimmed.startsWith("{") || trimmed.startsWith("}")) {
            val needsClosingTag = !trimmed.contains("</tool_call>")
            val needsClosingBrace = !trimmed.trimEnd().endsWith("}")
            val suffix = buildString {
                if (needsClosingBrace) {
                    append("}")
                }
                if (needsClosingTag) {
                    append("</tool_call>")
                }
            }
            val normalized = prefix + trimmed + suffix
            Log.d("AgentDebug", "H2O normalized tool response: \"$normalized\"")
            normalized
        } else {
            trimmed
        }
    }

    private fun buildGemma2Prompt(
        history: List<ChatMessage>,
        requiredTool: String?,
        forceToolCall: Boolean
    ): String {
        // Find if the last message was a tool result to decide which prompt to use
        val isFinalAnswerTurn = history.lastOrNull()?.sender == Sender.AGENT &&
                history.getOrNull(history.size - 2)?.sender == Sender.TOOL

        if (isFinalAnswerTurn) {
            // If it's the final answer turn, use the simple synthesis prompt
            val userQuestion = history.findLast { it.sender == Sender.USER }?.text ?: ""
            val toolResult = (history.findLast { it.sender == Sender.TOOL }?.text ?: "")
                .replace(Regex("\\[Tool Result for [a-zA-Z_]+]:"), "")
                .trim()

            return """
You are a helpful assistant.
Use the following information to answer the user's question in a single, friendly sentence.

Information: $toolResult
Question: $userQuestion
Answer:
            """.trimIndent()
        } else {
            // Otherwise, build the full tool-selection prompt
            val promptBuilder = StringBuilder()
            val toolsAsJson = tools.values.joinToString(",\n") { tool ->
                """  { "name": "${tool.name}", "description": "${
                    tool.description.replace(
                        "\"",
                        "\\\""
                    )
                }" }"""
            }

            val toolRequirementNote = when {
                requiredTool == null -> ""
                forceToolCall -> "Tool required: $requiredTool. You MUST respond with a single <tool_call> tag for this tool and nothing else."
                else -> "Tool required: $requiredTool. You must call this tool to answer; do not answer directly."
            }

            val systemInstruction = """
You are a helpful assistant with access to the following tools:
[$toolsAsJson]

To use a tool, respond with a single `<tool_call>` XML tag containing a JSON object with the tool's 'name' and 'args'.
If no tool is needed, answer the user's question directly.
$toolRequirementNote

EXAMPLE:
user: What is the weather like in Paris?
model: <tool_call>{"name": "get_weather", "args": {"city": "Paris"}}</tool_call>
            """.trimIndent()

            promptBuilder.append(systemInstruction)
            promptBuilder.append("\n\n**CONVERSATION:**\n")
            history.takeLast(4).forEach { message ->
                when (message.sender) {
                    Sender.USER -> promptBuilder.append("user: ${message.text}\n")
                    Sender.AGENT -> if (message.text.isNotBlank()) promptBuilder.append("model: ${message.text}\n")
                    Sender.SYSTEM -> promptBuilder.append("system: ${message.text}\n")
                    else -> {}
                }
            }
            promptBuilder.append("model: ")
            return promptBuilder.toString()
        }
    }

    private fun buildGemma2FinalAnswerPrompt(history: List<ChatMessage>): String {
        val userQuestion = history.findLast { it.sender == Sender.USER }?.text ?: ""
        val toolResult = (history.findLast { it.sender == Sender.TOOL }?.text ?: "")
            .replace("[Tool Result for get_current_datetime]:", "") // Keep this cleanup
            .trim()

        val finalPrompt = """
You are a helpful assistant.
Use the following information to answer the user's question.
Answer in a single, friendly sentence.

Information: $toolResult
Question: $userQuestion
Answer:
    """.trimIndent()

        return finalPrompt
    }

    private fun buildQwenStrictToolPrompt(
        history: List<ChatMessage>,
        requiredTool: String?
    ): String {
        val toolsAsJson = tools.values.joinToString(",\n") { tool ->
            """  { "name": "${tool.name}", "description": "${
                tool.description.replace("\"", "\\\"")
            }" }"""
        }

        val toolRequirementNote =
            requiredTool?.let { "Tool required: $it. You MUST call this tool." } ?: ""

        val systemInstruction = """
You are a function-calling agent.
You DO NOT answer with text.
You ONLY answer with a JSON object matching this schema:
{"name":"<tool_name>","args":{...}}

Available tools:
[$toolsAsJson]

$toolRequirementNote
If you do not need a parameter, omit it from "args" (do NOT use null).
Return ONLY the JSON object and nothing else.
        """.trimIndent()

        val userQuestion = history.findLast { it.sender == Sender.USER }?.text.orEmpty()
        return "$systemInstruction\n\nUser: $userQuestion\nAnswer:"
    }

    private fun buildH2oToolPrompt(
        history: List<ChatMessage>,
        requiredTool: String?
    ): String {
        val userQuestion = history.findLast { it.sender == Sender.USER }?.text.orEmpty()
        val toolName = requiredTool ?: ""
        val tool = tools[toolName]
        val toolDescription = tool?.description ?: ""
        val assistantPrefix = "<tool_call>{\"name\":\"$toolName\",\"args\":"
        return """
You are a tool-calling agent.
Respond with exactly one <tool_call> tag containing a JSON object.
Do NOT include any other text.

Required tool:
- name: $toolName
- description: $toolDescription

Output format (example):
<tool_call>{"name":"$toolName","args":{}}</tool_call>
Complete the JSON and close the </tool_call> tag.

User: $userQuestion
Assistant: $assistantPrefix
        """.trimIndent()
    }

    private fun buildLlama3Prompt(
        history: List<ChatMessage>,
        requiredTool: String?,
        forceToolCall: Boolean
    ): String {
        val effectiveHistory = buildLlama3History(history, requiredTool)
        val historyBuilder = StringBuilder()
        historyBuilder.append("<|begin_of_text|>")
        val toolRequirementNote = when {
            requiredTool == null -> ""
            forceToolCall -> "\n\nTool required: $requiredTool. You MUST respond with a single <tool_call> tag for this tool and nothing else."
            else -> "\n\nTool required: $requiredTool. You must call this tool to answer; do not answer directly."
        }
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt$toolRequirementNote<|eot_id|>")
        for (message in effectiveHistory) {
            when (message.sender) {
                Sender.USER -> historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                Sender.AGENT -> {
                    if (message.text.isNotBlank()) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}<|eot_id|>")
                    }
                }

                Sender.SYSTEM -> {
                    historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n${message.text}<|eot_id|>")
                }
                Sender.TOOL -> {
                    historyBuilder.append("<|start_header_id|>tool<|end_header_id|>\n")
                    historyBuilder.append(message.text)
                    historyBuilder.append("<|eot_id|>\n")
                }
            }
        }
        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return historyBuilder.toString()
    }

    private fun buildLlama3History(
        history: List<ChatMessage>,
        requiredTool: String?
    ): List<ChatMessage> {
        val lastUser = history.lastOrNull { it.sender == Sender.USER }
        if (requiredTool != null) {
            return listOfNotNull(lastUser)
        }

        val recent = history.takeLast(8)
        val cleaned = mutableListOf<ChatMessage>()
        var lastUserText: String? = null
        for (message in recent) {
            when (message.sender) {
                Sender.AGENT -> {
                    val text = message.text.trim()
                    if (text.isBlank()) continue
                    if (text.contains(
                            "<tool_call>",
                            ignoreCase = true
                        ) || text.contains("Tool Call:", ignoreCase = true)
                    ) {
                        continue
                    }
                    cleaned.add(message)
                }

                Sender.USER -> {
                    if (message.text == lastUserText) continue
                    lastUserText = message.text
                    cleaned.add(message)
                }

                Sender.TOOL -> {
                    val text = message.text.trim()
                    if (text.isBlank()) continue
                    cleaned.add(message.copy(text = text.take(1200)))
                }

                Sender.SYSTEM -> cleaned.add(message)
            }
        }
        return cleaned
    }

    override fun getPartialReport(): String = toolTracker.generatePartialReport()
    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
        onStateUpdate?.invoke(AgentState.Processing("Generating code with local LLM..."))
        val response = engine.runInference(
            "USER: Based on the file $fileName, $prompt\nASSISTANT:",
            emptyList()
        )
        onStateUpdate?.invoke(AgentState.Idle)
        return response
    }

    override fun stop() {
        engine.stop()
    }

    private fun parseToolCall(text: String): ParsedToolCall? {
        val pattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            val jsonStr = matcher.group(1)?.trim()
            if (jsonStr.isNullOrBlank()) return null
            return try {
                json.decodeFromString<ParsedToolCall>(jsonStr)
            } catch (e: Exception) {
                Log.e("ToolParse", "Failed to parse tool call JSON", e)
                null
            }
        }
        return null
    }


    private fun addMessage(text: String, type: Sender) {
        val message = ChatMessage(messageIdCounter.getAndIncrement().toString(), text, type)
        _messages.update { currentList -> currentList + message }
    }

    private fun updateLastMessage(updatedText: String) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            val updatedMessage = lastMessage.copy(text = updatedText)
            currentList.dropLast(1) + updatedMessage
        }
    }

    private fun updateLastMessageDuration(durationMs: Long) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            if (lastMessage.sender == Sender.AGENT) {
                val updatedMessage = lastMessage.copy(durationMs = durationMs)
                currentList.dropLast(1) + updatedMessage
            } else {
                currentList
            }
        }
    }
    override fun loadHistory(history: List<ChatMessage>) {
        _messages.value = history.toList()
    }

    private fun detectRequiredTool(prompt: String): String? {
        val text = prompt.lowercase()

        if (text.contains("battery")) return "get_device_battery"

        val timeKeywords = listOf("time", "date", "datetime", "current time", "current date", "now")
        if (timeKeywords.any { text.contains(it) }) return "get_current_datetime"

        if (text.contains("weather") || text.contains("temperature") || text.contains("forecast")) {
            return "get_weather"
        }

        val fileKeywords = listOf("file", "files", "folder", "folders", "directory", "directories")
        val listKeywords = listOf("list", "show", "display", "print", "tree", "structure")
        val projectKeywords = listOf("project", "root")
        if (listKeywords.any { text.contains(it) } &&
            (fileKeywords.any { text.contains(it) } || projectKeywords.any { text.contains(it) })
        ) {
            return "list_files"
        }

        if (text.contains("file tree") || text.contains("project structure")) {
            return "list_files"
        }

        return null
    }
}
