package com.itsaky.androidide.data.repository

import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionCallingConfig
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.content
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class GeminiRepositoryImpl(
    firebaseAI: FirebaseAI,
    private val ideApi: IDEApiFacade,
) : GeminiRepository {

    private val toolTracker = ToolExecutionTracker()

    val modelName = "gemini-2.5-pro"
    private val functionCallingModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName,
        systemInstruction = content(role = "system") {
            text(
                "You are an expert programmer's assistant. You will use the provided tools " +
                        "to help the user manage their Android project files and build process. " +
                        "**Crucially, when asked to add or change text that will be displayed to the user, you must prefer using the `add_string_resource` tool to create a string resource. Then, use the returned resource ID (e.g., `R.string.your_string_name`) in the code. Avoid hardcoding user-facing strings directly in the source code whenever possible.**"
            )
        },
        tools = listOf(Tool.functionDeclarations(GeminiTools.allTools)),
        toolConfig = ToolConfig(functionCallingConfig = FunctionCallingConfig.any())
    )

    private val searchModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName,
        systemInstruction = content(role = "system") {
            text("You are a helpful assistant that answers questions using web searches.")
        },
        tools = listOf(Tool.googleSearch())
    )

    private val codeGenerationModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName,
        systemInstruction = content(role = "system") {
            text("You are an expert code generation assistant. You only respond with raw code based on the user's prompt. Do not add any explanations, comments, or markdown formatting like ```. Your response must be only the code itself.")
        }
    )

    override var onToolCall: ((FunctionCallPart) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        val routerPrompt = """
            Analyze the following user prompt and determine the primary intent.
            Respond with a single word: CODE, SEARCH, or OTHER.
            - Use CODE if the request involves file manipulation, building the project, or interacting with the IDE.
            - Use SEARCH if the request requires up-to-date information from the internet or general knowledge.
            - Use OTHER for conversational chat.

            User Prompt: "$prompt"
        """.trimIndent()

        // Use the search model for routing as it's general purpose
        val routeResult = searchModel.generateContent(routerPrompt).text?.trim()?.uppercase()

        return when (routeResult) {
            "CODE" -> executeCodeRequest(prompt, history)
            "SEARCH" -> executeSearchRequest(prompt)
            else -> executeSearchRequest(prompt)
        }
    }

    private suspend fun executeSearchRequest(prompt: String): AgentResponse {
        val response = searchModel.generateContent(prompt)
        val text = response.text ?: "I couldn't find an answer for that."
        return AgentResponse(text = text, report = "")
    }

    private suspend fun executeCodeRequest(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        val apiHistory = history.map { message ->
            content(role = if (message.sender == ChatMessage.Sender.USER) "user" else "model") {
                text(message.text)
            }
        }.toMutableList()
        apiHistory.add(content(role = "user") { text(prompt) })
        val json = Json { ignoreUnknownKeys = true }

        toolTracker.startTracking() // Start tracking for this new request

        for (i in 1..10) {
            onStateUpdate?.invoke(AgentState.Processing("Waiting for Gemini..."))
            val response = functionCallingModel.generateContent(apiHistory)

            val functionCalls = response.functionCalls
            if (functionCalls.isEmpty()) {
                val finalReport = toolTracker.generateReport()
                val responseText = response.text ?: "Operation complete."
                return AgentResponse(text = responseText, report = finalReport)
            }

            response.candidates.firstOrNull()?.content?.let { apiHistory.add(it) }

            val toolResponses = functionCalls.map { functionCall ->
                onStateUpdate?.invoke(AgentState.Processing("Executing tool: `${functionCall.name}`"))

                val toolStartTime = System.currentTimeMillis()
                val result: ToolResult = executeTool(functionCall)
                val toolDuration = System.currentTimeMillis() - toolStartTime

                toolTracker.logToolCall(
                    functionCall.name,
                    toolDuration
                )
                onToolCall?.invoke(functionCall)
                onToolMessage?.invoke(result.message)

                val resultJsonString = json.encodeToString(result)
                FunctionResponsePart(
                    name = functionCall.name,
                    response = json.parseToJsonElement(resultJsonString).jsonObject
                )
            }

            apiHistory.add(content(role = "tool") { parts.addAll(toolResponses) })
        }

        val finalReport = toolTracker.generateReport()
        return AgentResponse(
            text = "The request exceeded the maximum number of tool calls.",
            report = finalReport
        )
    }

    private suspend fun executeTool(functionCall: FunctionCallPart): ToolResult {
        return when (functionCall.name) {
            "create_file" -> {
                val path = (functionCall.args["path"] as? JsonPrimitive)?.content ?: ""
                val content = (functionCall.args["content"] as? JsonPrimitive)?.content ?: ""
                ideApi.createFile(path = path, content = content)
            }

            "update_file" -> {
                val path = (functionCall.args["path"] as? JsonPrimitive)?.content ?: ""
                val content = (functionCall.args["content"] as? JsonPrimitive)?.content ?: ""
                ideApi.updateFile(path, content)
            }

            "read_file" -> ideApi.readFile(
                path = functionCall.args["path"].toString()
            )

            "list_files" -> ideApi.listFiles(
                path = functionCall.args["path"].toString(),
                recursive = functionCall.args["recursive"]?.toString().toBoolean()
            )

            "run_app" -> ideApi.runApp()
//            "add_dependency" -> ideApi.addDependency(
//                dependencyString = functionCall.args["dependency_string"].toString(),
//                buildFilePath = functionCall.args["build_file_path"].toString()
//            )

            "get_build_output" -> ideApi.getBuildOutput()

            "add_string_resource" -> {
                val name = (functionCall.args["name"] as? JsonPrimitive)?.content ?: ""
                val value = (functionCall.args["value"] as? JsonPrimitive)?.content ?: ""
                if (name.isEmpty() || value.isEmpty()) {
                    ToolResult.failure("Both 'name' and 'value' parameters are required for add_string_resource.")
                } else {
                    ideApi.addStringResource(name, value)
                }
            }

            "ask_user" -> {
                val question = (functionCall.args["question"] as? JsonPrimitive)?.content ?: "..."

                val optionsJson = functionCall.args["options"]
                val options = optionsJson?.let {
                    Json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
                } ?: listOf()

                onAskUser?.invoke(question, options)

                ToolResult(
                    success = true,
                    message = "The user has been asked the question. Await their response in the next turn."
                )
            }


            else -> ToolResult.failure("Unknown tool: ${functionCall.name}")
        }
    }

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
        // Create a detailed prompt with all the context
        val contextPrompt = """
        You are an expert code generation assistant. The user is currently editing the file '$fileName' located at '$fileRelativePath'.

        This is the current full content of the file:
        ```
        $fileContent
        ```

        Based on this context, fulfill the user's request.

        User Request: "$prompt"

        IMPORTANT: Your response must be only the raw code itself. Do not add any explanations, comments, or markdown formatting like ```.
        """.trimIndent()

        val response = codeGenerationModel.generateContent(contextPrompt)
        return response.text ?: throw Exception("Failed to get a valid response from the API.")
    }

    override fun getPartialReport(): String {
        return toolTracker.generatePartialReport()
    }
}