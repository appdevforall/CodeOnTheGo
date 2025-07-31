package com.itsaky.androidide.data.repository

import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.api.IDEApiFacade
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GeminiRepositoryImpl(
    firebaseAI: FirebaseAI,
    private val ideApi: IDEApiFacade,
    private val fileActionManager: FileActionManager
) : GeminiRepository {

    private val createFileTool = FunctionDeclaration(
        name = "create_file",
        description = "Creates a file at a given relative path with the specified content.",
        parameters = mapOf(
            "path" to Schema.string("The relative path where the file should be created."),
            "content" to Schema.string("The content to write into the file.")
        )
    )

    private val generativeModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = "gemini-2.5-flash",
        tools = listOf(Tool.functionDeclarations(listOf(createFileTool)))
    )

    override suspend fun generateASimpleResponse(prompt: String): String {
        try {
            val history = mutableListOf<Content>()

            history.add(content(role = "user") { text(prompt) })

            val response = generativeModel.generateContent(history)

            val modelResponseContent = response.candidates.firstOrNull()?.content
            if (modelResponseContent != null) {
                history.add(modelResponseContent)
            }

            val functionCall = response.functionCalls.firstOrNull()
            if (functionCall != null && functionCall.name == "create_file") {
                val path = functionCall.args["path"].toString().removeSurrounding("\"")
                val content = functionCall.args["content"].toString().removeSurrounding("\"")

                val result = ideApi.createFile(path, content)

                val functionResponse = if (result.isSuccess) {
                    "Successfully created file at ${result.getOrNull()?.name}"
                } else {
                    "Failed to create file: ${result.exceptionOrNull()?.message}"
                }

                history.add(content(role = "tool") {
                    part(FunctionResponsePart(functionCall.name, buildJsonObject {
                        put("result", functionResponse)
                    }))
                })

                val finalResponse = generativeModel.generateContent(history)
                return finalResponse.text
                    ?: "The operation was processed, but I have nothing more to say."

            } else {
                return response.text ?: "I'm not sure how to help with that."
            }
        } catch (e: Exception) {
            return "An error occurred: ${e.message}"
        }
    }
}