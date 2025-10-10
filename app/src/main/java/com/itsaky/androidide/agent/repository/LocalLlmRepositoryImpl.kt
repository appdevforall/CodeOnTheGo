package com.itsaky.androidide.agent.repository

import android.content.Context
import android.util.Log
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.data.ToolCall
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

    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onToolCall: ((ToolCall) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null
    override var onProgressUpdate: ((message: ChatMessage) -> Unit)? = null
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
            if (success) "Local model loaded successfully!" else "Error: Failed to load local model."
        onStateUpdate?.invoke(AgentState.Processing(status))
        onStateUpdate?.invoke(AgentState.Idle)
        return success
    }

    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool(),
        GetWeatherTool(),
    ).associateBy { it.name }

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
    }
    private fun buildPromptWithHistory(
        history: List<ChatMessage>,
        isFinalAnswerTurn: Boolean
    ): String {
        return when (engine.currentModelFamily) {
            ModelFamily.LLAMA3 -> buildLlama3Prompt(history)
            ModelFamily.GEMMA2 -> {
                if (isFinalAnswerTurn) {
                    buildGemma2FinalAnswerPrompt(history)
                } else {
                    buildGemma2Prompt(history)
                }
            }

            else -> history.lastOrNull { it.sender == Sender.USER }?.text ?: ""
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        if (!engine.isModelLoaded) {
            return AgentResponse(
                text = "No local model is currently loaded. Please select one in AI Settings.",
                report = ""
            )
        }
        addMessage(prompt, Sender.USER)
        val placeholder = ""
        addMessage(placeholder, Sender.AGENT)

        runAgentLoop()
        return AgentResponse("Request exceeded maximum tool calls.", toolTracker.generateReport())
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun runAgentLoop() {
        toolTracker.startTracking()

        val maxTurns = 5
        var currentTurn = 0
        while (currentTurn < maxTurns) {
            Log.d("AgentDebug", "--- [Step ${currentTurn + 1}] ---")
            val currentHistory = _messages.value
            val isFinalAnswerTurn =
                currentHistory.getOrNull(currentHistory.size - 2)?.sender == Sender.TOOL

            // 2. UPDATE THE STOP STRINGS
            val stopStrings = if (isFinalAnswerTurn) {
                // For the final answer, stop before the model hallucinates a new question.
                listOf("Question:", "\n\n")
            } else {
                // For tool selection, we now expect a structured XML tag.
                listOf("</tool_call>")
            }

            val fullPromptHistory = buildPromptWithHistory(currentHistory, isFinalAnswerTurn)
            Log.d("AgentDebug", "Final Prompt Sent:\n$fullPromptHistory")
            val startTime = System.nanoTime()
            val modelResponse = try {
                withContext(Dispatchers.IO) {
                    engine.runInference(fullPromptHistory, stopStrings = stopStrings)
                }
            } catch (e: Exception) {
                Log.e("AgentLoop", "Model inference failed", e)
                "Error: Could not get a response from the model."
            }
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            val finalResponse = modelResponse.split(stopStrings.first()).first()
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

                Log.d("AgentDebug", "Final answer received. Concluding.")
                updateLastMessageDuration(durationMs)
                break
            } else {
                val toolCall = Util.parseToolCall(finalResponse, tools.keys)

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
                        updateLastMessageDuration(durationMs)

                        // Execute the tool with the parsed arguments
                        val result = tool.execute(context, toolCall.args)
                        addMessage(result, Sender.TOOL)
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
                    updateLastMessageDuration(durationMs)
                    Log.d("AgentDebug", "No tool call detected. Model gave a direct answer.")
                    break
                }
            }
            currentTurn++
        }
    }

    private fun buildGemma2Prompt(history: List<ChatMessage>): String {
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

            val systemInstruction = """
You are a helpful assistant with access to the following tools:
[$toolsAsJson]

To use a tool, respond with a single `<tool_call>` XML tag containing a JSON object with the tool's 'name' and 'args'.
If no tool is needed, answer the user's question directly.

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

    private fun buildLlama3Prompt(history: List<ChatMessage>): String {
        val historyBuilder = StringBuilder()
        historyBuilder.append("<|begin_of_text|>")
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt<|eot_id|>")
        for (message in history) {
            when (message.sender) {
                Sender.USER -> historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                Sender.AGENT -> {
                    if (message.text.isNotBlank()) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}")
                    }
                }

                Sender.SYSTEM -> {}
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
}