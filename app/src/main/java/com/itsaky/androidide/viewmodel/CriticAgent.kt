package com.itsaky.androidide.viewmodel

import com.google.firebase.ai.GenerativeModel
import com.itsaky.androidide.models.PlanStep
import com.itsaky.androidide.models.StepResult

class CriticAgent(private val model: GenerativeModel) {
    suspend fun evaluateResult(result: StepResult, originalStep: PlanStep): Boolean {
        // The Critic compares the actual output to the objective and expected output.
        val prompt = """
            Critique the following tool execution result.
            Original Objective: "${originalStep.objective}"
            Expected Output Format: "${originalStep.expectedOutputFormat}"
            Actual Execution Result: "${result.output}"
            Was the original objective successfully met based on the execution result?
            Respond with only "true" or "false".
        """.trimIndent()

        val response = model.generateContent(prompt)
        return response.text?.trim()?.toBoolean() ?: false
    }
}