package com.itsaky.androidide.agent.repository

import com.google.genai.types.Content
import com.google.genai.types.Part
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull


/**
 * Reviews and refines conversation steps, providing feedback or summarizing large tool outputs.
 * @param client An instance of [GeminiClient] used to make LLM calls.
 */
class Critic(private val client: GeminiClient) {

    companion object {
        private val log = LoggerFactory.getLogger(Critic::class.java)

        private const val SUMMARIZER_PROMPT = """
        You are an expert AI assistant that reviews and refines conversation steps. Your task is to process the last tool output in the provided history. Follow these rules in order:

        1.  **CHECK FOR ERRORS**: If the tool output contains an obvious error (e.g., "Not Found", "404", "failed", "exception"), your response should be a brief description of the error for the agent to correct.

        2.  **SUMMARIZE LARGE OUTPUTS**: If the tool output is a large JSON object or long text (more than 15 lines), your primary goal is to **summarize it into a single, concise, natural-language sentence**. Capture only the most critical information the agent needs for its next step.
            - **Example Input (Large PR JSON)**: `{"url": "...", "id": 123, "number": 21, "title": "IG-152 | CrossFade mixer...", "user": {"login": "jatezzz"}, "state": "open", "additions": 151, ...}`
            - **Your Required Output**: `Successfully fetched PR #21, titled "IG-152 | CrossFade mixer feature for music player". It is open and authored by 'jatezzz'.`

        3.  **APPROVE SMALL OUTPUTS**: If the tool output was successful and is short and simple (e.g., `{"status": "reminder created", "id": "123"}`), respond with only the word "OK".

        Your response will be fed directly back to the agent. Do not add conversational fluff.
        """
    }

    /**
     * Reviews the latest tool call result in the history.
     *
     * It provides corrective feedback for errors, summarizes large outputs,
     * or returns null if the step was small and successful.
     *
     * @param history The full conversation history.
     * @return A formatted string with feedback/summary, or null if no action is needed.
     */
    fun reviewAndSummarize(history: List<Content>): String {
        log.info("Critic: Reviewing and summarizing the last step...")

        if (history.size < 2 || history.last().role().getOrNull() != "tool") {
            return "OK"
        }

        val lastToolPart = history.last().parts().getOrNull()?.first()
        val toolResponseData = lastToolPart?.functionResponse()?.getOrNull()?.response()
        val toolResponseString = toolResponseData.toString()

        if (toolResponseString.length < 500) {
            val hasError = listOf("error", "failed", "not found", "404")
                .any { it in toolResponseString.lowercase() }

            if (!hasError) {
                log.info("Critic: Tool output is small and successful. Skipping LLM review.")
                return "OK"
            }
        }

        val criticHistory = mutableListOf(
            Content.builder().role("user").parts(Part.builder().text(SUMMARIZER_PROMPT).build())
                .build(),
            Content.builder().role("model").parts(
                Part.builder().text("Understood. Provide the history for me to process.").build()
            ).build()
        )

        val lastTurn = history.last()
        val framedToolOutput = Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .text("Please process the following tool output:\n```\n$lastTurn\n```").build()
            )
            .build()

        criticHistory.add(framedToolOutput)

        val response = client.generateContent(criticHistory, tools = emptyList())
        val summaryText = response.text()?.trim()

        if (summaryText.isNullOrBlank()) {
            log.warn("Critic/Summarizer model returned no valid content.")
            return "OK"
        }

        if (summaryText.equals("OK", ignoreCase = true)) {
            return "OK"
        }

        return "System Note: $summaryText"
    }

}