package com.itsaky.androidide.data.repository

// BOLD: Add these new imports for Kotlinx Serialization
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

class GeminiRepositoryImpl(
    firebaseAI: FirebaseAI,
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
        tools = listOf(
            // FIX 1: The Tool constructor is internal.
            // Use the Tool.functionDeclarations() factory method instead.
            Tool.functionDeclarations(listOf(createFileTool))
        )
    )

    override suspend fun generateASimpleResponse(prompt: String): String {
        try {
            val response = generativeModel.generateContent(prompt)
            val functionCall = response.functionCalls.firstOrNull()

            if (functionCall != null && functionCall.name == "create_file") {
                delay(500)
                val success = Random.nextBoolean()
                val result = if (success) {
                    val path = functionCall.args["path"] ?: "unknown file"
                    "Successfully created file at $path"
                } else {
                    "Failed to create file due to a random error."
                }

                val finalResponse = generativeModel.generateContent(
                    content {
                        // FIX 2: The part() function expects a Part, not Content.
                        // Use the spread operator (*) to unpack the parts from the previous content.
                        response.candidates.firstOrNull()?.content?.let {
                            parts.addAll(it.parts)
                        }

                        // FIX 3: The FunctionResponsePart now requires a
                        // kotlinx.serialization.json.JsonObject, not an org.json.JSONObject.
                        part(FunctionResponsePart(functionCall.name, buildJsonObject {
                            put("result", result)
                        }))
                    }
                )

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