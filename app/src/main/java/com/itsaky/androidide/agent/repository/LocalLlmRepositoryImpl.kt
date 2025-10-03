package com.itsaky.androidide.agent.repository

import android.content.Context
import android.util.Log
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.MessageStatus
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.data.ToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.regex.Pattern

@Serializable
data class ParsedToolCall(
    @SerialName("tool_name") val name: String,
    val args: Map<String, JsonElement>? = null
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
    private val context: Context
) : GeminiRepository {

    private val toolTracker = ToolExecutionTracker()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onToolCall: ((ToolCall) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null
    override var onProgressUpdate: ((message: ChatMessage) -> Unit)? = null


    suspend fun loadModel(modelUriString: String): Boolean {
        onStateUpdate?.invoke(AgentState.Processing("Loading local model..."))
        val success = LlmInferenceEngine.initModelFromFile(context, modelUriString)
        val status =
            if (success) "Local model loaded successfully!" else "Error: Failed to load local model."
        onStateUpdate?.invoke(AgentState.Processing(status))
        onStateUpdate?.invoke(AgentState.Idle)
        return success
    }

    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool()
    ).associateBy { it.name }

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
    }

    private fun buildPromptWithHistory(history: List<ChatMessage>): String {
        val historyBuilder = StringBuilder()
        historyBuilder.append("<|begin_of_text|>")
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt<|eot_id|>")

        for (message in history) {
            when (message.sender) {
                ChatMessage.Sender.USER -> {
                    historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                }

                ChatMessage.Sender.AGENT -> {
                    if (message.text.isNotBlank() && message.status != MessageStatus.LOADING) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}<|eot_id|>")
                    }
                }

                else -> {}
            }
        }
        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return historyBuilder.toString()
    }

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        if (!LlmInferenceEngine.isModelLoaded) {
            return AgentResponse(
                text = "No local model is currently loaded. Please select one in AI Settings.",
                report = ""
            )
        }

        toolTracker.startTracking()
        LlmInferenceEngine.clearKvCache()

        var fullPromptHistory = buildPromptWithHistory(history)

        for (i in 1..10) {
            onStateUpdate?.invoke(AgentState.Processing("Local LLM is thinking... (Turn ${i})"))

            val stopStrings = listOf("<|eot_id|>", "</tool_call>")
            val rawResponse = LlmInferenceEngine.runInference(fullPromptHistory, stopStrings)
            Log.d("AgentDebug", "Raw Model Result: \"$rawResponse\"")

            val toolMatch = parseToolCall(rawResponse)
            if (toolMatch == null) {
                onStateUpdate?.invoke(AgentState.Idle)
                Log.d("AgentDebug", "No tool call detected. Concluding.")
                return AgentResponse(
                    text = rawResponse.trim(),
                    report = toolTracker.generateReport()
                )
            }

            val toolCallEndIndex = rawResponse.indexOf("</tool_call>")
            val toolCallText = rawResponse.substring(0, toolCallEndIndex + "</tool_call>".length)

            val thoughtMessage =
                "🤖 **Thought:** I will use the `${toolMatch.name}` tool.\n```json\n$toolCallText\n```"
            onProgressUpdate?.invoke(
                ChatMessage(
                    text = thoughtMessage,
                    sender = ChatMessage.Sender.SYSTEM
                )
            )

            Log.d("AgentDebug", "Tool Call Detected: $toolMatch")

            val tool = tools[toolMatch.name]
            if (tool != null) {
                val result = tool.execute(context, toolMatch.args ?: emptyMap())
                Log.d("AgentDebug", "Tool Response: \"$result\"")

                val toolResultMessage = "✅ **Tool Result:**\n```\n$result\n```"
                onProgressUpdate?.invoke(
                    ChatMessage(
                        text = toolResultMessage,
                        sender = ChatMessage.Sender.SYSTEM
                    )
                )

                fullPromptHistory += "$toolCallText<|eot_id|>"
                fullPromptHistory += """
                <|start_header_id|>user<|end_header_id|>

                [TOOL_RESULT]
                $result
                [/TOOL_RESULT]<|eot_id|><|start_header_id|>assistant<|end_header_id|>

                Based on the tool results, here is the answer to the user's question:
                """.trimIndent()

            } else {
                val errorMsg = "Error: Model tried to call unknown tool '${toolMatch.name}'"
                onProgressUpdate?.invoke(
                    ChatMessage(
                        text = errorMsg,
                        sender = ChatMessage.Sender.SYSTEM
                    )
                )
                Log.e("AgentDebug", errorMsg)
                break // Exit loop on error
            }
        }

        onStateUpdate?.invoke(AgentState.Idle)
        return AgentResponse("Request exceeded maximum tool calls.", toolTracker.generateReport())
    }

    override fun getPartialReport(): String = toolTracker.generatePartialReport()
    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
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
}