package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.errors.ClientException
import com.google.genai.errors.ServerException
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull
import kotlin.math.min


class AgenticRunner(
    private val context: Context,
    private val maxSteps: Int = 20,
    toolsOverride: List<Tool>? = null,
    plannerOverride: Planner? = null,
    criticOverride: Critic? = null,
    executorOverride: Executor? = null
) : GeminiRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
        )
    )
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _plan = MutableStateFlow<Plan?>(null)
    override val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private var runnerJob: Job = SupervisorJob()
    private var runnerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + runnerJob)

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

    private var executor: Executor = executorOverride ?: Executor()


    override var onStateUpdate: ((AgentState) -> Unit)? = null


    private val toolTracker = ToolExecutionTracker()

    /**
     * Immediately cancels the runner's CoroutineScope.
     * This will interrupt any ongoing network calls (plan, critique) and
     * cause the run loop to terminate with a CancellationException.
     */
    override fun stop() {
        log.info("Stop requested for AgenticRunner. Cancelling job.")
        runnerScope.cancel("User requested to stop the agent.")
        runnerJob = SupervisorJob()
        runnerScope = CoroutineScope(Dispatchers.IO + runnerJob)
        _plan.value = null
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

    private val tools: List<Tool> = toolsOverride ?: allAgentTools
    private val planner: Planner = plannerOverride ?: Planner(plannerClient, this.tools)
    private val critic: Critic = criticOverride ?: Critic(criticClient)
    private val globalPolicy: String
    private val globalStaticExamples: List<Map<String, Any>>

    private var logTs: String = ""
    private var logEntries = mutableListOf<JsonObject>()

    companion object {
        private val log = LoggerFactory.getLogger(AgenticRunner::class.java)
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 100L
        private const val MAX_RETRY_DELAY_MS = 2_000L
        private const val RETRY_BACKOFF_MULTIPLIER = 2.0

        private val json = Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    init {
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
    ) {
        // 1. Load the history from the current session
        resetPlan()
        loadHistory(history)

        // 2. Add the user's new message to the flow so it appears instantly
        addMessage(prompt, Sender.USER)

        val finalMessage = try {
            runnerScope.async {
                run()
            }.await()
        } catch (e: CancellationException) {
            log.warn("generateASimpleResponse caught cancellation.")
            updateLastMessage("Operation cancelled by user.") // Update UI on cancellation
            "Operation cancelled by user."
        }

        onStateUpdate?.invoke(AgentState.Idle)
    }

    private suspend fun run(): String {
        startLog()
        addMessage("...", Sender.AGENT)
        onStateUpdate?.invoke(AgentState.Processing("Initializing..."))

        val initialContent = buildInitialContent()
        val history = mutableListOf(initialContent)

        try {
            initializePlan(history)

            for (step in 0 until maxSteps) {
                runnerScope.ensureActive()

                val stepIndex = currentActionableStepIndex()
                if (stepIndex == null) {
                    val completionMessage =
                        _messages.value.lastOrNull()?.text?.takeIf { it.isNotBlank() }
                            ?: "Plan completed."
                    updateLastMessage(completionMessage)
                    return completionMessage
                }

                val currentStep = _plan.value!!.steps[stepIndex]
                markStepStatus(stepIndex, StepStatus.IN_PROGRESS, null)

                onStateUpdate?.invoke(
                    AgentState.Processing("Step ${stepIndex + 1}: ${currentStep.description}")
                )
                updateLastMessage("Executing: ${currentStep.description}")

                val planContent = processPlannerStep(history)
                val functionCalls =
                    planContent.parts().get().mapNotNull { it.functionCall().getOrNull() }

                if (functionCalls.isEmpty()) {
                    val finalText =
                        planContent.parts().get().first().text().getOrNull()?.trim().orEmpty()
                    updateLastMessage(finalText)
                    logTurn("final_answer", listOf(Part.builder().text(finalText).build()))
                    markStepStatus(stepIndex, StepStatus.DONE, finalText)
                    if (_plan.value?.isComplete() == true) {
                        return finalText
                    }
                    continue
                }

                val toolResultsParts = processToolExecutionStep(functionCalls)
                history.add(Content.builder().role("tool").parts(toolResultsParts).build())
                logTurn("tool", toolResultsParts)

                val critiqueResult = processCriticStep(history)
                val resultMessage = critiqueResult.takeUnless { it == "OK" }
                markStepStatus(stepIndex, StepStatus.DONE, resultMessage)
            }
            throw RuntimeException("Exceeded max steps")
        } catch (err: Exception) {
            if (err is CancellationException) {
                log.warn("Agentic run was cancelled during execution.")
                return "Operation cancelled by user."
            }
            log.error("Agentic run failed", err)
            markCurrentStepFailed(err.message)
            val errorMessage = "An error occurred: ${err.message}"
            updateLastMessage(errorMessage)
            return "Agentic run failed: ${err.message}"
        } finally {
            writeLog()
        }
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

    private suspend fun processPlannerStep(history: MutableList<Content>): Content {
        updateLastMessage("Planning...")
        val plan = runWithRetry("planner") {
            planner.plan(history)
        }
        history.add(plan)
        logTurn("model", plan.parts().get())
        return plan
    }

    private suspend fun processToolExecutionStep(functionCalls: List<com.google.genai.types.FunctionCall>): List<Part> {
        val toolCallSummary =
            functionCalls.joinToString("\n") { "Calling tool: `${it.name().get()}`" }
        updateLastMessage(toolCallSummary)
        return executor.execute(functionCalls)
    }

    private suspend fun processCriticStep(history: MutableList<Content>): String {
        updateLastMessage("Reviewing results...")
        val critiqueResult = runWithRetry("critic") {
            critic.reviewAndSummarize(history)
        }
        if (critiqueResult != "OK") {
            history.add(
                Content.builder().role("user")
                    .parts(Part.builder().text(critiqueResult).build()).build()
            )
            logTurn("system_critic", history.last().parts().get())
        }
        return critiqueResult
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
        _plan.value = null
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

    private fun resetPlan() {
        _plan.value = null
    }

    private suspend fun initializePlan(history: MutableList<Content>) {
        if (_plan.value != null) return
        updateLastMessage("Creating plan...")
        val generatedPlan = runWithRetry("planner_plan") {
            planner.generatePlan(history)
        }
        val planToUse = if (generatedPlan.steps.isEmpty()) {
            Plan(mutableListOf(TaskStep(description = "Address the user's request.")))
        } else {
            generatedPlan
        }
        _plan.value = planToUse
        logPlanSnapshot("plan_created")
        val summary = if (planToUse.steps.isEmpty()) {
            "Plan ready."
        } else {
            val next = planToUse.steps.first().description
            "Plan ready (${planToUse.steps.size} steps). Next: $next"
        }
        updateLastMessage(summary)
    }

    private fun currentActionableStepIndex(): Int? = _plan.value?.firstActionableIndex()

    private fun markStepStatus(index: Int, status: StepStatus, result: String?) {
        mutateCurrentPlan { plan ->
            plan.withUpdatedStep(index) { step ->
                val newResult = result ?: step.result
                step.withStatus(status, newResult)
            }
        }
    }

    private fun markCurrentStepFailed(message: String?) {
        val plan = _plan.value ?: return
        val inProgress = plan.steps.indexOfFirst { it.status == StepStatus.IN_PROGRESS }
        val fallback = plan.firstActionableIndex()
        val targetIndex = when {
            inProgress >= 0 -> inProgress
            fallback != null -> fallback
            else -> plan.steps.lastIndex.takeIf { it >= 0 }
        } ?: return
        markStepStatus(targetIndex, StepStatus.FAILED, message)
    }

    private fun mutateCurrentPlan(mutator: (Plan) -> Plan) {
        val current = _plan.value ?: return
        val workingCopy = current.deepCopy()
        val updated = mutator(workingCopy)
        _plan.value = updated
        logPlanSnapshot("plan_updated")
    }

    private fun logPlanSnapshot(tag: String) {
        val planSnapshot = _plan.value ?: return
        val logEntry = buildJsonObject {
            put("step", logEntries.size + 1)
            put("turn", tag)
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "plan")
                    putJsonArray("steps") {
                        planSnapshot.steps.forEachIndexed { index, task ->
                            add(buildJsonObject {
                                put("index", index)
                                put("description", task.description)
                                put("status", task.status.name)
                                task.result?.let { put("result", it) }
                            })
                        }
                    }
                })
            }
        }
        logEntries.add(logEntry)
    }

    private suspend fun <T> runWithRetry(
        operationName: String,
        block: suspend () -> T
    ): T {
        var currentDelay = INITIAL_RETRY_DELAY_MS
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                if (!isRetryableNetworkError(err) || attempt == MAX_RETRY_ATTEMPTS - 1) {
                    throw err
                }
                log.warn(
                    "Retryable error during $operationName (attempt ${attempt + 1}). Retrying in ${currentDelay}ms. Cause: ${err.message}"
                )
                delay(currentDelay)
                currentDelay = min(
                    (currentDelay * RETRY_BACKOFF_MULTIPLIER).toLong(),
                    MAX_RETRY_DELAY_MS
                )
            }
        }
        error("runWithRetry should always return or throw")
    }

    private fun isRetryableNetworkError(err: Throwable): Boolean {
        return when (err) {
            is IOException -> true
            is ServerException -> true
            is ClientException -> err.code() == 408 || err.code() == 429
            else -> false
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
