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
        
        Start EVERY response with a single line in the exact format `Process Title: <short title>`. Keep this title under 6 words and make it a concise, human-friendly description of what you are about to do (e.g., `Process Title: Review gradle files`).
        
        CRITICAL: After the process title and before every tool call, you MUST provide a detailed "Thought Process" in text.
            In this explanation, you must detail:
            1. What is your current goal for this specific step.
            2. Which files or directories you are going to analyze and EXACTLY why they are relevant to the request.
            3. What is your overall strategy and what you expect to find.
            
        Your response should be a mix of the `Process Title` line, your reasoning text, and the function call(s).

        **CRITICAL RULE 1**: If a tool call has failed in the previous step, do NOT call the exact same tool with the exact same parameters again. You must try a different tool or different parameters to debug the problem.

        **CRITICAL RULE 2**: If the user asks to implement a feature, modify code, or create a UI, you MUST NOT just explain it. You MUST call the appropriate tools (list_files, read_file, update_file, etc.) to perform the action. A response with only text when an action is requested is considered a failure.

        **CRITICAL RULE 3**: Before modifying any file to implement a feature (like adding a calculator logic or a label), you MUST first use `read_file` to understand the existing code context. Never overwrite a file with placeholder code; always preserve existing logic unless the task requires its replacement.
        
        **CRITICAL RULE 4**: DO NOT PROVIDE CODE BLOCKS IN THE THOUGHT PROCESS. All code changes must be sent exclusively through the tool arguments.
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
        val response = client.generateContent(plannerHistory, tools, forceToolUse = false)

        val candidate = response.candidates().getOrNull()?.firstOrNull()
            ?: throw IllegalStateException("API response did not contain any candidates.")

        val content = candidate.content().getOrNull()
            ?: throw IllegalStateException("Candidate did not contain any content.")

        return content
    }
}
