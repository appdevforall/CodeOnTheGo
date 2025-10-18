//package com.itsaky.androidide.agent.viewmodel
//
//import com.itsaky.androidide.agent.data.ToolCall
//import com.itsaky.androidide.models.PlanStep
//import com.itsaky.androidide.models.StepResult
//import org.slf4j.LoggerFactory
//
//class ExecutorAgent(private val repository: GeminiRepositoryImpl) {
//
//    companion object {
//        private val log = LoggerFactory.getLogger(ExecutorAgent::class.java)
//    }
//
//    suspend fun executeStep(step: PlanStep): StepResult {
//        log.debug("Executing Step ${step.stepId}: ${step.objective} using tool ${step.toolToUse}")
//        val toolCall = ToolCall(
//            name = step.toolToUse,
//            args = step.parameters
//        )
//
//        val toolResult = repository.executeTool(toolCall)
//        log.debug("toolResult: {}", toolResult)
//
//        val outputMessage = listOfNotNull(toolResult.message, toolResult.data).joinToString("\n")
//        val errorMessage = if (!toolResult.success) {
//            listOfNotNull(toolResult.message, toolResult.error_details).joinToString("\n")
//        } else {
//            null
//        }
//
//        return StepResult(
//            stepId = step.stepId,
//            wasSuccessful = toolResult.success,
//            output = outputMessage,
//            error = errorMessage
//        )
//    }
//}