package com.itsaky.androidide.agent.repository

import com.google.genai.types.Content
import com.google.genai.types.Part
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

/*
*/
/**
 * A data class representing a single turn in a conversation, for creating training exemplars.
 *//*
@Serializable
data class DialogueTurn(
    val role: String,
    val text: String? = null,
    val name: String? = null,
    val args: Map<String, Any?>? = null,
    val result: Map<String, Any?>? = null
)

*/
/**
 * A data class representing a complete training lesson derived from a conversation.
 *//*
@Serializable
data class Lesson(
    val pattern: String,
    val correction: String,
    @SerialName("exemplar_dialogue")
    val exemplarDialogue: List<DialogueTurn>
)*/


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
    fun reviewAndSummarize(history: List<Content>): String { // CHANGED: Return type is now non-nullable String
        log.info("Critic: Reviewing and summarizing the last step...")

        if (history.size < 2 || history.last().role().getOrNull() != "tool") {
            return "OK" // CHANGED from null
        }

        val lastToolPart = history.last().parts().getOrNull()?.first()
        val toolResponseData = lastToolPart?.functionResponse()?.getOrNull()?.response()
        val toolResponseString = toolResponseData.toString()

        if (toolResponseString.length < 500) {
            val hasError = listOf("error", "failed", "not found", "404")
                .any { it in toolResponseString.lowercase() }

            if (!hasError) {
                log.info("Critic: Tool output is small and successful. Skipping LLM review.")
                return "OK" // CHANGED from null
            }
        }

        // --- If output is large or has an error, ask the LLM to process it ---
        val criticHistory = mutableListOf(
            Content.builder().role("user").parts(Part.builder().text(SUMMARIZER_PROMPT).build())
                .build(),
            Content.builder().role("model").parts(
                Part.builder().text("Understood. Provide the history for me to process.").build()
            ).build()
        )

        // NEW: Frame the last tool output clearly to avoid confusion.
        // This tells the model "the following is data, not an instruction".
        val lastTurn = history.last()
        val framedToolOutput = Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .text("Please process the following tool output:\n```\n$lastTurn\n```").build()
            )
            .build()

        // Add the framed output to the history for the Critic model.
        criticHistory.add(framedToolOutput)

        // The Critic only needs to see the tool output, not the entire history that led to it.
        // We already framed it above.
        val response = try {
            client.generateContent(criticHistory, tools = emptyList())
        } catch (e: PlannerToolCallException) {
            log.warn("Critic model attempted an invalid tool call: {}", e.message)
            return "OK"
        }
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

    /*    */
    /**
     * Creates a lesson from a successful question-answering interaction.
     *//*
    private fun createQaLesson(history: List<Content>): Lesson? {
        val userMsg = history.firstOrNull { it.role().getOrNull() == "user" }?.parts()?.getOrNull()?.firstOrNull()?.text()
        val finalAnswer = if (history.last().role().getOrNull() == "model") history.last().parts().getOrNull()?.firstOrNull()?.text else null

        if (userMsg.isNullOrBlank() || finalAnswer.isNullOrBlank()) {
            return null
        }

        return Lesson(
            pattern = "Successful question-answering pair",
            correction = "When asked a similar question, provide a direct answer synthesized from the tool results.",
            exemplarDialogue = formatHistoryForExemplar(history)
        )
    }

    */
    /**
     * Converts Content history to a list of [DialogueTurn] objects for JSON serialization.
     *//*
    private fun formatHistoryForExemplar(history: List<Content>): List<DialogueTurn> {
        return history.mapNotNull { content ->
            val part = content.parts.firstOrNull() ?: return@mapNotNull null
            when (content.role) {
                "user" -> DialogueTurn(role = "user", text = part.text)
                "model" -> {
                    when {
                        part.text != null -> DialogueTurn(role = "assistant", text = part.text)
                        part.functionCall != null -> DialogueTurn(
                            role = "tool_call",
                            name = part.functionCall.name,
                            args = structToMap(part.functionCall.args)
                        )
                        else -> null
                    }
                }
                "tool" -> DialogueTurn(
                    role = "tool_result",
                    name = part.functionResponse.name,
                    result = structToMap(part.functionResponse.response)
                )
                else -> null
            }
        }
    }

    */
    /**
     * Recursively converts a GenAI [Struct] to a Kotlin [Map].
     *//*
    private fun structToMap(struct: Struct): Map<String, Any?> {
        return struct.fieldsMap.mapValues { (_, value) -> valueToAny(value) }
    }

    */
    /**
     * Recursively converts a GenAI [Value] to a standard Kotlin type.
     *//*
    private fun valueToAny(value: Value): Any? {
        return when (value.kindCase) {
            Value.KindCase.STRING_VALUE -> value.stringValue
            Value.KindCase.NUMBER_VALUE -> value.numberValue
            Value.KindCase.BOOL_VALUE -> value.boolValue
            Value.KindCase.STRUCT_VALUE -> structToMap(value.structValue)
            Value.KindCase.LIST_VALUE -> value.listValue.valuesList.map { valueToAny(it) }
            Value.KindCase.NULL_VALUE -> null
            else -> null
        }
    }*/
}
