package com.itsaky.androidide.agent.repository

// ✨ 1. Import necessary coroutine classes
import android.content.Context
import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.Tool
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull


class AgenticRunner(
    private val context: Context,
    private val maxSteps: Int = 20
) : GeminiRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
        )
    )
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val runnerJob = Job()
    private val runnerScope = CoroutineScope(Dispatchers.IO + runnerJob)

    private val plannerClient: GeminiClient by lazy {
        // Fetch the key when the client is first needed
        val apiKey = EncryptedPrefs.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            val errorMessage = "Gemini API Key not found. Please set it in the AI Settings."
            log.error(errorMessage)
            // Throw an exception that we can catch in the ViewModel
            throw IllegalStateException(errorMessage)
        }
        GeminiClient(apiKey, "gemini-2.5-pro") // Use a stable model version
    }

    private val criticClient: GeminiClient by lazy {
        val apiKey = EncryptedPrefs.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            val errorMessage = "Gemini API Key not found. Please set it in the AI Settings."
            log.error(errorMessage)
            throw IllegalStateException(errorMessage)
        }
        GeminiClient(apiKey, "gemini-2.5-flash")
    }

    private var executor: Executor = Executor()


    override var onStateUpdate: ((AgentState) -> Unit)? = null


    private val toolTracker = ToolExecutionTracker()

    /**
     * Immediately cancels the runner's CoroutineScope.
     * This will interrupt any ongoing network calls (plan, critique) and
     * cause the run loop to terminate with a CancellationException.
     */
    override fun stop() {
        log.info("Stop requested for AgenticRunner. Cancelling job.")
        runnerJob.cancel("User requested to stop the agent.")
    }

    override fun getPartialReport(): String {
        return toolTracker.generatePartialReport()
    }

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
        TODO("Not yet implemented")
    }

    private val planner: Planner
    private val critic: Critic
    private val tools: List<Tool>
    private val globalPolicy: String
    private val globalStaticExamples: List<Map<String, Any>>

    private var logTs: String = ""
    private var logEntries = mutableListOf<JsonObject>()

    companion object {
        private val log = LoggerFactory.getLogger(AgenticRunner::class.java)

        private val json = Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    init {
        this.tools = allAgentTools
        this.planner = Planner(plannerClient, this.tools)
        this.critic = Critic(criticClient)
        this.globalPolicy = getPolicyConfig()
        this.globalStaticExamples = getFewShotsConfig()
    }

    private fun buildSystemPrompt(
    ): String {
        val currentTime = LocalDateTime.now()
        val formattedTime =
            "${currentTime.dayOfWeek}, ${currentTime.toLocalDate()} ${currentTime.hour}:${
                currentTime.minute.toString().padStart(2, '0')
            }"


        var header = """
            You are an expert Android developer agent specializing in both Views and Jetpack Compose. Your goal is to fulfill user requests by interacting with an IDE.
            Follow policies strictly.

            [Session Information]
            - Current Date and Time: $formattedTime

        """.trimIndent()

        val globalRulesText = globalPolicy
        header += "[Global Rules]\n$globalRulesText\n"

        header += "\n[Tooling]\nUse tools when they reduce uncertainty or are required by policy.\n"


        return header
    }

    private fun createAugmentedPrompt(
        prompt: String,
        header: String,
        examples: List<Map<String, Any>>,
        formattedHistory: String
    ): String {
        val formattedExamples = StringBuilder()
        examples.forEachIndexed { i, dialogueDict ->
            formattedExamples.append("--- Example ${i + 1} ---\n")
            val listOfTurns = dialogueDict["dialogue"] as? List<Map<String, String>> ?: emptyList()
            listOfTurns.forEach { turn ->
                when (turn["role"]) {
                    "user" -> formattedExamples.append("User: ${turn["text"]}\n")
                    "assistant" -> formattedExamples.append("Assistant: ${turn["text"]}\n")
                }
            }
            formattedExamples.append("--- End Example ---\n\n")
        }

        val finalInstruction =
            "Based on the user's request, you MUST respond by calling one or more of the available tools. Do not provide a conversational answer."

        return "$header$formattedExamples$formattedHistory" +
                "$finalInstruction\n\nUser Request: $prompt"
    }

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        // 1. Load the history from the current session
        loadHistory(history)

        // 2. Add the user's new message to the flow so it appears instantly
        addMessage(prompt, Sender.USER)
        val finalMessage = try {
            runnerScope.async {
                run(prompt, history)
            }.await()
        } catch (e: CancellationException) {
            log.warn("generateASimpleResponse caught cancellation.")
            updateLastMessage("Operation cancelled by user.")
            "Operation cancelled by user."
        }

        onStateUpdate?.invoke(AgentState.Idle)
        return AgentResponse(text = finalMessage, report = toolTracker.generatePartialReport())
    }

    private suspend fun run(
        prompt: String,
        chatHistory: List<ChatMessage>? = null,
    ): String {
        startLog()
        // The prompt is now the last message in our state
        val prompt = _messages.value.lastOrNull { it.sender == Sender.USER }?.text ?: ""
        log.debug(prompt)

        // Add a placeholder message for the agent's response
        addMessage("", Sender.AGENT)

        onStateUpdate?.invoke(AgentState.Processing("Initializing Agent Workflow..."))
        val currentMessages = _messages.value.dropLast(1)
        val formattedHistory = chatHistory?.takeIf { it.isNotEmpty() }?.let {
            val historyLog = it.joinToString("\n") { msg ->
                "${msg.sender}: ${msg.text}"
            }
            "\n[START OF PREVIOUS CONVERSATION]\n$historyLog\n[END OF PREVIOUS CONVERSATION]\n\n"
        } ?: ""


        val header = buildSystemPrompt()

        val augmentedPrompt =
            createAugmentedPrompt(prompt, header, globalStaticExamples, formattedHistory)

        val history = mutableListOf(
            Content.builder().role("user").parts(Part.builder().text(augmentedPrompt).build())
                .build()
        )
        logTurn("user", history.last().parts().get())

        try {
            for (step in 0 until maxSteps) {
                runnerScope.ensureActive()

                val message = "Orchestrator: Step ${step + 1}..."
                log.info(message)
                onStateUpdate?.invoke(AgentState.Processing(message))
                updateLastMessage("Planning...")
                val plan = planner.plan(history)
                if (plan.parts().getOrNull().isNullOrEmpty()) {

                    log.warn("Planner returned an empty response, possibly due to safety filters. Halting execution.")
                    val finalText =
                        "I am unable to process this request. Please rephrase your prompt or check the content for any potential policy violations."
                    updateLastMessage(finalText)
                    logFinalText(finalText)
                    onStateUpdate?.invoke(AgentState.Idle)
                    return finalText
                }

                history.add(plan)
                logTurn("model", plan.parts().get())

                val functionCalls = plan.parts().get().mapNotNull { it.functionCall().getOrNull() }

                if (functionCalls.isEmpty()) {
                    log.info("Orchestrator: Plan is a final answer. Run complete.")
                    val finalText = plan.parts().get().first().text().getOrNull()?.trim() ?: ""
                    logFinalText(finalText)
                    updateLastMessage(finalText)
                    return finalText
                }

                // Give feedback about the tool call
                val toolCallSummary =
                    functionCalls.joinToString("\n") { "Calling tool: `${it.name().get()}`" }
                updateLastMessage(toolCallSummary)

                val toolResultsParts = executor.execute(functionCalls)
                history.add(Content.builder().role("tool").parts(toolResultsParts).build())
                logTurn("tool", toolResultsParts)

                val lastToolName = functionCalls.lastOrNull()?.name()?.getOrNull()
                if (lastToolName == "run_app") {
                    val runResultJson =
                        toolResultsParts.first().functionResponse().get().response().get()
                    val successMessage = runResultJson["message"] as? String ?: ""

                    if (successMessage.contains("App built and launched successfully")) {
                        log.info("Orchestrator: App run was successful. Concluding workflow.")
                        val finalText = "The application was successfully built and launched."
                        logFinalText(finalText)
                        return finalText
                    }
                }

                val critiqueResult = critic.reviewAndSummarize(history)

                if (critiqueResult != "OK") {
                    history.add(
                        Content.builder().role("user")
                            .parts(Part.builder().text(critiqueResult).build()).build()
                    )
                    logTurn("system_critic", history.last().parts().get())
                }
            }
            throw RuntimeException("Agentic run exceeded max_steps ($maxSteps)")
        } catch (err: Exception) {
            if (err is CancellationException) {
                log.warn("Agentic run was cancelled during execution.")
                return "Operation cancelled by user."
            }
            log.error("Agentic run failed", err)
            updateLastMessage("An error occurred: ${err.message}")
            return "Agentic run failed: ${err.message}"
        } finally {
            writeLog()
        }
    }

    private fun startLog() {
        val now = LocalDateTime.now()
        logTs = now.toString() // A simplified timestamp for the filename
        logEntries.clear()
    }

    private fun logTurn(turn: String, parts: List<Part>) {
        // We will now store JsonObject instead of Map<String, Any>
        val logEntry = buildJsonObject {
            put("step", logEntries.size + 1)
            put("turn", turn)
            putJsonArray("content") {
                parts.forEach { part ->
                    add(serializePartToJsonObject(part))
                }
            }
        }
        // Add the JsonObject to your logEntries list
        logEntries.add(logEntry)
    }

    private fun serializePartToJsonObject(part: Part): JsonObject {
        part.functionCall().getOrNull()?.let { fc ->
            return buildJsonObject {
                put("type", "function_call")
                put("name", fc.name().get())
                putJsonObject("args") {
                    fc.args().get().forEach { (key, value) ->
                        put(key, value.toString())
                    }
                }
            }
        }
        part.functionResponse().getOrNull()?.let { fr ->
            return buildJsonObject {
                put("type", "function_response")
                put("name", fr.name().get())
                put("response", fr.response().toString())
            }
        }
        part.text().getOrNull()?.let { text ->
            return buildJsonObject {
                put("type", "text")
                put("content", text)
            }
        }
        return buildJsonObject {
            put("type", "unknown")
            put("content", part.toString())
        }
    }

    private fun logFinalText(text: String) {
        val logEntry = buildJsonObject {
            put("step", logEntries.size + 1)
            put("turn", "system")
            put("final_text", text)
        }
        logEntries.add(logEntry)
    }

    private fun writeLog() {
        try {
            // Now that logEntries is a list of JsonObject, this will work perfectly.
            val logContent =
                json.encodeToString(ListSerializer(JsonObject.serializer()), logEntries)

            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val logFile = File(logDir, "gemini_run_${logTs}.interaction.json")
            logFile.writeText(logContent, Charsets.UTF_8)
            log.info("Full agent interaction logged to ${logFile.absolutePath}")

        } catch (e: Exception) {
            log.error("Failed to write interaction log.", e)
        }
    }


    private fun getPolicyConfig(): String {
        return try {
            context.assets.open("agent/policy.yml")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            println("Error reading few-shots text file: ${e.message}")
            "" // Return an empty string if the file can't be read
        }
    }

    private fun getFewShotsConfig(): List<Map<String, Any>> {
        try {
            val jsonString = context.assets.open("agent/planner_fewshots.json")
                .bufferedReader()
                .use { it.readText() }

            val jsonObjects =
                json.decodeFromString(ListSerializer(JsonObject.serializer()), jsonString)

            return jsonObjects.map { it.toStandardMap() }

        } catch (e: Exception) {
            println("Error parsing few-shots JSON: ${e.message}")
            return emptyList()
        }
    }

    fun loadHistory(history: List<ChatMessage>) {
        _messages.value = history
    }

    private fun addMessage(text: String, sender: Sender) {
        val message = ChatMessage(text = text, sender = sender)
        _messages.update { currentList -> currentList + message }
    }

    private fun updateLastMessage(newText: String) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            val updatedMessage = lastMessage.copy(text = newText)
            currentList.dropLast(1) + updatedMessage
        }
    }

    private fun appendToLastMessage(chunk: String) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            val updatedMessage = lastMessage.copy(text = lastMessage.text + chunk)
            currentList.dropLast(1) + updatedMessage
        }
    }
}

/**
 * Extension function to safely convert a JsonObject to a standard Map<String, Any>.
 */
private fun JsonObject.toStandardMap(): Map<String, Any> {
    return this.mapValues { (_, jsonElement) ->
        jsonElement.toAny()
    }
}

/**
 * Recursive helper function to convert any JsonElement into a standard Kotlin type.
 */
private fun JsonElement.toAny(): Any {
    return when (this) {
        is JsonObject -> this.toStandardMap()
        is JsonArray -> this.map { it.toAny() }
        is JsonPrimitive -> {
            if (this.isString) {
                this.content
            } else {
                // Handles numbers, booleans, and nulls
                this.contentOrNull ?: this.booleanOrNull ?: this.longOrNull ?: this.doubleOrNull
                ?: Unit
            }
        }
    }
}