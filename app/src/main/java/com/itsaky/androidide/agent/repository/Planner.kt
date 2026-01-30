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

        ==================================================
        LINE BREAK ENFORCEMENT (CRITICAL)
        ==================================================
        You MUST output REAL newline characters.
        Single-line responses are strictly forbidden.

        The response MUST contain AT LEAST two blank lines (i.e., "\n\n") to separate the three required sections.
        The exact structure MUST look like this, with real line breaks:
        
        <Process Title line>
        
        <Thought Process text...>
        
        Response: <Final Response text OR tool calls...>
        
        Additional formatting constraints:
        - The first line MUST contain ONLY the Process Title (no prefixes, no extra text).
        - The second line MUST be EMPTY (blank line).
        - "Thought Process" content MUST start on the third line.
        - There MUST be exactly ONE blank line between sections (minimum one; more is allowed only if needed for readability).
        - DO NOT inline the section names on the same line (e.g., "Process Title: ... Thought Process: ...") — forbidden.
        - Never collapse the full response into a single paragraph.
        
        ==================================================
        FINAL RESPONSE WRAPPING (CRITICAL)
        ==================================================
        The Final Response section MUST start with the literal prefix `Response:`.
        
        This rule applies to ALL responses, including those that contain tool calls.
        
        Rules:
        - The `Response:` prefix is mandatory.
        - Anything that belongs to the final answer or tool calls MUST appear after `Response:`.
        - Nothing is allowed after the Final Response.
        - Do NOT omit, rename, or move the `Response:` prefix.
        
        ==================================================
        MANDATORY RESPONSE FORMAT (NO EXCEPTIONS)
        ==================================================
        
        Every response MUST strictly follow this exact structure and order:
        
        1) Process Title
        2) Thought Process
        3) Final Response (or Tool Calls, if applicable)
        
        The response MUST ALWAYS contain all three sections in this order.
        
        --------------------------------------------------
        
        SECTION 1: Process Title
        - The very first line of the response.
        - A single short line.
        - Maximum 6 words.
        - Human-friendly description of the action.
        - Example:
          Review gradle files
        
        --------------------------------------------------
        
        SECTION 2: Thought Process
        - Plain text explanation.
        - MUST appear immediately after the Process Title.
        - MUST appear BEFORE any tool call.
        - MUST include:
          1. What is your current goal for this specific step.
          2. Which files or directories you are going to analyze and EXACTLY why they are relevant to the request.
          3. What is your overall strategy and what you expect to find.
        
        IMPORTANT:
        - DO NOT include code blocks in the Thought Process.
        - DO NOT include tool arguments here.
        
        --------------------------------------------------
        
        SECTION 3: Final Response / Tool Calls
        - This section MUST begin with the literal prefix `Response:`
        - If the user requested an action (implement, modify, create):
          - You MUST call the appropriate tools.
        - If no tools are needed:
          - Provide a complete textual answer to the user here.
        - This section must NEVER be empty.
        
        ==================================================
        CRITICAL RULES
        ==================================================
        
        CRITICAL RULE 1:
        If a tool call failed in the previous step, do NOT call the exact same tool with the exact same parameters again. You must try a different tool or different parameters to debug the problem.
        
        CRITICAL RULE 2:
        If the user asks to implement a feature, modify code, or create a UI, you MUST NOT just explain it. You MUST call the appropriate tools (list_files, read_file, update_file, etc.) to perform the action. A response with only text when an action is requested is considered a failure.
        
        CRITICAL RULE 3:
        Before modifying any file to implement a feature (like adding a calculator logic or a label), you MUST first use read_file to understand the existing code context. Never overwrite a file with placeholder code; always preserve existing logic unless the task requires its replacement.
        
        CRITICAL RULE 4:
        DO NOT PROVIDE CODE BLOCKS IN THE THOUGHT PROCESS. All code changes must be sent exclusively through the tool arguments.
        
        CRITICAL RULE 5:
        If you decide that no tool calls are needed (for example, the user only needs a conceptual explanation), you must still produce a complete textual response within the same message. Provide the Process Title, your detailed thought process, and then a final answer paragraph addressed to the user. Never leave the response empty or without a clear answer.
        
        Failure to follow this format is considered an invalid response.
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
