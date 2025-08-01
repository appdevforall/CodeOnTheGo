package com.itsaky.androidide.data.repository

import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionCallingConfig
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.content
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.data.model.ToolResult
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

    private val functionCallingModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = "gemini-2.5-pro",
        systemInstruction = content(role = "system") {
            text(
                "You are an expert programmer's assistant. You will use the provided tools " +
                        "to help the user manage their Android project files and build process."
            )
        },
        tools = listOf(Tool.functionDeclarations(GeminiTools.allTools)),
        toolConfig = ToolConfig(functionCallingConfig = FunctionCallingConfig.any())
    )

    private val searchModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = "gemini-2.5-pro",
        systemInstruction = content(role = "system") {
            text("You are a helpful assistant that answers questions using web searches.")
        },
        tools = listOf(Tool.googleSearch())
    )

    override var onToolCall: ((FunctionCallPart) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): String {
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
            else -> executeSearchRequest(prompt) // Default to search for general conversation
        }
    }

    private suspend fun executeSearchRequest(prompt: String): String {
        val response = searchModel.generateContent(prompt)
        return response.text ?: "I couldn't find an answer for that."
    }

    private suspend fun executeCodeRequest(prompt: String, history: List<ChatMessage>): String {
        val apiHistory = history.map { message ->
            content(role = if (message.sender == ChatMessage.Sender.USER) "user" else "model") {
                text(message.text)
            }
        }.toMutableList()

        apiHistory.add(content(role = "user") { text(prompt) })
        val json = Json { ignoreUnknownKeys = true }

        // The tool-calling loop now uses the functionCallingModel
        for (i in 1..10) {
            val response = functionCallingModel.generateContent(apiHistory)

            val functionCalls = response.functionCalls
            if (functionCalls.isEmpty()) {
                return response.text ?: "The operation was processed, but I have no final answer."
            }

            response.candidates.firstOrNull()?.content?.let { apiHistory.add(it) }

            val toolResponses = functionCalls.map { functionCall ->
                onToolCall?.invoke(functionCall)
                val result: ToolResult = executeTool(functionCall)
                onToolMessage?.invoke(result.message)
                val resultJsonString = json.encodeToString(result)
                FunctionResponsePart(
                    name = functionCall.name,
                    response = json.parseToJsonElement(resultJsonString).jsonObject
                )
            }

            apiHistory.add(content(role = "tool") { parts.addAll(toolResponses) })
        }

        return "The request exceeded the maximum number of tool calls."
    }

    private fun executeTool(functionCall: FunctionCallPart): ToolResult {
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


            "run_build" -> ideApi.runBuild(
                module = functionCall.args["module"].toString(),
                variant = functionCall.args["variant"].toString()
            )

            "read_file" -> ideApi.readFile(
                path = functionCall.args["path"].toString()
            )

            "list_files" -> ideApi.listFiles(
                path = functionCall.args["path"].toString(),
                recursive = functionCall.args["recursive"]?.toString().toBoolean()
            )

            "build_project" -> ideApi.buildProject()
            "run_app" -> ideApi.runApp()
            "add_dependency" -> ideApi.addDependency(
                dependencyString = functionCall.args["dependency_string"].toString(),
                buildFilePath = functionCall.args["build_file_path"].toString()
            )

            "get_build_output" -> ideApi.getBuildOutput()

            "ask_user" -> {
                val question = (functionCall.args["question"] as? JsonPrimitive)?.content ?: "..."

                // The 'options' argument is a JSON array. We need to deserialize it properly.
                val optionsJson = functionCall.args["options"]
                val options = optionsJson?.let {
                    Json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
                } ?: listOf()

                // Invoke the callback to notify the ViewModel/UI
                onAskUser?.invoke(question, options)

                // Return a result to the AI, confirming the question was asked.
                ToolResult(
                    success = true,
                    message = "The user has been asked the question. Await their response in the next turn."
                )
            }


            else -> ToolResult.failure("Unknown tool: ${functionCall.name}")
        }
    }

    override suspend fun generateCode(prompt: String): String {
        val response = functionCallingModel.generateContent(prompt)
        return response.text ?: throw Exception("Failed to get a valid response from the API.")
    }
}