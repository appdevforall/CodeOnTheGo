package com.itsaky.androidide.agent.repository

import android.content.Context
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.agent.data.Content
import com.itsaky.androidide.agent.data.GeminiRequest
import com.itsaky.androidide.agent.data.GeminiResponse
import com.itsaky.androidide.agent.data.Part
import com.itsaky.androidide.agent.data.SimplerToolCall
import com.itsaky.androidide.agent.data.ToolCall
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.viewmodel.ExecutorAgent
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.models.PlanStep
import com.itsaky.androidide.models.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

// The class no longer needs FirebaseAI. It now handles its own HTTP calls.
class GeminiRepositoryImpl(
    private val ideApi: IDEApiFacade,
    private val context: Context
) : GeminiRepository {

    // --- HTTP Client and API Configuration ---
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- Model Names ---
    private val plannerModel = "gemini-1.5-pro"
    private val criticModel = "gemini-1.5-flash"
    private val codeGenModel = "gemini-1.5-pro"

    private val toolTracker = ToolExecutionTracker()

    // Callbacks remain the same
    override var onToolCall: ((ToolCall) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        return runAgentWorkflow(prompt, history)
    }

    /**
     * A generic, reusable function to call the Gemini REST API.
     */
    private suspend fun callGeminiApi(
        model: String,
        prompt: String,
        systemInstruction: String? = null,
        // Optional: for multi-turn conversations
        history: List<Content> = emptyList()
    ): Result<String> {
        // --- START: MODIFIED SECTION ---

        // 1. Fetch the API key from secure storage using the context.
        val apiKey = EncryptedPrefs.getGeminiApiKey(context)

        // 2. Check if the API key is available. If not, fail gracefully.
        if (apiKey.isNullOrBlank()) {
            val errorMessage = "Gemini API Key not found. Please set it in the AI Settings."
            log.error(errorMessage)
            return Result.failure(Exception(errorMessage))
        }

        // --- END: MODIFIED SECTION ---
        return withContext(Dispatchers.IO) {
            try {
                val contents = mutableListOf<Content>()
                contents.addAll(history)
                contents.add(Content(parts = listOf(Part(text = prompt)), role = "user"))

                val requestBodyJson = json.encodeToString(
                    GeminiRequest.serializer(),
                    GeminiRequest(
                        contents = contents,
                        systemInstruction = systemInstruction?.let { Content(parts = listOf(Part(it))) }
                    )
                )

                val requestBody = requestBodyJson.toRequestBody("application/json".toMediaType())
                val url =
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder().url(url).post(requestBody).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("API Error: ${response.code} ${response.body?.string()}")
                }

                val responseBodyString = response.body?.string()
                    ?: throw Exception("Empty response body")

                val geminiResponse = json.decodeFromString<GeminiResponse>(responseBodyString)
                val responseText =
                    geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        ?: ""
                val cleanedJson = responseText
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                Result.success(cleanedJson)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun executeTool(functionCall: ToolCall): ToolResult {
        return when (functionCall.name) {
            "create_file" -> {
                val path = (functionCall.args?.get("path") as? JsonPrimitive)?.content?.trim()
                    ?.replace("\"", "") ?: ""
                val content = (functionCall.args?.get("content") as? JsonPrimitive)?.content ?: ""
                ideApi.createFile(path = path, content = content)
            }

            "update_file" -> {
                val path = (functionCall.args?.get("path") as? JsonPrimitive)?.content?.trim()
                    ?.replace("\"", "") ?: ""
                val content = (functionCall.args?.get("content") as? JsonPrimitive)?.content ?: ""
                ideApi.updateFile(path, content)
            }

            "read_file" -> {
                ideApi.readFile(
                    path = functionCall.args?.get("path").toString().trim().replace("\"", "")
                )
            }

            "list_files" -> {
                ideApi.listFiles(
                    path = functionCall.args?.get("path").toString().trim().replace("\"", ""),
                    recursive = functionCall.args?.get("recursive")?.toString().toBoolean()
                )
            }

            "run_app" -> {
                ideApi.runApp()
            }
            "add_dependency" -> {
                val dependencyString =
                    (functionCall.args?.get("dependency_string") as? JsonPrimitive)?.content ?: ""
                val buildFilePath =
                    (functionCall.args?.get("build_file_path") as? JsonPrimitive)?.content?.trim()
                        ?.replace("\"", "") ?: ""
                if (dependencyString.isEmpty()) {
                    ToolResult.failure("The 'dependency_string' parameter is required.")
                } else {
                    ideApi.addDependency(
                        dependencyString = dependencyString,
                        buildFilePath = buildFilePath
                    )
                }
            }

            "get_build_output" -> {
                ideApi.getBuildOutput()
            }

            "add_string_resource" -> {
                val name = (functionCall.args?.get("name") as? JsonPrimitive)?.content ?: ""
                val value = (functionCall.args?.get("value") as? JsonPrimitive)?.content ?: ""
                if (name.isEmpty() || value.isEmpty()) {
                    ToolResult.failure("Both 'name' and 'value' parameters are required for add_string_resource.")
                } else {
                    ideApi.addStringResource(name, value)
                }
            }

            "ask_user" -> {
                val question = functionCall.args
                    ?.let { (it?.get("question") as? JsonPrimitive)?.content }
                    ?: "..."

                val optionsJson = functionCall.args?.get("options")
                val options = optionsJson?.let {
                    Json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
                } ?: listOf()

                onAskUser?.invoke(question, options)

                ToolResult(
                    success = true,
                    message = "The user has been asked the question. Await their response in the next turn."
                )
            }


            else -> {
                ToolResult.failure("Unknown tool: ${functionCall.name}")
            }
        }
    }

    // --- Agent Logic Methods (Now using callGeminiApi) ---

    private suspend fun createPlan(userInput: String, toolDeclarations: String): List<PlanStep> {
        val systemInstruction = """
        You are a meticulous software architect...
        Your sole output is a JSON array of the tools to be called.
        Here is the list of available tools you can use in your plan:
        $toolDeclarations
        """.trimIndent()

        val prompt = "User Request: \"$userInput\"\n"
        val result = callGeminiApi(plannerModel, prompt, systemInstruction)

//        val jsonString = """
//            [
//              {
//                "name": "run_app",
//                "parameters": []
//              }
//            ]
//        """.trimIndent()
        return result.fold(
            onSuccess = { jsonString ->
                try {
                    // Step 1: Parse into the new, simpler ToolCall class
                    val toolCalls = json.decodeFromString(
                        ListSerializer(SimplerToolCall.serializer()),
                        jsonString
                    )

                    // Step 2: Convert the simple ToolCalls into the complex PlanSteps your app uses
                    toolCalls.mapIndexed { index, toolCall ->
                        // --- START: CORRECTED LOGIC ---
                        val paramsMap: Map<String, JsonElement> =
                            if (toolCall.parameters is JsonObject) {
                                // If the element is a JSON object, decode it into a Map
                                json.decodeFromJsonElement(
                                    MapSerializer(String.serializer(), JsonElement.serializer()),
                                    toolCall.parameters
                                )
                            } else {
                                // Otherwise (e.g., it's a JsonArray []), default to an empty map
                                emptyMap()
                            }
                        // --- END: CORRECTED LOGIC ---

                        PlanStep(
                            stepId = index + 1,
                            objective = "Execute tool: ${toolCall.name}",
                            toolToUse = toolCall.name,
                            parameters = paramsMap, // Use the safely created map
                            expectedOutputFormat = "Default"
                        )
                    }
                } catch (e: Exception) {
                    log.error(
                        "Error parsing or converting plan JSON: ${e.message}\nContent: $jsonString",
                        e
                    )
                    emptyList()
                }
            },
            onFailure = {
                log.error("Failed to create plan from API", it)
                emptyList()
            }
        )
    }

    private suspend fun evaluateResult(result: StepResult, originalStep: PlanStep): Boolean {
        val systemInstruction =
            "You are a quality assurance agent... Your only output should be 'true' or 'false'."

        val truncatedOutput =
            if (result.output.length > 1500) result.output.take(1500) + "..." else result.output

        val prompt = """
            **Step's Objective:** "${originalStep.objective}"
            **Tool Chosen:** `${originalStep.toolToUse}`
            **Actual Output from the Tool:** ```${truncatedOutput}```
            Based on this, respond with only "true" if the step was successful, or "false" if it was not.
            """.trimIndent()

        val apiResult = callGeminiApi(criticModel, prompt, systemInstruction)
        return apiResult.getOrNull()?.trim()?.equals("true", ignoreCase = true) ?: false
    }

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
        val systemInstruction =
            "You are an expert code generation assistant... Your response must be only the code itself."

        val contextPrompt = """
            The user is editing '$fileName' at '$fileRelativePath'.
            File content: ```$fileContent```
            User Request: "$prompt"
            """.trimIndent()

        val result = callGeminiApi(codeGenModel, contextPrompt, systemInstruction)
        return result.getOrThrow() // Throw exception on failure
    }

    // The rest of your file (runAgentWorkflow, executeTool, etc.) can remain largely the same,
    // as it now depends on the rewritten createPlan and evaluateResult methods.

    // ... (paste the rest of your `GeminiRepositoryImpl` code here, from `runAgentWorkflow` downwards)
    // IMPORTANT: The `Google Search` tool is part of the Firebase SDK and not available in the public
    // Gemini REST API. You will need to remove that case from your `executeTool` function or use a
    // separate API (like Google's Custom Search API) to implement it.
    override fun getPartialReport(): String {
        return toolTracker.generatePartialReport()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GeminiRepositoryImpl::class.java)
    }


    private suspend fun runAgentWorkflow(
        userInput: String,
        history: List<ChatMessage>
    ): AgentResponse {
        log.debug(userInput)
        onStateUpdate?.invoke(AgentState.Processing("Initializing Agent Workflow..."))

        val executor = ExecutorAgent(this)

        val initialPrompt = buildInitialPromptWithHistory(userInput, history)

        var currentPlan: List<PlanStep>
        val completedSteps = mutableListOf<StepResult>()
        var lastFailureReason: String? = null
        var remainingRetries = 3

        val workingContext = StringBuilder()
        // Use the full initial prompt in the context
        workingContext.append("User's initial request & context:\n\"$initialPrompt\"\n\n")

        onStateUpdate?.invoke(AgentState.Processing("👨‍🎨 Creating an initial plan..."))
        // Pass the full initial prompt to the planner
        currentPlan =
            createPlan(initialPrompt, GeminiTools.getToolDeclarationsAsJson())

        if (currentPlan.isEmpty()) {
            val failureMessage =
                "I couldn't create a valid plan. Please try rephrasing your request."
            onStateUpdate?.invoke(AgentState.Idle)
            return AgentResponse(text = failureMessage, report = "")
        }

        var currentStepIndex = 0
        while (currentStepIndex < currentPlan.size && remainingRetries > 0) {
            val step = currentPlan[currentStepIndex]
            onStateUpdate?.invoke(AgentState.Processing("🚀 Executing step ${step.stepId}: ${step.objective}"))

            val result = executor.executeStep(step)
            onToolMessage?.invoke("Tool `${step.toolToUse}` output: ${result.output}")

            var wasStepSuccessful = result.wasSuccessful
            log.debug("result: {}", result)
            log.debug("wasStepSuccessful: $wasStepSuccessful")
            if (wasStepSuccessful) {
                onStateUpdate?.invoke(AgentState.Processing("🧐 Critiquing result..."))
                wasStepSuccessful = evaluateResult(result, step)

                log.debug("critic: ___v")
                log.debug("wasStepSuccessful: $wasStepSuccessful")
                if (!wasStepSuccessful) {
                    lastFailureReason =
                        "Critique failed: The result '${result.output}' did not meet the objective '${step.objective}'."
                    log.debug("lastFailureReason: {}", lastFailureReason)
                }
            } else {
                lastFailureReason = "Execution failed: ${result.error}"
            }

            if (wasStepSuccessful) {
                onStateUpdate?.invoke(AgentState.Processing("✅ Step successful."))
                completedSteps.add(result)

                // --- NEW: Add the successful step's result to the working context ---
                workingContext.append("Step ${step.stepId} (${step.toolToUse}) was successful.\n")
                workingContext.append("Objective: ${step.objective}\n")
                workingContext.append("Result: ${result.output}\n\n")

                currentStepIndex++
                lastFailureReason = null
            } else {
                // --- FAILURE & RE-PLANNING LOGIC ---
                remainingRetries--
                onStateUpdate?.invoke(AgentState.Processing("⚠️ Step failed. Reason: $lastFailureReason. Attempting to re-plan..."))

                // --- NEW: Construct a much richer re-plan prompt with the full context ---
                val rePlanPrompt = """
            My original goal was: "$userInput"

            Here is a summary of the steps I have already completed and their results:
            --- START CONTEXT ---
            $workingContext
            --- END CONTEXT ---

            The last step I tried to execute failed. Here is the reason for the failure:
            "$lastFailureReason"

            Based on all of this context, create a new, revised plan to achieve the original goal. Do not repeat steps that have already provided useful information.
            """.trimIndent()

                currentPlan =
                    createPlan(rePlanPrompt, GeminiTools.getToolDeclarationsAsJson())
                currentStepIndex = 0

                if (currentPlan.isEmpty()) {
                    val failureMessage =
                        "I failed to recover from an error and could not create a new plan. Aborting workflow."
                    onStateUpdate?.invoke(AgentState.Idle)
                    return AgentResponse(
                        text = failureMessage,
                        report = "Completed ${completedSteps.size} steps before failure."
                    )
                }
            }
        }
        val finalMessage: String = if (remainingRetries <= 0) {
            "❌ Workflow failed. I was unable to fix the error after multiple attempts. Last error: $lastFailureReason"
        } else {
            "✅ Workflow completed successfully! All steps were executed and verified."
        }

        onStateUpdate?.invoke(AgentState.Idle)
        return AgentResponse(
            text = finalMessage,
            report = "Completed ${completedSteps.size} steps."
        )
    }

    private fun buildInitialPromptWithHistory(
        userInput: String,
        history: List<ChatMessage>
    ): String {
        if (history.isEmpty()) {
            return userInput
        }

        val historyString = history.joinToString(separator = "\n") {
            // Format each message based on its sender
            when (it.sender) {
                ChatMessage.Sender.USER -> "Previous User: ${it.text}"
                ChatMessage.Sender.AGENT -> "Previous Agent: ${it.text}"
                else -> "" // Ignore system or tool messages for this context
            }
        }

        return """
    Here is the recent conversation history for context:
    --- START HISTORY ---
    $historyString
    --- END HISTORY ---

    Now, please act on my latest request: "$userInput"
    """.trimIndent()
    }

}