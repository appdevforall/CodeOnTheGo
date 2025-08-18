package com.itsaky.androidide.agent.viewmodel

import com.google.firebase.ai.type.FunctionCallPart
import com.itsaky.androidide.agent.repository.GeminiRepositoryImpl
import com.itsaky.androidide.models.PlanStep
import com.itsaky.androidide.models.StepResult
import kotlinx.serialization.json.JsonPrimitive

class ExecutorAgent(private val repository: GeminiRepositoryImpl) {
    // The Executor's job is to run a single step using the repository's tool execution logic.
    suspend fun executeStep(step: PlanStep): StepResult {
        println("Executing Step ${step.stepId}: ${step.objective} using tool ${step.toolToUse}")
        // Create a fake FunctionCallPart to pass to the existing executeTool method
        val functionCall = FunctionCallPart(
            name = step.toolToUse,
            args = step.parameters.mapValues { JsonPrimitive(it.value.toString()) }
        )

        // Delegate the actual tool execution to the repository
        val toolResult = repository.executeTool(functionCall)

        return StepResult(
            stepId = step.stepId,
            wasSuccessful = toolResult.success,
            output = toolResult.message,
            error = if (!toolResult.success) toolResult.message else null
        )
    }
}