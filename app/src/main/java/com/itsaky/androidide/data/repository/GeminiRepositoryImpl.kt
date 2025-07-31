package com.itsaky.androidide.data.repository

import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.data.model.ToolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class GeminiRepositoryImpl(
    firebaseAI: FirebaseAI,
    private val ideApi: IDEApiFacade,
) : GeminiRepository {

    private val generativeModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = "gemini-2.5-pro",
        tools = listOf(Tool.functionDeclarations(GeminiTools.allTools))
    )

    override var onToolCall: ((FunctionCallPart) -> Unit)? = null

    override suspend fun generateASimpleResponse(prompt: String): String {
        val history = mutableListOf(content(role = "user") { text(prompt) })
        val json = Json { ignoreUnknownKeys = true }

        // Loop to handle multi-turn function calls
        for (i in 1..10) { // Safety break after 10 turns
            val response = generativeModel.generateContent(history)

            val functionCalls = response.functionCalls
            if (functionCalls.isEmpty()) {
                // No function calls, we have our final text answer
                return response.text ?: "The operation was processed, but I have no final answer."
            }

            // Add the model's request to the history
            response.candidates.firstOrNull()?.content?.let { history.add(it) }

            // Execute all function calls and gather the results
            val toolResponses = functionCalls.map { functionCall ->
                onToolCall?.invoke(functionCall)
                val result: ToolResult = executeTool(functionCall)
                val resultJsonString = json.encodeToString(result)
                FunctionResponsePart(
                    name = functionCall.name,
                    response = json.parseToJsonElement(resultJsonString).jsonObject
                )
            }

            // Add the tool results to the history
            history.add(content(role = "tool") {
                parts.addAll(toolResponses)
            })
        }

        return "The request exceeded the maximum number of tool calls."
    }

    private fun executeTool(functionCall: FunctionCallPart): ToolResult {
        return when (functionCall.name) {
            "create_file" -> {
                val path = functionCall.args["path"]?.toString()?.removeSurrounding("\"") ?: ""
                val content =
                    functionCall.args["content"]?.toString()?.removeSurrounding("\"") ?: ""
                ideApi.createFile(path = path, content = content)
            }

            "update_file" -> {
                val path = functionCall.args["path"]?.toString()?.removeSurrounding("\"") ?: ""
                val content =
                    functionCall.args["content"]?.toString()?.removeSurrounding("\"") ?: ""
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

            "ask_user" -> ideApi.askUser(
                question = functionCall.args["question"].toString(),
                options = functionCall.args["options"]?.toString()?.split(",") ?: listOf("OK")
            )


            else -> ToolResult.failure("Unknown tool: ${functionCall.name}")
        }
    }
}