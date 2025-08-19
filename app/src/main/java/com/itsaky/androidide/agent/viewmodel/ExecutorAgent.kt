package com.itsaky.androidide.agent.viewmodel

import com.google.firebase.ai.type.FunctionCallPart
import com.itsaky.androidide.agent.repository.GeminiRepositoryImpl
import com.itsaky.androidide.models.PlanStep
import com.itsaky.androidide.models.StepResult
import org.slf4j.LoggerFactory

class ExecutorAgent(private val repository: GeminiRepositoryImpl) {

    companion object {
        private val log = LoggerFactory.getLogger(ExecutorAgent::class.java)
    }

    suspend fun executeStep(step: PlanStep): StepResult {
        log.debug("Executing Step ${step.stepId}: ${step.objective} using tool ${step.toolToUse}")
        val functionCall = FunctionCallPart(
            name = step.toolToUse,
            args = step.parameters
        )

        val toolResult = repository.executeTool(functionCall)
        log.debug("toolResult: {}", toolResult)
        return StepResult(
            stepId = step.stepId,
            wasSuccessful = toolResult.success,
            output = toolResult.message + "\n" + toolResult.data,
            error = if (!toolResult.success) toolResult.message + "\n" + toolResult.error_details else null
        )
    }
}