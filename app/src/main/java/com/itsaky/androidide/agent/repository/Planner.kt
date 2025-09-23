package com.itsaky.androidide.agent.repository

import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.Tool
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

/**
 * The "thinker" of the agent. It uses the Gemini model to decide the next steps.
 * @param client An instance of [GeminiClient] used to make LLM calls.
 * @param tools The list of available tools the planner can choose from.
 */
class Planner(
    private val client: GeminiClient,
    private val tools: List<Tool>
) {

    companion object {
        private val log = LoggerFactory.getLogger(Planner::class.java)

        private const val SYSTEM_PROMPT = """
        You are an expert AI developer agent. Your sole purpose is to analyze the user's request and the conversation history, then select the most appropriate tool and parameters to call next. 
        You MUST respond with only a valid JSON object for a tool call. Do not provide any conversational text, explanations, or markdown formatting.

        **CRITICAL RULE**: If a tool call has failed in the previous step, do NOT call the exact same tool with the exact same parameters again. You must try a different tool or different parameters to debug the problem.
    """
    }

    /**
     * Given the conversation history, generate the next step (the plan).
     * The plan is the raw Content object returned by the model, typically containing a function call.
     *
     * @param history The full conversation history.
     * @return The model's response as a [Content] object.
     */
    fun plan(history: List<Content>): Content {
        log.info("Planner: Devising a plan...")

        // NEW: Create a new history list that starts with our specific instruction.
        val plannerHistory = mutableListOf<Content>()

        // NEW: Add the system prompt as the very first message.
        val systemInstruction = Content.builder()
            .role("user") // System instructions are often sent with the "user" role.
            .parts(Part.builder().text(SYSTEM_PROMPT).build())
            .build()
        plannerHistory.add(systemInstruction)

        // NEW: Add the rest of the actual conversation history after our instruction.
        plannerHistory.addAll(history)

        // Pass the new, augmented history to the client.
        val response = client.generateContent(plannerHistory, tools, forceToolUse = true)

        val candidate = response.candidates().getOrNull()?.firstOrNull()
            ?: throw IllegalStateException("API response did not contain any candidates.")

        val content = candidate.content().getOrNull()
            ?: throw IllegalStateException("Candidate did not contain any content.")

        return content
    }
}