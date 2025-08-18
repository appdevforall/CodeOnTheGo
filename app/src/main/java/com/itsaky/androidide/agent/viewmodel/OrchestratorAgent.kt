package com.itsaky.androidide.agent.viewmodel

import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.content
import com.itsaky.androidide.models.PlanStep
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class OrchestratorAgent(private val model: GenerativeModel) {

    companion object {
        private val log = LoggerFactory.getLogger(OrchestratorAgent::class.java)
    }
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
        log.debug(response.text)
        val planJson = response.text ?: return emptyList()

        return try {
            json.decodeFromString(ListSerializer(PlanStep.serializer()), planJson)
        } catch (e: Exception) {
            log.error("Error parsing plan: ${e.message}")
            emptyList()
        }
    }
}