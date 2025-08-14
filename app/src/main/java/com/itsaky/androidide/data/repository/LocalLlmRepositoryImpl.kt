package com.itsaky.androidide.data.repository

import android.content.Context
import com.google.firebase.ai.type.FunctionCallPart
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.app.LlmInferenceEngine
import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class LocalLlmRepositoryImpl(
    private val context: Context,
    private val ideApi: IDEApiFacade,
) : GeminiRepository {

    private var isModelLoaded = false
    private val toolTracker = ToolExecutionTracker()
    private val json = Json { ignoreUnknownKeys = true }

    // A simple regex to find the <tool_code> block
    private val toolCodeRegex = Regex("<tool_code>(.*?)</tool_code>", RegexOption.DOT_MATCHES_ALL)

    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onToolCall: ((FunctionCallPart) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null

    suspend fun loadModel(modelUriString: String): Boolean {
        if (isModelLoaded) {
            LlmInferenceEngine.releaseModel()
        }
        onStateUpdate?.invoke(AgentState.Processing("Loading local model..."))
        isModelLoaded = LlmInferenceEngine.initModelFromFile(context, modelUriString)
        val status =
            if (isModelLoaded) "Local model loaded successfully!" else "Error: Failed to load local model."
        onStateUpdate?.invoke(AgentState.Processing(status))
        onStateUpdate?.invoke(AgentState.Idle)
        return isModelLoaded
    }

    private fun buildSystemPrompt(): String {
        val toolDefinitions = LocalLlmTools.allTools.joinToString("\n") { tool ->
            val params = tool.parameters.entries.joinToString(", ") { (key, _) -> "$key: string" }
            "${tool.name}($params) - ${tool.description}"
        }

        // ✅ Instruct the model to use plain text markers instead of XML/HTML tags.
        return """
    You are an expert programmer's assistant. Use the provided tools to help the user.
    To use a tool, respond with ONLY a JSON block wrapped in [TOOL_CALL] and [/TOOL_CALL] markers.

    Example:
    [TOOL_CALL]
    {
      "tool_name": "create_file",
      "parameters": {
        "path": "app/src/main/res/values/strings.xml",
        "content": "<resources></resources>"
      }
    }
    [/TOOL_CALL]

    If you can answer directly, do not use any markers.

    Available tools:
    $toolDefinitions
    """.trimIndent()
    }

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        if (!isModelLoaded) {
            return AgentResponse(text = "No local model is currently loaded.", report = "")
        }

        toolTracker.startTracking()
        val systemPrompt = buildSystemPrompt()
        val fullHistory = mutableListOf(
            ChatMessage(
                text = systemPrompt,
                sender = ChatMessage.Sender.SYSTEM,
                timestamp = System.currentTimeMillis()
            )
        )
        fullHistory.addAll(history)
        fullHistory.add(
            ChatMessage(
                text = prompt,
                sender = ChatMessage.Sender.USER,
                timestamp = System.currentTimeMillis()
            )
        )


        for (i in 1..10) { // Max 10 turns
            onStateUpdate?.invoke(AgentState.Processing("Waiting for local LLM..."))

            val currentPrompt = fullHistory.joinToString("\n") {
                // A simple prompt format for the local model
                when (it.sender) {
                    ChatMessage.Sender.USER -> "USER: ${it.text}"
                    ChatMessage.Sender.AGENT -> "ASSISTANT: ${it.text}"
                    ChatMessage.Sender.SYSTEM -> it.text
                    // ✅ FIX 1: Add a case to correctly format tool results for the model.
                    ChatMessage.Sender.TOOL -> "TOOL_RESULT: ${it.text}"
                }
            } + "\nASSISTANT:"

            val rawResponse = LlmInferenceEngine.runInference(currentPrompt)

            val toolMatch = toolCodeRegex.find(rawResponse)

            if (toolMatch == null) {
                // No tool call detected, this is the final answer
                val finalReport = toolTracker.generateReport()
                return AgentResponse(text = rawResponse, report = finalReport)
            }

            // ✅ FIX 2: Add the model's response to history with the correct sender.
            fullHistory.add(
                ChatMessage(
                    text = rawResponse,
                    sender = ChatMessage.Sender.AGENT,
                    timestamp = System.currentTimeMillis()
                )
            )

            val jsonContent = toolMatch.groupValues[1]
            try {
                val toolCallRequest = json.decodeFromString<ToolCallRequest>(jsonContent)

                // For UI Callbacks (reusing Firebase type for simplicity)
                val functionCallPart = FunctionCallPart(
                    name = toolCallRequest.tool_name,
                    args = toolCallRequest.parameters.mapValues { it.value } // Simplified conversion
                )
                onToolCall?.invoke(functionCallPart)
                onStateUpdate?.invoke(AgentState.Processing("Executing tool: `${toolCallRequest.tool_name}`"))

                val toolStartTime = System.currentTimeMillis()
                val result: ToolResult = executeTool(toolCallRequest)
                val toolDuration = System.currentTimeMillis() - toolStartTime
                toolTracker.logToolCall(toolCallRequest.tool_name, toolDuration)
                onToolMessage?.invoke(result.message)

                // Add tool result to history for the next turn
                val resultJsonString = json.encodeToString(result)
                // This now uses the new 'TOOL' sender type, which is handled above.
                fullHistory.add(
                    ChatMessage(
                        text = resultJsonString,
                        sender = ChatMessage.Sender.TOOL,
                        timestamp = System.currentTimeMillis()
                    )
                )

            } catch (e: Exception) {
                // Failed to parse or execute the tool call
                val errorMsg = "Error processing tool call: ${e.message}"
                fullHistory.add(
                    ChatMessage(
                        text = errorMsg,
                        sender = ChatMessage.Sender.TOOL,
                        timestamp = System.currentTimeMillis()
                    )
                )
                // Continue the loop, letting the model know its tool call failed
            }
        }

        return AgentResponse("Request exceeded maximum tool calls.", toolTracker.generateReport())
    }


    // Place these helper functions inside your LocalLlmRepositoryImpl class
    private fun Map<String, JsonElement>.stringParam(key: String, default: String = ""): String {
        return this[key]?.jsonPrimitive?.content ?: default
    }

    private fun Map<String, JsonElement>.booleanParam(
        key: String,
        default: Boolean = false
    ): Boolean {
        return this[key]?.jsonPrimitive?.booleanOrNull ?: default
    }

    private suspend fun executeTool(toolCall: ToolCallRequest): ToolResult {
        // Use the helper functions to cleanly extract parameters
        val params = toolCall.parameters

        return when (toolCall.tool_name) {
            "create_file" -> {
                ideApi.createFile(
                    path = params.stringParam("path"),
                    content = params.stringParam("content")
                )
            }

            "update_file" -> {
                ideApi.updateFile(
                    path = params.stringParam("path"),
                    content = params.stringParam("content")
                )
            }

            "read_file" -> {
                ideApi.readFile(
                    path = params.stringParam("path")
                )
            }

            "list_files" -> {
                ideApi.listFiles(
                    path = params.stringParam("path"),
                    recursive = params.booleanParam("recursive")
                )
            }

            "run_app" -> {
                ideApi.runApp()
            }

            "add_dependency" -> {
                val dependencyString = params.stringParam("dependency_string")
                if (dependencyString.isEmpty()) {
                    ToolResult.failure("The 'dependency_string' parameter is required.")
                } else {
                    ideApi.addDependency(
                        dependencyString = dependencyString,
                        buildFilePath = params.stringParam("build_file_path")
                    )
                }
            }

            "get_build_output" -> {
                ideApi.getBuildOutput()
            }

            "add_string_resource" -> {
                val name = params.stringParam("name")
                val value = params.stringParam("value")
                if (name.isEmpty() || value.isEmpty()) {
                    ToolResult.failure("Both 'name' and 'value' parameters are required for add_string_resource.")
                } else {
                    ideApi.addStringResource(name, value)
                }
            }

            "ask_user" -> {
                val question = params.stringParam("question", "...")
                val optionsJson = params["options"]
                val options = optionsJson?.let {
                    // The JSON library can decode directly from a JsonElement
                    json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
                } ?: listOf()

                onAskUser?.invoke(question, options)

                ToolResult(
                    success = true,
                    message = "The user has been asked the question. Await their response in the next turn."
                )
            }

            else -> ToolResult.failure("Unknown tool: ${toolCall.tool_name}")
        }
    }

    // Helper data class for deserializing the model's tool request
    @Serializable
    private data class ToolCallRequest(
        val tool_name: String,
        val parameters: Map<String, kotlinx.serialization.json.JsonElement>
    )

    override fun getPartialReport(): String {
        return toolTracker.generatePartialReport()
    }

    override suspend fun generateCode(
        prompt: String, fileContent: String, fileName: String, fileRelativePath: String
    ): String {
        // This implementation can remain separate as it doesn't use the tool-calling loop
        return "Code generation not yet implemented for the local LLM."
    }
}
