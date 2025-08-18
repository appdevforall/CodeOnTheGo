package com.itsaky.androidide.data.repository

import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.itsaky.androidide.agent.ToolExecutionTracker
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.models.AgentState
import com.itsaky.androidide.models.ChatMessage
import com.itsaky.androidide.models.StepResult
import com.itsaky.androidide.viewmodel.CriticAgent
import com.itsaky.androidide.viewmodel.ExecutorAgent
import com.itsaky.androidide.viewmodel.OrchestratorAgent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class GeminiRepositoryImpl(
    firebaseAI: FirebaseAI,
    private val ideApi: IDEApiFacade,
) : GeminiRepository {

    private val toolTracker = ToolExecutionTracker()

    private val modelName = "gemini-2.5-pro"

    // --- AGENT MODELS DEFINITION ---

    // 1. The Planner: A meticulous project manager. Its only job is to create a detailed, structured plan.
// It has no tools, ensuring it focuses solely on planning.
    private val vertexAiModelForPlanning: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName,
        systemInstruction = content(role = "system") {
            text(
                """ 
            You are a meticulous software architect and project planner. Your purpose is to decompose a complex user request into a detailed, step-by-step plan.
            You must respond with a valid JSON array of objects, where each object represents a single, atomic step in the plan.
            Each step must include a unique 'stepId', a clear 'objective', the specific 'toolToUse' from the provided list, a 'parameters' object, and a description of the 'expectedOutputFormat'.
            Do not execute anything. Your sole output is the JSON plan.

            Here is the list of available tools you can use in your plan:
            ${GeminiTools.getToolDeclarationsAsJson()}
            """.trimIndent()
            )
        },
        // Enforce JSON output for reliable parsing
        // FIX: Use the builder function instead of the private constructor
        generationConfig = generationConfig {
            responseMimeType = "application/json"
        }
    )

    // 3. The Critic: A detail-oriented quality assurance analyst.
    // Its job is to verify if the output of a step meets the original objective.
    private val vertexAiModelForCritique: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName, // A powerful model is good for nuanced critique
        systemInstruction = content(role = "system") {
            text("You are a quality assurance agent. Your task is to evaluate the result of a tool execution against its intended objective. You must be strict and objective. Your only output should be 'true' if the objective was met, or 'false' if it was not.")
        }
    )
    private val searchModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName,
        systemInstruction = content(role = "system") {
            text("You are a helpful assistant that answers questions using web searches.")
        },
        tools = listOf(Tool.googleSearch())
    )

    private val codeGenerationModel: GenerativeModel = firebaseAI.generativeModel(
        modelName = modelName,
        systemInstruction = content(role = "system") {
            text("You are an expert code generation assistant. You only respond with raw code based on the user's prompt. Do not add any explanations, comments, or markdown formatting like ```. Your response must be only the code itself.")
        }
    )

    override var onToolCall: ((FunctionCallPart) -> Unit)? = null
    override var onToolMessage: ((String) -> Unit)? = null
    override var onStateUpdate: ((AgentState) -> Unit)? = null
    override var onAskUser: ((question: String, options: List<String>) -> Unit)? = null

    override suspend fun generateASimpleResponse(
        prompt: String,
        history: List<ChatMessage>
    ): AgentResponse {
        val routerPrompt = """
            Analyze the following user prompt and determine the primary intent.
            Respond with a single word: PLAN, SEARCH, or OTHER.
            - Use PLAN if the request is complex and involves file manipulation, building the project, or multi-step interactions with the IDE.
            - Use SEARCH if the request requires up-to-date information from the internet or general knowledge.
            - Use OTHER for conversational chat.

            User Prompt: "$prompt"
        """.trimIndent()

        val routeResult = searchModel.generateContent(routerPrompt).text?.trim()?.uppercase()

        return when (routeResult) {
            "PLAN" -> runAgentWorkflow(prompt, history)
            "SEARCH" -> executeSearchRequest(prompt)
            else -> executeSearchRequest(prompt)
        }
    }

    private suspend fun executeSearchRequest(prompt: String): AgentResponse {
        val response = searchModel.generateContent(prompt)
        val text = response.text ?: "I couldn't find an answer for that."
        return AgentResponse(text = text, report = "")
    }

    suspend fun executeTool(functionCall: FunctionCallPart): ToolResult {
        return when (functionCall.name) {
            "create_file" -> {
                val path = (functionCall.args["path"] as? JsonPrimitive)?.content?.trim()
                    ?.replace("\"", "") ?: ""
                val content = (functionCall.args["content"] as? JsonPrimitive)?.content ?: ""
                ideApi.createFile(path = path, content = content)
            }

            "update_file" -> {
                val path = (functionCall.args["path"] as? JsonPrimitive)?.content?.trim()
                    ?.replace("\"", "") ?: ""
                val content = (functionCall.args["content"] as? JsonPrimitive)?.content ?: ""
                ideApi.updateFile(path, content)
            }

            "read_file" -> ideApi.readFile(
                path = functionCall.args["path"].toString().trim().replace("\"", "")
            )

            "list_files" -> ideApi.listFiles(
                path = functionCall.args["path"].toString().trim().replace("\"", ""),
                recursive = functionCall.args["recursive"]?.toString().toBoolean()
            )

            "run_app" -> ideApi.runApp()
            "add_dependency" -> {
                val dependencyString =
                    (functionCall.args["dependency_string"] as? JsonPrimitive)?.content ?: ""
                val buildFilePath =
                    (functionCall.args["build_file_path"] as? JsonPrimitive)?.content?.trim()
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

            "get_build_output" -> ideApi.getBuildOutput()

            "add_string_resource" -> {
                val name = (functionCall.args["name"] as? JsonPrimitive)?.content ?: ""
                val value = (functionCall.args["value"] as? JsonPrimitive)?.content ?: ""
                if (name.isEmpty() || value.isEmpty()) {
                    ToolResult.failure("Both 'name' and 'value' parameters are required for add_string_resource.")
                } else {
                    ideApi.addStringResource(name, value)
                }
            }

            "ask_user" -> {
                val question = (functionCall.args["question"] as? JsonPrimitive)?.content ?: "..."

                val optionsJson = functionCall.args["options"]
                val options = optionsJson?.let {
                    Json.decodeFromJsonElement(ListSerializer(String.serializer()), it)
                } ?: listOf()

                onAskUser?.invoke(question, options)

                ToolResult(
                    success = true,
                    message = "The user has been asked the question. Await their response in the next turn."
                )
            }


            else -> ToolResult.failure("Unknown tool: ${functionCall.name}")
        }
    }

    override suspend fun generateCode(
        prompt: String,
        fileContent: String,
        fileName: String,
        fileRelativePath: String
    ): String {
        // Create a detailed prompt with all the context
        val contextPrompt = """
        You are an expert code generation assistant. The user is currently editing the file '$fileName' located at '$fileRelativePath'.

        This is the current full content of the file:
        ```
        $fileContent
        ```

        Based on this context, fulfill the user's request.

        User Request: "$prompt"

        IMPORTANT: Your response must be only the raw code itself. Do not add any explanations, comments, or markdown formatting like ```.
        """.trimIndent()

        val response = codeGenerationModel.generateContent(contextPrompt)
        return response.text ?: throw Exception("Failed to get a valid response from the API.")
    }

    override fun getPartialReport(): String {
        return toolTracker.generatePartialReport()
    }

    private suspend fun runAgentWorkflow(
        userInput: String,
        history: List<ChatMessage>
    ): AgentResponse {
        onStateUpdate?.invoke(AgentState.Processing("Initializing Agent Workflow..."))

        // Initialize agents with their models and dependencies
        val orchestrator = OrchestratorAgent(vertexAiModelForPlanning)
        val executor =
            ExecutorAgent(this) // Executor needs a reference to this repository to call tools
        val critic = CriticAgent(vertexAiModelForCritique)

        // 1. PLAN
        onStateUpdate?.invoke(AgentState.Processing("👨‍🎨 Creating a plan..."))
        val plan = orchestrator.createPlan(userInput, GeminiTools.getToolDeclarationsAsJson())
        if (plan.isEmpty()) {
            val failureMessage =
                "I couldn't create a valid plan for your request. Please try rephrasing it."
            onStateUpdate?.invoke(AgentState.Idle)
            return AgentResponse(text = failureMessage, report = "")
        }

        val completedSteps = mutableListOf<StepResult>()
        var finalMessage = ""

        // 2. EXECUTE and CRITIQUE in a loop
        for (step in plan) {
            onStateUpdate?.invoke(AgentState.Processing("🚀 Executing: ${step.objective}"))
            val result = executor.executeStep(step)
            onToolMessage?.invoke("Tool `${step.toolToUse}` output: ${result.output}")


            if (!result.wasSuccessful) {
                finalMessage = "A step failed during execution: ${result.error}. Aborting workflow."
                onStateUpdate?.invoke(AgentState.Processing(finalMessage))
                break // Stop the workflow
            }

            onStateUpdate?.invoke(AgentState.Processing("🧐 Critiquing result..."))
            val isSuccess = critic.evaluateResult(result, step)

            if (isSuccess) {
                onStateUpdate?.invoke(AgentState.Processing("✅ Step successful."))
                completedSteps.add(result)
            } else {
                finalMessage =
                    "A step failed the critique. The result didn't meet the objective. Aborting."
                onStateUpdate?.invoke(AgentState.Processing(finalMessage))
                // In a more advanced implementation, you would trigger a re-planning loop here,
                // feeding the failure reason back to the Orchestrator.
                break // Stop the workflow
            }
        }

        if (finalMessage.isEmpty()) {
            // 3. Final Synthesis
            // If all steps succeeded, you can use a final model call to summarize the results.
            finalMessage =
                "✅ Workflow completed successfully! All ${plan.size} steps were executed and verified."
        }

        onStateUpdate?.invoke(AgentState.Idle)
        // For now, we'll return a simple summary.
        return AgentResponse(
            text = finalMessage,
            report = "Completed ${completedSteps.size}/${plan.size} steps."
        )
    }
}