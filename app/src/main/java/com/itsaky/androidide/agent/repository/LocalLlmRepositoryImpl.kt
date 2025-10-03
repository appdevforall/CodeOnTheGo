package com.itsaky.androidide.agent.repository

import android.content.Context
import android.util.Log
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.data.ToolCall
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

enum class MessageType {
    SYSTEM, USER, MODEL
}
data class UiMessage(
    val id: Long,
    val text: String,
    val type: MessageType
)

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
    private val ideApi: IDEApiFacade = IDEApiFacade,
) : GeminiRepository {

    private val toolTracker = ToolExecutionTracker()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Regex to find a JSON block inside our custom markers
    private val toolCallRegex = Regex("\\[TOOL_CALL\\](.*?)\\[/TOOL_CALL\\]", RegexOption.DOT_MATCHES_ALL)

    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onToolCall: ((ToolCall) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null

    suspend fun loadModel(modelUriString: String): Boolean {
        onStateUpdate?.invoke(AgentState.Processing("Loading local model..."))
        val success = LlmInferenceEngine.initModelFromFile(context, modelUriString)
        val status = if (success) "Local model loaded successfully!" else "Error: Failed to load local model."
        onStateUpdate?.invoke(AgentState.Processing(status))
        onStateUpdate?.invoke(AgentState.Idle)
        return success
    }

    private fun buildSystemPrompt(): String {
        val toolDefinitions = LocalLlmTools.allTools.joinToString("\n") { tool ->
            val params = tool.parameters.entries.joinToString(", ") { (key, desc) -> """"$key": "string" // $desc""" }
            "- ${tool.name}({ $params })"
        }

        return """
        You are a helpful programmer's assistant. To accomplish the user's request, you must use the available tools.
        To use a tool, you MUST respond with ONLY a JSON object inside [TOOL_CALL] and [/TOOL_CALL] markers. Your response should contain nothing else.
        The JSON object must have "tool_name" and "parameters" keys.

        Example of a tool call:
        [TOOL_CALL]
        {
          "tool_name": "list_files",
          "parameters": {
            "path": ".",
            "recursive": "false"
          }
        }
        [/TOOL_CALL]

        If you believe you have fully answered the user's question, respond with the final answer in plain text without any markers.

        Available tools:
        $toolDefinitions
        """.trimIndent()
    }

    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool()
    ).associateBy { it.name }

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
    }
    private fun buildPromptWithHistory(history: List<UiMessage>): String {
        val historyBuilder = StringBuilder()

        // Add the special begin_of_text token ONLY at the start.
        historyBuilder.append("<|begin_of_text|>")
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt<|eot_id|>")

        for (message in history) {
            when (message.type) {
                MessageType.USER -> {
                    historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                }
                // The placeholder is the last message, so we don't append it to the prompt.
                MessageType.MODEL -> {
                    if (message.text.isNotBlank()) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}")
                    }
                }
                // There are no SYSTEM messages in the initial turn.
                MessageType.SYSTEM -> {}
            }
        }

        // Prompt the assistant to start its turn.
        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n")

        return historyBuilder.toString()
    }
    var conversation = listOf<UiMessage>()

    override suspend fun generateASimpleResponse(prompt: String, history: List<ChatMessage>): AgentResponse {
        if (!LlmInferenceEngine.isModelLoaded) {
            return AgentResponse(text = "No local model is currently loaded. Please select one in AI Settings.", report = "")
        }

        LlmInferenceEngine.clearKvCache()
        addMessage(prompt, MessageType.USER)
        val placeholder = "..."
        addMessage(placeholder, MessageType.MODEL)
        var fullPromptHistory = buildPromptWithHistory(conversation)

        toolTracker.startTracking()
        val fullHistory = mutableListOf(
            ChatMessage(text = buildSystemPrompt(), sender = ChatMessage.Sender.SYSTEM)
        )
        fullHistory.addAll(history)
        fullHistory.add(ChatMessage(text = prompt, sender = ChatMessage.Sender.USER))

        // Agentic loop for tool usage
        for (i in 1..10) { // Max 10 turns
            onStateUpdate?.invoke(AgentState.Processing("Local LLM is thinking... (Turn ${i})"))
            val isFinalAnswerTurn = i > 1
            val stopStrings = if (isFinalAnswerTurn) {
                listOf("<|eot_id|>")
            } else {
                listOf("<|eot_id|>", "</tool_call>")
            }

            val rawResponse = LlmInferenceEngine.runInference(fullPromptHistory, stopStrings)

            Log.d("AgentDebug", "Raw Model Result: \"$rawResponse\"")
            // Add the model's raw response to history
            fullHistory.add(ChatMessage(text = rawResponse, sender = ChatMessage.Sender.AGENT))

            val toolMatch = parseToolCall(rawResponse)
            if (toolMatch == null) {
                // No tool call found, this is the final answer
                onStateUpdate?.invoke(AgentState.Idle)
                updateLastMessage(rawResponse.trim())
                Log.d("AgentDebug", "No tool call detected. Concluding.")
                return AgentResponse(text = rawResponse.trim(), report = toolTracker.generateReport())
            }

            val toolCallEndIndex = rawResponse.indexOf("</tool_call>")
            val trimmedResponse =
                rawResponse.substring(0, toolCallEndIndex + "</tool_call>".length)

            updateLastMessage(trimmedResponse)
            Log.d("AgentDebug", "Tool Call Detected: $toolMatch")

            val tool = tools[toolMatch.name]
            if (tool != null) {
                val result = tool.execute(context, toolMatch.args ?: emptyMap())
                Log.d("AgentDebug", "Tool Response: \"$result\"")

                addMessage(result, MessageType.SYSTEM)

                addMessage("", MessageType.MODEL)

                fullPromptHistory += "$trimmedResponse<|eot_id|>"
                fullPromptHistory += """
                <|start_header_id|>user<|end_header_id|>

                [TOOL_RESULT]
                $result
                [/TOOL_RESULT]<|eot_id|><|start_header_id|>assistant<|end_header_id|>

                Based on the tool results, here is the answer to the user's question:
                """.trimIndent()

            } else {
                val errorMsg = "Error: Model tried to call unknown tool '${toolMatch.name}'"
                updateLastMessage(errorMsg) // Update placeholder with error
                Log.e("AgentDebug", errorMsg)
                break // Exit loop on error
            }
        }

        onStateUpdate?.invoke(AgentState.Idle)
        return AgentResponse("Request exceeded maximum tool calls.", toolTracker.generateReport())
    }
    private val messageIdCounter = AtomicLong(0)
    private fun addMessage(text: String, type: MessageType) {
        val message = UiMessage(messageIdCounter.getAndIncrement(), text, type)
        conversation = conversation + message
//        _uiMessages.postValue(conversation)
    }

    private fun updateLastMessage(updatedText: String) {
        if (conversation.isNotEmpty()) {
            val lastMessage = conversation.last()
            val updatedMessage = lastMessage.copy(text = updatedText)
            conversation = conversation.dropLast(1) + updatedMessage
//            _uiMessages.postValue(conversation)
        }
    }

    private suspend fun executeTool(toolCall: ToolCallRequest): ToolResult {
        val params = toolCall.parameters
        return when (toolCall.tool_name) {
            "create_file" -> ideApi.createFile(path = params.stringParam("path"), content = params.stringParam("content"))
            "read_file" -> ideApi.readFile(path = params.stringParam("path"))
            "update_file" -> ideApi.updateFile(path = params.stringParam("path"), content = params.stringParam("content"))
            "delete_file" -> ideApi.deleteFile(path = params.stringParam("path"))
            "list_files" -> ideApi.listFiles(path = params.stringParam("path"), recursive = params.booleanParam("recursive"))
            "add_dependency" -> {
                val dep = params.stringParam("dependency")
                val path = params.stringParam("build_file_path")
                if (dep.isEmpty() || path.isEmpty()) ToolResult.failure("'dependency' and 'build_file_path' are required.")
                else ideApi.addDependency(if (path.endsWith(".kts")) "implementation(\"$dep\")" else "implementation '$dep'", path)
            }
            "add_string_resource" -> {
                val name = params.stringParam("name")
                val value = params.stringParam("value")
                if (name.isEmpty() || value.isEmpty()) ToolResult.failure("'name' and 'value' are required.")
                else ideApi.addStringResource(name, value)
            }
            "run_app" -> ideApi.runApp()
            "trigger_gradle_sync" -> ideApi.triggerGradleSync()
            "get_build_output" -> ideApi.getBuildOutput()
            "ask_user" -> {
                val question = params.stringParam("question", "...")
                val optionsJson = params["options"]
                val options = optionsJson?.let { json.decodeFromJsonElement(ListSerializer(String.serializer()), it) } ?: listOf()
                onAskUser?.invoke(question, options)
                ToolResult.success("User has been asked. Await their response.")
            }
            else -> ToolResult.failure("Unknown tool: ${toolCall.tool_name}")
        }
    }

    private fun Map<String, JsonElement>.stringParam(key: String, default: String = ""): String = this[key]?.jsonPrimitive?.content ?: default
    private fun Map<String, JsonElement>.booleanParam(key: String, default: Boolean = false): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default

    @Serializable
    private data class ToolCallRequest(val tool_name: String, val parameters: Map<String, JsonElement>)

    override fun getPartialReport(): String = toolTracker.generatePartialReport()
    override suspend fun generateCode(prompt: String, fileContent: String, fileName: String, fileRelativePath: String): String {
        onStateUpdate?.invoke(AgentState.Processing("Generating code with local LLM..."))
        val response = LlmInferenceEngine.runInference(
            "USER: Based on the file $fileName, $prompt\nASSISTANT:",
            emptyList()
        )
        onStateUpdate?.invoke(AgentState.Idle)
        return response
    }

    override fun stop() {
        LlmInferenceEngine.stopInference()
    }

    private fun parseToolCall(text: String): ToolCall? {
        val pattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            val jsonStr = matcher.group(1)?.trim()
            if (jsonStr.isNullOrBlank()) {
                Log.e("ToolParse", "Found tool_call tags but the content was empty.")
                return null
            }

            try {
                val json = JSONObject(jsonStr)
                val toolName = json.getString("tool_name")
                val argsJson = json.getJSONObject("args")
                val argsMap = mutableMapOf<String, JsonElement>()
                argsJson.keys().forEach { key ->
                    argsMap[key] = argsJson.get(key) as JsonElement
                }
                return ToolCall(toolName, argsMap)
            } catch (e: Exception) {
                Log.e("ToolParse", "Failed to parse tool call JSON", e)
                return null
            }
        }
        return null
    }
}