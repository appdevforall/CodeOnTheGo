package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.errors.ClientException
import com.google.genai.errors.ServerException
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.Part
import com.google.genai.types.Tool
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ApprovalId
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.model.ReviewDecision
import com.itsaky.androidide.agent.prompt.ModelFamily
import com.itsaky.androidide.agent.prompt.ResponseItem
import com.itsaky.androidide.agent.prompt.TurnContext
import com.itsaky.androidide.agent.prompt.buildMessagesForChatAPI
import com.itsaky.androidide.agent.prompt.buildPrompt
import com.itsaky.androidide.agent.tool.ToolApprovalManager
import com.itsaky.androidide.agent.tool.ToolApprovalResponse
import com.itsaky.androidide.agent.tool.ToolHandler
import com.itsaky.androidide.agent.tool.ToolsConfigParams
import com.itsaky.androidide.agent.tool.buildToolRouter
import com.itsaky.androidide.agent.tool.buildToolsConfig
import com.itsaky.androidide.agent.tool.toJsonElement
import com.itsaky.androidide.agent.tool.toolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.math.min

internal const val BASE_AGENT_DEFAULT_INSTRUCTIONS =
    "You are an expert Android developer agent specializing in both Views and Jetpack Compose. " +
            "Your goal is to fulfill user requests by interacting with an IDE.\n" +
            "Follow policies strictly."

/**
 * Contains the shared, model-agnostic logic for running a multi-step agentic workflow.
 *
 * Subclasses provide the model-specific implementations for planning and action selection.
 */
