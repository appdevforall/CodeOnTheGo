package com.itsaky.androidide.viewmodel

import com.google.firebase.ai.GenerativeModel
import com.itsaky.androidide.models.PlanStep
import com.itsaky.androidide.models.StepResult

class CriticAgent(private val model: GenerativeModel) {
    suspend fun evaluateResult(result: StepResult, originalStep: PlanStep): Boolean {
        // The Critic compares the actual output to the objective and expected output.
        val prompt = """
            Critique the following tool execution result based on its immediate objective.
            
            **CONTEXT:** A step in a larger plan was executed. Your task is to determine if THIS SPECIFIC STEP was successful, not the entire plan.
            
            **Step's Objective:** "${originalStep.objective}"
            **Tool Used:** `${originalStep.toolToUse}`
            **Expected Output Format:** "${originalStep.expectedOutputFormat}"
            **Actual Tool Output:** "${result.output}"
            
            **Question:** Did the tool's actual output successfully satisfy the step's specific objective? A simple success message from the tool (like "File updated successfully") is a valid success if the objective was to use that tool.
            
            Respond with only "true" or "false".
        """.trimIndent()

        val response = model.generateContent(prompt)
        return response.text?.trim()?.toBoolean() ?: false
    }
}