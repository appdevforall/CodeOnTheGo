package com.itsaky.androidide.agent.viewmodel

import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.content
import com.itsaky.androidide.models.PlanStep
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class OrchestratorAgent(private val model: GenerativeModel) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun createPlan(userInput: String, toolDeclarations: String): List<PlanStep> {
        val prompt = content(role = "user") {
            text("User Request: \"$userInput\"")
            text("Tool Declarations: $toolDeclarations")
        }

        val response = model.generateContent(prompt)
        val planJson = response.text ?: return emptyList()

        return try {
            // Attempt to parse the generated JSON into a list of PlanStep objects
            json.decodeFromString(ListSerializer(PlanStep.serializer()), planJson)
        } catch (e: Exception) {
            println("Error parsing plan: ${e.message}")
            emptyList() // Return an empty list if parsing fails
        }
    }
}