abstract class BaseAgenticRunner(
    protected val context: Context,
    protected val modelFamily: ModelFamily,
    private val maxSteps: Int = 20,
    private val toolsOverride: List<Tool>? = null,
    private val executorOverride: Executor? = null
) : GeminiRepository {

    /**
     * All tools available to the planner. Subclasses can override to customize.
     */
    protected open val tools: List<Tool> = toolsOverride ?: allAgentTools

    protected open val approvalManager: ToolApprovalManager = object : ToolApprovalManager {
        override suspend fun ensureApproved(
            toolName: String,
            handler: ToolHandler,
            args: Map<String, Any?>
        ): ToolApprovalResponse {
            return ensureToolApproved(toolName, handler, args)
        }
    }
    protected open val executor: Executor = executorOverride ?: createExecutor()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _plan = MutableStateFlow<Plan?>(null)
    override val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private var runnerJob: Job = SupervisorJob()
    private var runnerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + runnerJob)

    override var onStateUpdate: ((AgentState) -> Unit)? = null

    private val toolTracker = ToolExecutionTracker()
    private var lastRunEncounteredError = false

    private val approvedForSession = mutableSetOf<String>()
    private val approvalLock = Any()
    private val pendingApprovals = mutableMapOf<ApprovalId, PendingApproval>()

    private var logTs: String = ""
    private var logEntries = mutableListOf<JsonObject>()

    private val globalPolicy: String
    private val globalStaticExamples: List<Map<String, Any>>

    init {
        this.globalPolicy = getPolicyConfig()
        this.globalStaticExamples = getFewShotsConfig()
    }

    companion object {
        private val log = LoggerFactory.getLogger(BaseAgenticRunner::class.java)

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 100L
        private const val MAX_RETRY_DELAY_MS = 2_000L
        private const val RETRY_BACKOFF_MULTIPLIER = 2.0
        private const val MAX_STEP_ATTEMPTS = 3
        private val json = Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    // --- Abstract hooks for subclasses ---------------------------------------------------------

    /**
     * Build the initial planner-ready content from the current message history.
     */
    protected open fun buildInitialContent(): Content {
        val historyWithoutPlaceholder = _messages.value.dropLast(1)
        val responseItems = historyWithoutPlaceholder.mapNotNull { it.toResponseItem() }
        if (responseItems.isEmpty()) {
            throw IllegalStateException("Unable to build prompt without a user message.")
        }

        val firstUserIndex = responseItems.indexOfFirst { item ->
            item is ResponseItem.Message && item.role == "user"
        }
        if (firstUserIndex == -1) {
            throw IllegalStateException("Conversation must contain at least one user message.")
        }

        val effectiveItems = responseItems.drop(firstUserIndex)

        val turnContext = TurnContext(
            modelFamily = modelFamily,
            toolsConfig = tools,
            baseInstructionsOverride = buildInstructionOverride()
        )
        val prompt = buildPrompt(turnContext, effectiveItems)
        val messages = buildMessagesForChatAPI(prompt, modelFamily)
        return messages.first()
    }

    /**
     * Subclasses must ask their underlying model to produce an initial plan.
     */
    protected abstract suspend fun createInitialPlan(history: List<Content>): Plan

    /**
     * Subclasses must ask their underlying model to decide the next action for a plan step.
     */
    protected abstract suspend fun planForStep(
        history: List<Content>,
        plan: Plan,
        stepIndex: Int
    ): Content

    /**
     * Allows subclasses to customize review/summarization after tool execution.
     */
    protected open suspend fun processCriticStep(history: MutableList<Content>): String {
        return "OK"
    }

    // --- GeminiRepository ----------------------------------------------------------------------

    override fun stop() {
        log.info("Stop requested for BaseAgenticRunner. Cancelling job.")
        runnerScope.cancel("User requested to stop the agent.")
        runnerJob = SupervisorJob()
        runnerScope = CoroutineScope(Dispatchers.IO + runnerJob)
        _plan.value = null

        val pendingDeferreds = mutableListOf<CompletableDeferred<ReviewDecision>>()
        synchronized(approvalLock) {
            pendingDeferreds += pendingApprovals.values.map { it.deferred }
            pendingApprovals.clear()
        }
        pendingDeferreds.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(ReviewDecision.Denied)
            }
        }

        onRunnerStopped()
    }

    protected open fun onRunnerStopped() {}

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ) {
        lastRunEncounteredError = false
        onStateUpdate?.invoke(AgentState.Initializing("Preparing agent..."))
        resetPlan()
        loadHistory(history)

        addMessage(prompt, Sender.USER)

        try {
            runnerScope.async {
                run()
            }.await()
        } catch (e: CancellationException) {
            log.warn("generateASimpleResponse caught cancellation.")
            addMessage("Operation cancelled by user.", Sender.SYSTEM)
        }

        if (!lastRunEncounteredError) {
            onStateUpdate?.invoke(AgentState.Idle)
        }
    }

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
        throw UnsupportedOperationException("Code generation is not yet implemented.")
    }

    override fun loadHistory(history: List<ChatMessage>) {
        _plan.value = null
        _messages.value = history
    }

    override fun submitApprovalDecision(id: ApprovalId, decision: ReviewDecision) {
        var deferred: CompletableDeferred<ReviewDecision>? = null
        synchronized(approvalLock) {
            val pending = pendingApprovals[id]
            if (pending == null) {
                log.warn("Received decision for unknown approval id: {}", id)
            } else {
                deferred = pending.deferred
            }
        }
        deferred?.takeIf { !it.isCompleted }?.complete(decision)
    }

    // --- Core run loop -------------------------------------------------------------------------

    private suspend fun run(): String {
        startLog()
        addMessage("Starting agent workflow...", Sender.AGENT)
        onStateUpdate?.invoke(AgentState.Initializing("Crafting a plan..."))

        val initialContent = buildInitialContent()
        val history = mutableListOf(initialContent)

        val initialPlan = runWithRetry("planner_initial_plan") {
            createInitialPlan(history)
        }

        if (initialPlan.steps.isEmpty()) {
            val message = "I couldn't devise a plan for that request."
            addMessage(message, Sender.AGENT)
            return message
        }

        _plan.value = initialPlan
        logPlanSnapshot("plan_created")
        postPlanSummary(initialPlan)
        emitExecutingState(_plan.value?.firstActionableIndex())

        try {
            val plannedSteps = _plan.value?.steps?.size ?: 0
            val totalSteps = plannedSteps.coerceAtMost(maxSteps)
            if (plannedSteps > maxSteps) {
                log.warn(
                    "Plan contains {} steps but runner is limited to {}. Only the first {} steps will be executed.",
                    plannedSteps,
                    maxSteps,
                    totalSteps
                )
            }
            for (stepIndex in 0 until totalSteps) {
                runnerScope.ensureActive()

                val currentPlanSnapshot = _plan.value
                    ?: throw IllegalStateException("Plan disappeared during execution.")
                val stepDescription = currentPlanSnapshot.steps[stepIndex].description

                markStepStatus(stepIndex, StepStatus.IN_PROGRESS, null)
                emitExecutingState(stepIndex)
                addMessage(
                    "Step ${stepIndex + 1} of $totalSteps: $stepDescription",
                    Sender.AGENT
                )

                var attempts = 0
                var stepSucceeded = false

                while (!stepSucceeded && attempts < MAX_STEP_ATTEMPTS) {
                    val planSnapshot = _plan.value?.deepCopy()
                        ?: throw IllegalStateException("Plan snapshot unavailable.")
                    onStateUpdate?.invoke(
                        AgentState.Thinking("Determining next action for: \"$stepDescription\"")
                    )

                    val plannerContent = runWithRetry("planner_step") {
                        planForStep(history, planSnapshot, stepIndex)
                    }
                    val plannerParts = plannerContent.parts().getOrNull().orEmpty()
                    history.add(plannerContent)
                    logTurn("model_step_${stepIndex + 1}", plannerParts)

                    val functionCalls = plannerParts.mapNotNull { it.functionCall().getOrNull() }

                    if (functionCalls.isEmpty()) {
                        val responseText =
                            plannerParts.firstOrNull()?.text()?.getOrNull()?.trim()
                        if (!responseText.isNullOrBlank()) {
                            addMessage(responseText, Sender.AGENT)
                        } else {
                            addMessage(
                                "Step ${stepIndex + 1} resolved without additional actions.",
                                Sender.AGENT
                            )
                        }
                        markStepStatus(stepIndex, StepStatus.DONE, responseText)
                        emitExecutingState(stepIndex)
                        stepSucceeded = true
                        continue
                    }

                    onStateUpdate?.invoke(
                        AgentState.Thinking("Calling tools for: \"$stepDescription\"")
                    )
                    val toolResultsParts = processToolExecutionStep(functionCalls)
                    history.add(Content.builder().role("tool").parts(toolResultsParts).build())
                    logTurn("tool_step_${stepIndex + 1}", toolResultsParts)

                    val critique = processCriticStep(history)
                    if (critique == "OK") {
                        val formattedResults = toolResultsParts.mapNotNull { part ->
                            formatToolResultPart(part).takeIf { it.isNotBlank() }
                        }
                        val resultSummary = formattedResults.joinToString("\n\n")
                            .ifBlank { buildToolSuccessSummary(functionCalls) }
                        markStepStatus(stepIndex, StepStatus.DONE, resultSummary)
                        addMessage(
                            "Step ${stepIndex + 1} completed successfully.",
                            Sender.AGENT
                        )
                        emitExecutingState(stepIndex)
                        stepSucceeded = true
                    } else {
                        attempts++
                        markStepStatus(stepIndex, StepStatus.IN_PROGRESS, critique)
                        emitExecutingState(stepIndex)
                    }
                }

                if (!stepSucceeded) {
                    markStepStatus(
                        stepIndex,
                        StepStatus.FAILED,
                        "Exceeded $MAX_STEP_ATTEMPTS attempts."
                    )
                    emitExecutingState(stepIndex)
                    throw RuntimeException(
                        "Failed to complete step: \"$stepDescription\" after $MAX_STEP_ATTEMPTS attempts."
                    )
                }
            }

            val finalPlanSnapshot = _plan.value
            val finalAnswer = finalPlanSnapshot?.steps?.lastOrNull()?.result
                ?.takeIf { !it.isNullOrBlank() }
                ?: "I have completed all the steps in the plan."
            addFinalAgentMessageIfNeeded(finalAnswer)
            logTurn(
                "final_answer",
                listOf(Part.builder().text(finalAnswer).build())
            )
            emitExecutingState(finalPlanSnapshot?.steps?.lastIndex)
            return finalAnswer
        } catch (err: Exception) {
            if (err is CancellationException) {
                log.warn("Agentic run was cancelled during execution.")
                return "Operation cancelled by user."
            }
            log.error("Agentic run failed", err)
            lastRunEncounteredError = true
            val failedIndex = markCurrentStepFailed(err.message)
            emitExecutingState(failedIndex)
            val errorMessage = "An error occurred: ${err.message}"
            addMessage(errorMessage, Sender.SYSTEM)
            onStateUpdate?.invoke(AgentState.Error(errorMessage))
            return "Agentic run failed: ${err.message}"
        } finally {
            writeLog()
        }
    }

    private fun createExecutor(): Executor {
        val toolsConfigParams = ToolsConfigParams(modelFamily = modelFamily)
        val toolsConfig = buildToolsConfig(toolsConfigParams)
        val toolRouter = buildToolRouter(toolsConfig)
        return Executor(toolRouter, approvalManager)
    }

    private suspend fun processToolExecutionStep(functionCalls: List<FunctionCall>): List<Part> {
        val toolNames = functionCalls.map { it.name().getOrNull() ?: "unknown" }
        val toolCallSummary = toolNames.joinToString("\n") { name ->
            "Calling tool: `$name`"
        }
        addMessage(toolCallSummary, Sender.SYSTEM)
        val results = executor.execute(functionCalls)
        val formatted = results.mapNotNull { part ->
            formatToolResultPart(part).takeIf { text -> text.isNotBlank() }
        }
        if (formatted.isEmpty()) {
            addMessage(buildToolSuccessSummary(functionCalls), Sender.TOOL)
        } else {
            formatted.forEach { addMessage(it, Sender.TOOL) }
        }
        return results
    }

    private fun buildToolSuccessSummary(functionCalls: List<FunctionCall>): String {
        val toolNames = functionCalls.map { it.name().getOrNull() ?: "unknown" }
        return if (toolNames.size == 1) {
            "Tool `${toolNames.first()}` finished successfully."
        } else {
            "Tools ${toolNames.joinToString { "`$it`" }} finished successfully."
        }
    }

    private fun formatToolResultPart(part: Part): String {
        val functionResponse = part.functionResponse().getOrNull() ?: return ""
        val toolName = functionResponse.name().getOrNull()?.takeIf { it.isNotBlank() } ?: "tool"
        val payloadAny = functionResponse.response().getOrNull()
            ?: return "$toolName finished."

        val payloadMap = payloadAny as? Map<*, *> ?: return "$toolName: ${payloadAny.toString()}"

        val successAny = unwrapOptional(payloadMap["success"])
        val success = when (successAny) {
            is Boolean -> successAny
            is String -> successAny.equals("true", ignoreCase = true)
            else -> null
        }
        val status = when (success) {
            true -> "succeeded"
            false -> "failed"
            null -> "finished"
        }

        val message = unwrapOptional(payloadMap["message"])?.toString()?.takeIf { it.isNotBlank() }
        val errorDetails =
            unwrapOptional(payloadMap["error_details"])?.toString()?.takeIf { it.isNotBlank() }
        val data = unwrapOptional(payloadMap["data"])?.toString()
            ?.takeIf { it.isNotBlank() && it != "{}" && !it.equals("null", ignoreCase = true) }

        return buildString {
            append("$toolName $status")
            if (!message.isNullOrBlank()) {
                append(": ")
                append(message.trim())
            }
            if (!errorDetails.isNullOrBlank()) {
                if (!message.isNullOrBlank()) append(" ")
                append("(Details: ${errorDetails.trim()})")
            }
            if (!data.isNullOrBlank()) {
                append("\n")
                append(data.trim())
            }
        }.trim()
    }

    private fun unwrapOptional(value: Any?): Any? = when (value) {
        is java.util.Optional<*> -> value.getOrNull()
        else -> value
    }

    private fun buildInstructionOverride(): String {
        val currentTime = LocalDateTime.now()
        val formattedTime =
            "${currentTime.dayOfWeek}, ${currentTime.toLocalDate()} ${currentTime.hour}:${
                currentTime.minute.toString().padStart(2, '0')
            }"

        val builder = StringBuilder()
        builder.append(baseInstructions())
        builder.append("\n\n[Session Information]\n- Current Date and Time: $formattedTime\n\n")

        if (globalPolicy.isNotBlank()) {
            builder.append("[Global Rules]\n")
            builder.append(globalPolicy.trim())
            builder.append("\n\n")
        }

        builder.append("[Tooling]\nUse tools when they reduce uncertainty or are required by policy.\n\n")

        val examplesBlock = formatExamples(globalStaticExamples)
        if (examplesBlock.isNotBlank()) {
            builder.append(examplesBlock)
        }

        builder.append("Based on the user's request, you MUST respond by calling one or more of the available tools. Do not provide a conversational answer.")

        return builder.toString().trimEnd()
    }

    protected open fun baseInstructions(): String = BASE_AGENT_DEFAULT_INSTRUCTIONS

    private fun formatExamples(examples: List<Map<String, Any>>): String {
        if (examples.isEmpty()) return ""
        val builder = StringBuilder()
        examples.forEachIndexed { index, rawDialogue ->
            val dialogueTurns =
                (rawDialogue["dialogue"] as? List<*>)?.filterIsInstance<Map<String, String>>()
                    ?: emptyList()
            if (dialogueTurns.isEmpty()) {
                return@forEachIndexed
            }
            builder.append("--- Example ${index + 1} ---\n")
            dialogueTurns.forEach { turn ->
                val role = turn["role"] ?: return@forEach
                val text = turn["text"] ?: ""
                val labeledRole = when (role.lowercase()) {
                    "user" -> "User"
                    "assistant" -> "Assistant"
                    else -> role.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                    }
                }
                builder.append("$labeledRole: $text\n")
            }
            builder.append("--- End Example ---\n\n")
        }
        return builder.toString()
    }

    private fun startLog() {
        val now = LocalDateTime.now()
        logTs = now.toString()
        logEntries.clear()
    }

    private fun logTurn(turn: String, parts: List<Part>) {
        val logEntry = buildJsonObject {
            put("step", logEntries.size + 1)
            put("turn", turn)
            putJsonArray("content") {
                parts.forEach { part ->
                    add(serializePartToJsonObject(part))
                }
            }
        }
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
            val logContent =
                json.encodeToString(ListSerializer(JsonObject.serializer()), logEntries)

            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val logFile = File(logDir, "agent_run_${logTs}.interaction.json")
            logFile.writeText(logContent, Charsets.UTF_8)
            log.info("Full agent interaction logged to ${logFile.absolutePath}")

        } catch (e: Exception) {
            log.error("Failed to write interaction log.", e)
        }
    }

    override fun getPartialReport(): String {
        return toolTracker.generatePartialReport()
    }

    private fun resetPlan() {
        _plan.value = null
    }

    private fun addMessage(text: String, sender: Sender) {
        val message = ChatMessage(text = text, sender = sender)
        _messages.update { currentList -> currentList + message }
    }

    private fun postPlanSummary(plan: Plan) {
        if (plan.steps.isEmpty()) return
        val summary = buildString {
            val stepCount = plan.steps.size
            val header = if (stepCount == 1) {
                "Plan created with 1 step:"
            } else {
                "Plan created with $stepCount steps:"
            }
            append(header)
            append('\n')
            plan.steps.forEachIndexed { index, task ->
                append("${index + 1}. ${task.description}")
                if (index != plan.steps.lastIndex) {
                    append('\n')
                }
            }
        }.trimEnd()
        addMessage(summary, Sender.SYSTEM)
    }

    private fun addFinalAgentMessageIfNeeded(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val lastText = _messages.value.lastOrNull()?.text?.trim()
        if (lastText != trimmed) {
            addMessage(trimmed, Sender.AGENT)
        }
    }

    private fun markStepStatus(index: Int, status: StepStatus, result: String?) {
        mutateCurrentPlan { plan ->
            plan.withUpdatedStep(index) { step ->
                val newResult = result ?: step.result
                step.withStatus(status, newResult)
            }
        }
    }

    private fun markCurrentStepFailed(message: String?): Int? {
        val plan = _plan.value ?: return null
        val inProgress = plan.steps.indexOfFirst { it.status == StepStatus.IN_PROGRESS }
        val fallback = plan.firstActionableIndex()
        val targetIndex = when {
            inProgress >= 0 -> inProgress
            fallback != null -> fallback
            else -> plan.steps.lastIndex.takeIf { it >= 0 }
        } ?: return null
        markStepStatus(targetIndex, StepStatus.FAILED, message)
        return targetIndex
    }

    private fun mutateCurrentPlan(mutator: (Plan) -> Plan) {
        val current = _plan.value ?: return
        val workingCopy = current.deepCopy()
        val updated = mutator(workingCopy)
        _plan.value = updated
        logPlanSnapshot("plan_updated")
    }

    private fun emitExecutingState(preferredIndex: Int?) {
        val planSnapshot = _plan.value ?: return
        if (planSnapshot.steps.isEmpty()) return
        val targetIndex = when {
            preferredIndex != null && preferredIndex in planSnapshot.steps.indices -> preferredIndex
            else -> planSnapshot.firstActionableIndex() ?: planSnapshot.steps.lastIndex
        }
        val planCopy = planSnapshot.deepCopy()
        onStateUpdate?.invoke(AgentState.Executing(planCopy, targetIndex))
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

    protected suspend fun <T> runWithRetry(
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

    protected open fun isRetryableNetworkError(err: Throwable): Boolean {
        return when (err) {
            is IOException -> true
            is ServerException -> true
            is ClientException -> err.code() == 408 || err.code() == 429
            else -> false
        }
    }

    private suspend fun ensureToolApproved(
        toolName: String,
        handler: ToolHandler,
        args: Map<String, Any?>
    ): ToolApprovalResponse {
        if (!handler.isPotentiallyDangerous) {
            return ToolApprovalResponse(approved = true)
        }

        val signature = createToolSignature(toolName, args)
        synchronized(approvalLock) {
            if (approvedForSession.contains(signature)) {
                return ToolApprovalResponse(approved = true)
            }
        }

        val decision = requestUserApproval(signature, toolName, args)

        return when (decision) {
            ReviewDecision.Approved -> {
                notifyApprovalResolution(toolName, decision)
                ToolApprovalResponse(approved = true)
            }

            ReviewDecision.ApprovedForSession -> {
                synchronized(approvalLock) {
                    approvedForSession.add(signature)
                }
                notifyApprovalResolution(toolName, decision)
                ToolApprovalResponse(approved = true)
            }

            ReviewDecision.Denied -> {
                notifyApprovalResolution(toolName, decision)
                addMessage(
                    "Action '$toolName' was denied. No changes were made.",
                    Sender.AGENT
                )
                ToolApprovalResponse(
                    approved = false,
                    denialMessage = "User denied the request for '$toolName'."
                )
            }
        }
    }

    private suspend fun requestUserApproval(
        signature: String,
        toolName: String,
        args: Map<String, Any?>
    ): ReviewDecision {
        val approvalId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ReviewDecision>()

        synchronized(approvalLock) {
            pendingApprovals[approvalId] = PendingApproval(signature, toolName, args, deferred)
        }

        val reason = buildApprovalReason(toolName)
        onStateUpdate?.invoke(AgentState.AwaitingApproval(approvalId, toolName, args, reason))

        return try {
            deferred.await()
        } finally {
            synchronized(approvalLock) {
                pendingApprovals.remove(approvalId)
            }
        }
    }

    private fun buildApprovalReason(toolName: String): String {
        return "Tool '$toolName' wants to perform an action that may modify your project."
    }

    private fun notifyApprovalResolution(toolName: String, decision: ReviewDecision) {
        val message = when (decision) {
            ReviewDecision.Approved -> "Approval received for '$toolName'."
            ReviewDecision.ApprovedForSession -> "Approved '$toolName' for the remainder of this session."
            ReviewDecision.Denied -> "Denied '$toolName'."
        }
        onStateUpdate?.invoke(AgentState.Thinking(message))
    }

    private fun createToolSignature(toolName: String, args: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val normalizedArgs = normalizeArgs(args) as? Map<String, Any?> ?: emptyMap()
        val jsonElement = normalizedArgs.toJsonElement()
        val argsJson = toolJson.encodeToString(JsonElement.serializer(), jsonElement)
        return "$toolName|$argsJson"
    }

    private fun normalizeArgs(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> value.entries
                .filter { it.key != null }
                .sortedBy { it.key.toString() }
                .associate { entry ->
                    entry.key.toString() to normalizeArgs(entry.value)
                }

            is List<*> -> value.map { normalizeArgs(it) }
            is Array<*> -> value.map { normalizeArgs(it) }
            else -> value
        }
    }

    private data class PendingApproval(
        val signature: String,
        val toolName: String,
        val args: Map<String, Any?>,
        val deferred: CompletableDeferred<ReviewDecision>
    )

    private fun ChatMessage.toResponseItem(): ResponseItem? {
        if (text.isBlank()) return null
        val role = when (sender) {
            Sender.USER -> "user"
            Sender.AGENT -> "assistant"
            Sender.TOOL -> "tool"
            Sender.SYSTEM -> "user"
        }
        return ResponseItem.Message(role, text)
    }

    private fun getPolicyConfig(): String {
        return try {
            context.assets.open("agent/policy.yml")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            log.warn("Error reading policy text file: {}", e.message)
            ""
        }
    }

    private fun getFewShotsConfig(): List<Map<String, Any>> {
        return try {
            val jsonString = context.assets.open("agent/planner_fewshots.json")
                .bufferedReader()
                .use { it.readText() }

            val jsonObjects =
                json.decodeFromString(ListSerializer(JsonObject.serializer()), jsonString)

            jsonObjects.map { it.toStandardMap() }
        } catch (e: Exception) {
            log.warn("Error parsing few-shots JSON: {}", e.message)
            emptyList()
        }
    }
}

private fun JsonObject.toStandardMap(): Map<String, Any> {
    return this.mapValues { (_, jsonElement) ->
        jsonElement.toAny()
    }
}

private fun JsonElement.toAny(): Any {
    return when (this) {
        is JsonObject -> this.toStandardMap()
        is JsonArray -> this.map { it.toAny() }
        is JsonPrimitive -> {
            if (this.isString) {
                this.content
            } else {
                this.contentOrNull ?: this.booleanOrNull ?: this.longOrNull ?: this.doubleOrNull
                ?: Unit
            }
        }
    }
}
