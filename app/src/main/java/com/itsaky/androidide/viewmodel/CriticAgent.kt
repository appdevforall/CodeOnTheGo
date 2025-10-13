package com.itsaky.androidide.viewmodel

//class CriticAgent(private val model: GenerativeModel) {
//    suspend fun evaluateResult(result: StepResult, originalStep: PlanStep): Boolean {
//        // Limit the length of the tool output to avoid overly long prompts
//        val truncatedOutput = if (result.output.length > 1500) {
//            result.output.substring(0, 1500) + "... (truncated)"
//        } else {
//            result.output
//        }
//
//        // A more sophisticated prompt that asks the Critic to reason about the tool choice
//        val prompt = content(role = "user") {
//            text(
//                """
//                You are a senior software developer acting as a quality assurance agent.
//                Your task is to evaluate if a tool's output logically contributes to achieving the step's objective.
//
//                **Step's Objective:**
//                "${originalStep.objective}"
//
//                **Tool Chosen to Accomplish Objective:**
//                `${originalStep.toolToUse}`
//
//                **Actual Output from the Tool:**
//                ```
//                ${truncatedOutput}
//                ```
//
//                **Reasoning Task:**
//                1.  First, assess if using the tool `${originalStep.toolToUse}` is a logical and reasonable action to take to make progress on the objective "${originalStep.objective}".
//                2.  Second, assess if the "Actual Tool Output" indicates that the tool ran successfully. A message like "File created successfully" or "File updated successfully" means the tool itself worked.
//                3.  **Conclusion:** If the tool choice was logical (Task 1) AND the tool executed successfully (Task 2), then the step should be considered a success.
//
//                **Final Answer:**
//                Based on your reasoning, respond with only "true" if the step was successful, or "false" if it was not.
//                """.trimIndent()
//            )
//        }
//
//
//        val response = model.generateContent(prompt)
//        return response.text?.trim()?.toBoolean() ?: false
//    }
//}