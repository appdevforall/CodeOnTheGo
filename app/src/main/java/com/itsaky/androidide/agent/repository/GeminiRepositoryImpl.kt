//package com.itsaky.androidide.agent.repository
//
//import android.content.Context
//import com.google.genai.types.Content
//import com.google.genai.types.Part
//import com.itsaky.androidide.agent.ToolExecutionTracker
//import com.itsaky.androidide.agent.data.SimplerToolCall
//import com.itsaky.androidide.agent.data.ToolCall
//import com.itsaky.androidide.agent.fragments.EncryptedPrefs
//import com.itsaky.androidide.agent.model.ToolResult
//import com.itsaky.androidide.agent.repository.GeminiTools.allMyTools
//import com.itsaky.androidide.agent.viewmodel.ExecutorAgent
//import com.itsaky.androidide.api.IDEApiFacade
//import com.itsaky.androidide.models.AgentState
//import com.itsaky.androidide.models.ChatMessage
//import com.itsaky.androidide.models.PlanStep
//import com.itsaky.androidide.models.StepResult
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.withContext
//import kotlinx.serialization.ExperimentalSerializationApi
//import kotlinx.serialization.builtins.ListSerializer
//import kotlinx.serialization.builtins.MapSerializer
//import kotlinx.serialization.builtins.serializer
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.json.JsonElement
//import kotlinx.serialization.json.JsonObject
//import kotlinx.serialization.json.JsonPrimitive
//import org.slf4j.LoggerFactory
//import java.util.concurrent.TimeoutException
//
//class GeminiRepositoryImpl(
//    private val ideApi: IDEApiFacade,
//    private val context: Context
//) : GeminiRepository {
//
//    @OptIn(ExperimentalSerializationApi::class)
//    private val json = Json {
//        ignoreUnknownKeys = true
//        isLenient = true
//        allowTrailingComma = true
//    }
//
//    // apply a UI switch
//    private val plannerModel = "gemini-2.5-flash"
//    private val criticModel = "gemini-2.5-flash-lite"
//    private val codeGenModel = "gemini-2.5-flash"
//
//    private val toolTracker = ToolExecutionTracker()
//
//    override var onToolCall: ((ToolCall) -> Unit)? = null
//    override var onToolMessage: ((String) -> Unit)? = null
//    override var onStateUpdate: ((AgentState) -> Unit)? = null
//    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null
//
//    val geminiClient: GeminiClient by lazy {
//        val apiKey = EncryptedPrefs.getGeminiApiKey(context)
//        if (apiKey.isNullOrBlank()) {
//            val errorMessage = "Gemini API Key not found. Please set it in the AI Settings."
//            log.error(errorMessage)
//            throw Exception(errorMessage)
//        }
//        GeminiClient(apiKey)
//    }
//
//    override suspend fun generateASimpleResponse(
//        prompt: String,
//        history: List<ChatMessage>
//    ): AgentResponse {
//        return runAgentWorkflow(prompt, history)
//    }
//
//    private fun loadPlannerFewShotSamplesFromFile(): List<Content> {
//        try {
//            // Reads the file content as a single string
//            val jsonString = context.assets.open("agent/planner_fewshots.json")
//                .bufferedReader()
//                .use { it.readText() }
//
//            // Parses the string into the List<Content> object that the agent needs
//            val decodeFromString =
//                json.decodeFromString<List<com.itsaky.androidide.agent.data.Content>>(
//                    ListSerializer(
//                        com.itsaky.androidide.agent.data.Content.serializer()
//                    ), jsonString
//                )
//            return decodeFromString.map { it.toContent() }
//
//        } catch (e: Exception) {
//            log.error("Failed to load or parse few-shot samples from assets.", e)
//            return emptyList()
//        }
//    }
//
//    // And your getPlannerFewShotSamples() function remains simple:
//    private fun getPlannerFewShotSamples(): List<Content> {
//        return loadPlannerFewShotSamplesFromFile()
//    }
//
//    private suspend fun callGeminiApi(
//        model: String,
//        prompt: String,
//        systemInstruction: String? = null,
//        history: List<Content> = emptyList()
//    ): Result<String> {
//        return withContext(Dispatchers.IO) {
//            try {
//                val contents = mutableListOf<Content>()
//                contents.addAll(history)
//                contents.add(
//                    Content.builder().parts(listOf(Part.builder().text(prompt).build()))
//                        .role("User").build()
//                )
//                val response =
//                    geminiClient.generateContent(history, allMyTools)
//                Result.success(response)
//            } catch (e: Exception) {
//                Result.failure(e)
//            }
//        }
//    }
//
//    suspend fun executeTool(functionCall: ToolCall): ToolResult {
//        return when (functionCall.name) {
//            "create_file" -> {
//                val path = (functionCall.args?.get("path") as? JsonPrimitive)?.content?.trim()
//                    ?.replace("\"", "") ?: ""
//                val content = (functionCall.args?.get("content") as? JsonPrimitive)?.content ?: ""
//                log.debug("Creating file '{}' with content:\n{}", path, content)
//                ideApi.createFile(path = path, content = content)
//            }
//
//            "update_file" -> {
//                val path = (functionCall.args?.get("path") as? JsonPrimitive)?.content?.trim()
//                    ?.replace("\"", "") ?: ""
//                val content = (functionCall.args?.get("content") as? JsonPrimitive)?.content ?: ""
//                log.debug("Updating file '{}' with content:\n{}", path, content)
//                ideApi.updateFile(path, content)
//            }
//
//            "read_file" -> {
//                ideApi.readFile(
//                    path = functionCall.args?.get("path").toString().trim().replace("\"", "")
//                )
//            }
//
//            "list_files" -> {
//                ideApi.listFiles(
//                    path = functionCall.args?.get("path").toString().trim().replace("\"", ""),
//                    recursive = functionCall.args?.get("recursive")?.toString().toBoolean()
//                )
//            }
//
//            "run_app" -> {
//                ideApi.runApp()
//            }
//
//            "add_dependency" -> {
//                val dependencyCoordinates =
//                    (functionCall.args?.get("dependency") as? JsonPrimitive)?.content ?: ""
//
//                val buildFilePath =
//                    (functionCall.args?.get("build_file_path") as? JsonPrimitive)?.content?.trim()
//                        ?.replace("\"", "")
//
//                if (dependencyCoordinates.isEmpty()) {
//                    ToolResult.failure("The 'dependency' parameter is required.")
//                } else if (buildFilePath.isNullOrEmpty()) {
//                    ToolResult.failure("The 'build_file_path' parameter is required. You must find the file path first using 'list_files'.")
//                } else {
//                    // This logic correctly builds the string for .kts or .gradle files
//                    val dependencyString = if (buildFilePath.endsWith(".kts")) {
//                        "implementation(\"$dependencyCoordinates\")"
//                    } else {
//                        "implementation '$dependencyCoordinates'"
//                    }
//                    ideApi.addDependency(
//                        dependencyString = dependencyString,
//                        buildFilePath = buildFilePath
//                    )
//                }
//            }
//
//            "get_build_output" -> {
//                ideApi.getBuildOutput()
//            }
//
//            "trigger_gradle_sync" -> {
//                ideApi.triggerGradleSync()
//            }
//
//            "add_string_resource" -> {
//                val name = (functionCall.args?.get("name") as? JsonPrimitive)?.content ?: ""
//                val value = (functionCall.args?.get("value") as? JsonPrimitive)?.content ?: ""
//                if (name.isEmpty() || value.isEmpty()) {
//                    ToolResult.failure("Both 'name' and 'value' parameters are required for add_string_resource.")
//                } else {
//                    ideApi.addStringResource(name, value)
//                }
//            }
//
//            "ask_user" -> {
//                val question = functionCall.args
//                    ?.let { (it["question"] as? JsonPrimitive)?.content }
//                    ?: "..."
//
//                val optionsJson = functionCall.args?.get("options")
//                val options = optionsJson?.let {
//                    Json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
//                } ?: listOf()
//
//                onAskUser?.invoke(question, options)
//
//                ToolResult(
//                    success = true,
//                    message = "The user has been asked the question. Await their response in the next turn."
//                )
//            }
//
//            "delete_file" -> {
//                val path = (functionCall.args?.get("path") as? JsonPrimitive)?.content?.trim()
//                    ?.replace("\"", "") ?: ""
//                log.debug("Deleting file '{}'", path)
//                ideApi.deleteFile(path)
//            }
//
//
//            else -> {
//                ToolResult.failure("Unknown tool: ${functionCall.name}")
//            }
//        }
//    }
//
//    private suspend fun createPlan(
//        userInput: String,
//        history: List<Content>
//    ): List<PlanStep> {
//        val systemInstruction = """
//You are an expert Android developer agent specializing in both Views and Jetpack Compose. Your goal is to fulfill user requests by interacting with an IDE.
//
//**Your Core Workflow:**
//1.  **CRITICAL FIRST STEP: IDENTIFY PROJECT ARCHITECTURE.** Your first action **MUST** be to `read_file` on the `app/build.gradle.kts` (or `build.gradle`). Look for two key things:
//    - The language: `.kts` for Kotlin, `.gradle` for Groovy.
//    - The UI Toolkit: Check for `buildFeatures { compose = true }`. If this is present, it is a **Jetpack Compose project**. If it's absent, it's a traditional **Views project**.
//    1.1. Before doing anything else, you **MUST** use `list_files` on the `app` directory to identify the build script. Look for either `build.gradle` (for Groovy) or `build.gradle.kts` (for Kotlin). All subsequent actions depend on this file type. Do not assume the language.
//2.  **ANALYZE:** After identifying the build script, use `read_file` to understand its content, syntax, and current state.
//    2.1.  **ADAPT YOUR STRATEGY:**
//      - **For Compose Projects:**
//        - Do NOT use `setContentView` or create/update XML layout files. Your work is in `.kt` files with `@Composable` functions.
//        - **CRITICAL:** If you are rewriting the `MainActivity.kt` to be fully Compose, you **MUST delete the entire `app/src/main/res/layout` directory** using the `delete_file` tool to prevent resource conflicts. If the directory doesn't exist, that is fine.
//      - **For Views Projects:**
//        - You will work with both XML layout files in `res/layout/` and Activity/Fragment files.
//4.  **ACT & VERIFY (The Kotlin Rule):**
//    - If you are writing Kotlin code (creating a `.kt` file), you **MUST** ensure the `org.jetbrains.kotlin.android` plugin is present in the build script you identified in step 1.
//    - If the plugin is missing, your **ONLY** priority is to use `update_file` to add it.
//    - After making build script changes, you **MUST** call `trigger_gradle_sync`.
//5.  **EXECUTE:** After determining the architecture, proceed with the user's request using the correct tools and code for that architecture.
//6.  **VERIFY:** After code changes, always use `trigger_gradle_sync` and `run_app` to verify your changes.
//
//**CRITICAL: Error Handling**
//- If a build fails with "Unresolved reference: AppCompatActivity", you have likely written Views code in a Compose project. You **MUST** correct the `.kt` file to use Composable functions instead. Do not try to fix this by adding dependencies.
//- If you see an error about a file not being found (e.g., trying to update `build.gradle.kts` when only `build.gradle` exists), you have misidentified the project type. Re-start your process by using `list_files` to find the correct build file.
//- If a build fails due to a `.java` vs `.kt` file conflict, your first step is to **delete the conflicting file**.
//
//-   Output a JSON array of the next tool calls.
//-   If you have enough information, output an empty JSON array `[]`.
//""".trimIndent()
//
//        val fullHistory = mutableListOf<Content>()
//        fullHistory.addAll(getPlannerFewShotSamples())
//        fullHistory.addAll(history) // Then add the live conversation history
//
//        val result =
//            callGeminiApi(plannerModel, userInput, systemInstruction, history = fullHistory)
//
//        return result.fold(
//            onSuccess = { jsonString ->
//                log.debug("Raw plan is: {}", jsonString)
//                try {
//                    val sanitizedJsonString = jsonString.replace("\\$", "\\\\$")
//
//                    val toolCalls = json.decodeFromString(
//                        ListSerializer(SimplerToolCall.serializer()),
//                        jsonString
//                    )
//
//                    toolCalls.mapIndexed { index, toolCall ->
//                        val paramsMap: Map<String, JsonElement> =
//                            if (toolCall.parameters is JsonObject) {
//                                json.decodeFromJsonElement(
//                                    MapSerializer(String.serializer(), JsonElement.serializer()),
//                                    toolCall.parameters
//                                )
//                            } else {
//                                emptyMap()
//                            }
//
//                        PlanStep(
//                            stepId = index + 1,
//                            objective = "Execute tool: ${toolCall.name}",
//                            toolToUse = toolCall.name,
//                            parameters = paramsMap,
//                            expectedOutputFormat = "Default"
//                        )
//                    }
//                } catch (e: Exception) {
//                    log.error(
//                        "Error parsing or converting plan JSON: ${e.message}\nContent: $jsonString",
//                        e
//                    )
//                    emptyList()
//                }
//            },
//            onFailure = {
//                log.error("Failed to create plan from API", it)
//                emptyList()
//            }
//        )
//    }
//
//    private suspend fun evaluateResult(result: StepResult, originalStep: PlanStep): Boolean {
//        val systemInstruction =
//            "You are a quality assurance agent... Your only output should be 'true' or 'false'."
//
//        val truncatedOutput =
//            if (result.output.length > 1500) result.output.take(1500) + "..." else result.output
//
//        val prompt = """
//            **Step's Objective:** "${originalStep.objective}"
//            **Tool Chosen:** `${originalStep.toolToUse}`
//            **Actual Output from the Tool:** ```${truncatedOutput}```
//            Based on this, respond with only "true" if the step was successful, or "false" if it was not.
//            """.trimIndent()
//
//        val apiResult = callGeminiApi(criticModel, prompt, systemInstruction)
//        return apiResult.getOrNull()?.trim()?.equals("true", ignoreCase = true) ?: false
//    }
//
//    override suspend fun generateCode(
//        prompt: String,
//        fileContent: String,
//        fileName: String,
//        fileRelativePath: String
//    ): String {
//        val systemInstruction =
//            "You are an expert code generation assistant... Your response must be only the code itself."
//
//        val contextPrompt = """
//            The user is editing '$fileName' at '$fileRelativePath'.
//            File content: ```$fileContent```
//            User Request: "$prompt"
//            """.trimIndent()
//
//        val result = callGeminiApi(codeGenModel, contextPrompt, systemInstruction)
//        return result.getOrThrow()
//    }
//
//    override fun getPartialReport(): String {
//        return toolTracker.generatePartialReport()
//    }
//
//    companion object {
//        private val log = LoggerFactory.getLogger(GeminiRepositoryImpl::class.java)
//    }
//
//
//    private suspend fun runAgentWorkflow(
//        userInput: String,
//        history: List<ChatMessage>
//    ): AgentResponse {
//        log.debug(userInput)
//        onStateUpdate?.invoke(AgentState.Processing("Initializing Agent Workflow..."))
//
//        val executor = ExecutorAgent(this)
//
//        val chatHistoryAsContent = history.map {
//            Content.builder().parts(listOf(Part.builder().text(it.text).build()))
//                .role(if (it.sender == ChatMessage.Sender.USER) "user" else "model").build()
//        }
//
//        var loopCount = 0
//        val maxLoops = 15
//
//        val conversationHistory = mutableListOf<Content>()
//        conversationHistory.addAll(chatHistoryAsContent)
//
//        var currentPrompt = userInput
//
//        while (loopCount < maxLoops) {
//            onStateUpdate?.invoke(AgentState.Processing("🤔 Thinking about the next step..."))
//            log.debug("Model input will be: {}", currentPrompt)
//            val currentPlan =
//                createPlan(
//                    currentPrompt,
//
//                    conversationHistory
//                )
//
//            currentPrompt = "Based on the previous tool output, decide the next action."
//
//            if (currentPlan.isEmpty()) {
//                val finalPrompt =
//                    "Based on the actions taken, provide a final, concise summary to the user."
//                val summaryResult =
//                    callGeminiApi(plannerModel, finalPrompt, null, conversationHistory)
//
//                val finalMessage = summaryResult.getOrDefault("✅ Workflow completed successfully!")
//                onStateUpdate?.invoke(AgentState.Idle)
//                return AgentResponse(text = finalMessage, report = "Completed workflow.")
//            }
//
//            val planAsJson = json.encodeToString(
//                ListSerializer(SimplerToolCall.serializer()),
//                currentPlan.map { it.toSimplerToolCall() })
//            log.debug("Model generated plan: {}", planAsJson)
//            conversationHistory.add(
//                Content.builder().parts(listOf(Part.builder().text(planAsJson).build()))
//                    .role("model").build()
//            )
//
//            for (step in currentPlan) {
//                onStateUpdate?.invoke(AgentState.Processing("🚀 Executing: ${step.toolToUse}"))
//                val result = executor.executeStep(step)
//                onToolMessage?.invoke("Tool `${step.toolToUse}` output: ${result.output}")
//
//                // Add the initial tool call result to history
//                addToolResultToHistory(result, conversationHistory)
//
//                // *** NEW POLLING LOGIC ***
//                // If the step was to trigger a sync, start polling for the result.
//                if (step.toolToUse == "trigger_gradle_sync" && result.wasSuccessful) {
//                    onStateUpdate?.invoke(AgentState.Processing(" Gradle Sync started, waiting for completion..."))
//                    try {
//                        val finalBuildResult = pollForBuildResult(step)
//                        onToolMessage?.invoke("Final Build Output: ${finalBuildResult.output}")
//                        // Add the definitive build result to history for the model to see
//                        addToolResultToHistory(finalBuildResult, conversationHistory)
//                    } catch (e: TimeoutException) {
//                        onToolMessage?.invoke("Polling timed out: ${e.message}")
//                        val timeoutResult =
//                            StepResult(
//                                stepId = step.stepId,
//                                wasSuccessful = false,
//                                output = "Gradle sync timed out after 2 minutes.",
//                                error = "Gradle sync timed out after 2 minutes."
//                            )
//                        addToolResultToHistory(timeoutResult, conversationHistory)
//                    }
//                }
//            }
//
//            loopCount++
//        }
//
//
//        onStateUpdate?.invoke(AgentState.Idle)
//        return AgentResponse(
//            text = "❌ Workflow failed. Reached maximum number of steps.",
//            report = "Exceeded step limit."
//        )
//    }
//
//    private suspend fun pollForBuildResult(step: PlanStep): StepResult {
//        val maxAttempts = 24 // 24 attempts * 5 seconds = 120 seconds (2 minutes)
//        var attempt = 0
//
//        while (attempt < maxAttempts) {
//            delay(5000) // Wait for 5 seconds between checks
//            log.debug("Polling build output, attempt ${attempt + 1}/$maxAttempts")
//            onToolMessage?.invoke("Checking build status...")
//
//            val statusResult = ideApi.isBuildRunning()
//
//            if (statusResult.success) {
//                val isRunning = statusResult.data.toBoolean()
//                if (!isRunning) {
//                    log.info("Polling successful: IDE reports build is no longer running.")
//                    // Now that the build is done, get the final output log for the model.
//                    onToolMessage?.invoke("Sync complete. Fetching final build log...")
//                    return StepResult(
//                        stepId = step.stepId,
//                        wasSuccessful = true,
//                        output = ideApi.getBuildOutput().message
//                    )
//                }
//            }
//            attempt++
//        }
//
//        throw TimeoutException("Timed out waiting for Gradle sync to complete.")
//    }
//
//    private fun addToolResultToHistory(
//        result: StepResult,
//        conversationHistory: MutableList<Content>
//    ) {
//        val sanitizedOutput = result.output
//            .replace("\\", "\\\\")
//            .replace("\"", "\\\"")
//            .replace("\n", " ")
//
//        val sanitizedError = result.error
//            ?.replace("\\", "\\\\")
//            ?.replace("\"", "\\\"")
//            ?.replace("\n", " ")
//            ?: ""
//
//        val toolResultString = if (result.wasSuccessful) {
//            "TOOL_RESULT:\nToolResult(success=true, message='Tool executed successfully', data='$sanitizedOutput')"
//        } else {
//            "TOOL_RESULT:\nToolResult(success=false, message='Tool execution failed', error='$sanitizedError')"
//        }
//        conversationHistory.add(
//            Content.builder().parts(listOf(Part.builder().text(toolResultString).build()))
//                .role("user").build()
//        )
//    }
//
//}
//
//private fun com.itsaky.androidide.agent.data.Content.toContent(): Content {
//    val parts = this.parts.map { part -> Part.builder().text(part.text).build() }
//    return Content.builder().parts(parts)
//        .role(this.role).build()
//}
