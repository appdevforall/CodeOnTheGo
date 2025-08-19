package com.itsaky.androidide.viewmodel

import com.google.firebase.ai.GenerativeModel
import com.itsaky.androidide.models.PlanStep
import com.itsaky.androidide.models.StepResult

class CriticAgent(private val model: GenerativeModel) {
    suspend fun evaluateResult(result: StepResult, originalStep: PlanStep): Boolean {
        // Limit the length of the tool output to avoid overly long prompts
        val truncatedOutput = if (result.output.length > 2000) {
            result.output.substring(0, 2000) + "... (truncated)"
        } else {
            result.output
        }

        // A more sophisticated prompt that asks the Critic to reason about the result's utility.
        val prompt = """
            You are a senior software developer acting as a quality assurance agent.
            Your task is to evaluate if a tool's output is useful and sufficient for the stated objective.

            **Step's Objective:**
            "${originalStep.objective}"

            **Tool Used:**
            `${originalStep.toolToUse}`

            **Actual Tool Output (may be truncated):**
            ```
            $truncatedOutput
            ```

            **Reasoning Task:**
            1. Analyze the **Step's Objective**. What information was the agent trying to obtain?
            2. Analyze the **Actual Tool Output**. Does this output contain the information needed to achieve the objective?
            2.5. **Special Rule for `run_app`:** The agent is blind and cannot see the screen. If the tool is `run_app` and the objective is to launch the app for user verification, an output like "App built and launched successfully" is a complete success for this step.
            3. Conclude your evaluation. Is the output **sufficient and relevant** to the objective, allowing the overall plan to proceed? A generic success message like "Files listed successfully" is only a success if the data provided alongside it is useful.


            **Final Answer:**
            Based on your reasoning, respond with only "true" if the tool output is sufficient, or "false" if it is not.
        """.trimIndent()

        val response = model.generateContent(prompt)
        return response.text?.trim()?.toBoolean() ?: false
    }
}