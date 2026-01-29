package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.types.Tool
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.optionals.getOrNull


class AgenticRunner(
    private val context: Context,
    private val maxSteps: Int = 20
) : GeminiRepository {

    private var currentAIAgentThought: String = ""
    private var currentProcessTitle: String = ""

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
        )
    )
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var runnerJob: Job = SupervisorJob()
    private var runnerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + runnerJob)
    private val runToken = AtomicLong(0L)
    private fun nextRunToken(): Long = runToken.incrementAndGet()
    private fun isTokenActive(token: Long): Boolean = runToken.get() == token

    private val plannerClient: GeminiClient by lazy {
        // Fetch the key when the client is first needed
        val apiKey = EncryptedPrefs.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            val errorMessage = context.getString(R.string.ai_api_key_not_found)
            log.error(errorMessage)
            // Throw an exception that we can catch in the ViewModel
            throw IllegalStateException(errorMessage)
        }
        GeminiClient(apiKey, "gemini-2.5-pro") // Use a stable model version
    }

    private val criticClient: GeminiClient by lazy {
        val apiKey = EncryptedPrefs.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            val errorMessage = context.getString(R.string.ai_api_key_not_found)
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
        val cancelledToken = nextRunToken()
        log.info("Stop requested for AgenticRunner. Cancelling job. token=$cancelledToken")

        updateLastMessageIfActive(cancelledToken, "Operation cancelled by user.")
        onStateUpdate?.invoke(AgentState.Idle)

        runnerScope.cancel("User requested to stop the agent.")
        runnerJob = SupervisorJob()
        runnerScope = CoroutineScope(Dispatchers.IO + runnerJob)
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

            [Autonomy & Permissions]
            1. **Read Access (Implicit):** You have full, permanent authorization to analyze the project directory, list files, and read contents. **DO NOT ask for permission to read files or explore directories**; do it immediately using the available tools if needed to understand the project or answer a question.
            2. **Write Access (Explicit):** You MUST ask for user confirmation ONLY when you are about to modify, create, or delete files.
            3. **Proactivity:** If a request is vague (e.g., "fix the bug in the main screen"), use your tools to explore the codebase and find the relevant files automatically instead of asking the user where they are.
        
        """.trimIndent()

        val globalRulesText = globalPolicy
        header += "[Global Rules]\n$globalRulesText\n"

        header += "\n[Tooling]\nUse tools when they reduce uncertainty or are required by policy. Assume you are already 'logged in' and 'authorized' for all read-only operations.\n"

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
            "Use tools only when they are necessary to fulfill the user's request. " +
                "If the request requires reading, creating, updating, or deleting files, " +
                "or any IDE action, you MUST call the appropriate tool(s). " +
                "If you already have enough information to answer without tools, answer directly. " +
                "Do not respond with a plan or suggested actions—either call tools or provide the final answer."

        return "$header$formattedExamples$formattedHistory" +
                "$finalInstruction\n\nUser Request: $prompt"
    }

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ) {
        // 1. Load the history from the current session
        loadHistory(history)

        // 2. Add the user's new message to the flow so it appears instantly
        addMessage(prompt, Sender.USER)

        val token = nextRunToken()

        val finalMessage = try {
            runnerScope.async {
                run(token)
            }.await()
        } catch (e: CancellationException) {
            log.warn("generateASimpleResponse caught cancellation. token=$token")
            updateLastMessageIfActive(token, "Operation cancelled by user.") // Update UI on cancellation
            "Operation cancelled by user."
        }

        if (isTokenActive(token)) {
            onStateUpdate?.invoke(AgentState.Idle)
        }
    }

    private suspend fun run(token: Long): String {
        if (!isTokenActive(token)) return "Operation cancelled by user."

        startLog()
        currentAIAgentThought = ""
        currentProcessTitle = ""
        addMessageIfActive(token, "...", Sender.AGENT)
        onStateUpdateIfActive(token, AgentState.Processing("Initializing..."))

        val initialContent = buildInitialContent()
        val history = mutableListOf(initialContent)

        try {
            for (step in 0 until maxSteps) {
                runnerScope.ensureActive()
                if (!isTokenActive(token)) throw CancellationException("Run token invalidated.")

                val stepNumber = step + 1
                updateProcessingState(token, stepNumber)

                val plan = processPlannerStep(token, history)
                updateProcessingState(token, stepNumber)
                val functionCalls = plan.parts().get().mapNotNull { it.functionCall().getOrNull() }

                if (functionCalls.isEmpty()) {
                    val finalText = plan.parts().get().first().text().getOrNull()?.trim() ?: ""
                    updateLastMessageIfActive(token, finalText)
                    logTurn("final_answer", listOf(Part.builder().text(finalText).build()))
                    return finalText
                }

                val toolResultsParts = processToolExecutionStep(token, functionCalls)
                history.add(Content.builder().role("tool").parts(toolResultsParts).build())
                logTurn("tool", toolResultsParts)

                val critiqueResult = processCriticStep(token, history)
                if (critiqueResult.trim().equals("OK", ignoreCase = true)) {
                    return generateFinalAnswer(history)
                }
            }
            throw RuntimeException("Exceeded max steps")
        } catch (err: Exception) {
            if (err is CancellationException) {
                log.warn("Agentic run was cancelled during execution. token=$token")
                return "Operation cancelled by user."
            }
            log.error("Agentic run failed", err)
            updateLastMessageIfActive(token, "An error occurred: ${err.message}")
            return "Agentic run failed: ${err.message}"
        } finally {
            writeLog()
        }
    }

    private fun updateProcessingState(token: Long, stepNumber: Int) {
        onStateUpdateIfActive(token, AgentState.Processing("Step $stepNumber: ${getCurrentThoughtTitle()}"))
		}

    private fun getCurrentThoughtTitle(): String {
        if (currentProcessTitle.isNotBlank()) {
            return currentProcessTitle
        }

        if (currentAIAgentThought.isBlank()) {
            return "Processing current request..."
        }

        val fallback = currentAIAgentThought
            .lineSequence()
            .map { extractTaskDescription(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        return fallback.ifBlank { "Processing current request..." }
    }

    private fun extractTaskDescription(line: String): String {
        var workingLine = line.trim()
        if (workingLine.isEmpty()) {
            return ""
        }

        // Remove bullet and numbering prefixes ("- ", "1. ", "2)" etc.)
        val bulletPrefixes = listOf("- ", "* ", "• ", "– ")
        for (prefix in bulletPrefixes) {
            if (workingLine.startsWith(prefix)) {
                workingLine = workingLine.removePrefix(prefix).trimStart()
                break
            }
        }

        val numberedPrefixRegex = Regex("^\\d+[\\.)]\\s*")
        workingLine = workingLine.replaceFirst(numberedPrefixRegex, "").trimStart()

        val labelPrefixes = listOf(
            "Thought Process:",
            "Thought:",
            "Process:",
            "Goal:",
            "Task:",
            "Plan:"
        )
        for (prefix in labelPrefixes) {
            if (workingLine.startsWith(prefix, ignoreCase = true)) {
                workingLine = workingLine.substring(prefix.length)
                    .trimStart { it == ':' || it.isWhitespace() }
                break
            }
        }

        val separatorCandidates = listOf(". ", ";", " - ")
        val shortened = separatorCandidates
            .map { workingLine.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()

        return if (shortened != null) {
            workingLine.substring(0, shortened).trim()
        } else {
            workingLine.trim()
        }
    }

    private fun extractPlannerMetadata(text: String): Pair<String?, String> {
			if (text.isBlank()) {
				return null to ""
        }

        var processTitle: String? = null
        val remainingLines = mutableListOf<String>()

        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (processTitle == null && trimmed.startsWith("Process Title:", ignoreCase = true)) {
                processTitle = trimmed.substringAfter(":").trim()
            } else {
                remainingLines += line
            }
        }

        return processTitle to remainingLines.joinToString("\n").trim()
    }

    private fun updateProcessTitle(titleCandidate: String?, fallbackSource: String) {
        val sanitizedCandidate = titleCandidate?.let { sanitizeProcessTitle(it) }.orEmpty()
        val fallbackTitle = fallbackSource
            .lineSequence()
            .map { extractTaskDescription(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        currentProcessTitle = when {
            sanitizedCandidate.isNotBlank() -> sanitizedCandidate
            fallbackTitle.isNotBlank() -> fallbackTitle
            else -> ""
        }
    }

    private fun sanitizeProcessTitle(title: String): String {
        return title
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // --- Helper methods for the run loop ---

    private fun buildInitialContent(): Content {
        val prompt = _messages.value.lastOrNull { it.sender == Sender.USER }?.text ?: ""
        val currentMessages = _messages.value.dropLast(1) // Exclude placeholder
        val formattedHistory = currentMessages.takeIf { it.isNotEmpty() }?.let {
            val historyLog = it.joinToString("\n") { msg ->
                "${msg.sender}: ${msg.text}"
            }
            "\n[START OF PREVIOUS CONVERSATION]\n$historyLog\n[END OF PREVIOUS CONVERSATION]\n\n"
        } ?: ""
        val header = buildSystemPrompt()
        val augmentedPrompt =
            createAugmentedPrompt(prompt, header, globalStaticExamples, formattedHistory)
        return Content.builder().role("user").parts(Part.builder().text(augmentedPrompt).build())
            .build()
    }

    private fun processPlannerStep(token: Long, history: MutableList<Content>): Content {
        updateLastMessageIfActive(token, "Planning...")
        val plan = planner.plan(history)
        val combinedText = plan.parts().get()
            .mapNotNull { it.text().getOrNull() }
            .joinToString("\n")
            .trim()

        val (processTitle, remainingThought) = extractPlannerMetadata(combinedText)
        updateProcessTitle(processTitle, remainingThought)

        if (remainingThought.isNotEmpty()) {
            currentAIAgentThought = remainingThought
            updateLastMessageIfActive(token, remainingThought)
        } else {
            currentAIAgentThought = "Analyzing and planning next steps..."
            updateLastMessageIfActive(token, currentAIAgentThought)
        }

        history.add(plan)
        logTurn("model", plan.parts().get())
        return plan
    }

    private suspend fun processToolExecutionStep(token: Long, functionCalls: List<com.google.genai.types.FunctionCall>): List<Part> {
        val toolCallSummary =
            functionCalls.joinToString("\n") { "Calling tool: `${it.name().get()}`" }
        val fullStatus = if (currentAIAgentThought.isNotEmpty()) {
            "$currentAIAgentThought\n\n$toolCallSummary"
        } else {
            toolCallSummary
        }
        updateLastMessageIfActive(token, fullStatus)
        return executor.execute(functionCalls)
    }

    private suspend fun processCriticStep(token: Long, history: MutableList<Content>): String {
        updateLastMessageIfActive(token, "$currentAIAgentThought\n\nReviewing results and verifying changes...")
        val critiqueResult = critic.reviewAndSummarize(history)
        if (critiqueResult != "OK") {
            history.add(
                Content.builder().role("user")
                    .parts(Part.builder().text(critiqueResult).build()).build()
            )
            logTurn("system_critic", history.last().parts().get())
        }
        return critiqueResult
    }

    private fun generateFinalAnswer(history: List<Content>): String {
        val finalInstruction = Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .text("Provide a final, concise answer to the user's request based on the conversation so far.")
                    .build()
            )
            .build()

        val finalHistory = history.toMutableList().apply { add(finalInstruction) }
        val response = plannerClient.generateContent(finalHistory, tools = emptyList())
        val finalText = response.text()?.trim().orEmpty()
        updateLastMessage(finalText)
        logTurn("final_answer", listOf(Part.builder().text(finalText).build()))
        return finalText
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

    override fun loadHistory(history: List<ChatMessage>) {
        _messages.value = history.toList()
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

    private fun addMessageIfActive(token: Long, text: String, sender: Sender) {
        if (!isTokenActive(token)) return
        addMessage(text, sender)
    }

    private fun updateLastMessageIfActive(token: Long, newText: String) {
        if (!isTokenActive(token)) return
        updateLastMessage(newText)
    }

    private fun onStateUpdateIfActive(token: Long, state: AgentState) {
        if (!isTokenActive(token)) return
        onStateUpdate?.invoke(state)
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
