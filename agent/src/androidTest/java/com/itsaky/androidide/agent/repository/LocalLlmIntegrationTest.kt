package com.itsaky.androidide.agent.repository

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.api.IdeToolingApi
import com.itsaky.androidide.agent.model.ReviewDecision
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.shell.ShellCommandResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.FileWriter

/**
 * Integration tests for Local LLM using actual GGUF models.
 *
 * Prerequisites:
 * 1. Download a GGUF model (e.g., gemma-3-1b-it.Q4_K_M.gguf from Unsloth)
 * 2. Place it in the device's Download folder or specify path via TEST_MODEL_PATH env var
 *
 * The Llama library is automatically installed from test assets during setUp.
 *
 * Run with:
 * ```
 * ./gradlew :agent:connectedV8DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.itsaky.androidide.agent.repository.LocalLlmIntegrationTest
 * ```
 *
 * Or with custom model path:
 * ```
 * adb shell setprop debug.test_model_path "/sdcard/Download/gemma-3-1b-it.Q4_K_M.gguf"
 * ./gradlew :agent:connectedV8DebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LocalLlmIntegrationTest {

    private lateinit var context: Context
    private var engine: LlmInferenceEngine? = null
    private var modelPath: String? = null
    private var llamaLibraryInstalled = false
    private var adoptedShellPermissions = false

    companion object {
        private const val TAG = "LocalLlmIntegrationTest"
        @Volatile
        private var llamaInstalledForSession = false

        // Common model locations to check
        private val MODEL_SEARCH_PATHS = listOf(
            // Download folder - various Gemma models
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/gemma-3-1b-it-UD-IQ1_S.gguf",
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/gemma-3-1b-it.Q4_K_M.gguf",
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/gemma3-1b.gguf",
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/phi-2-dpo.Q3_K_M.gguf",
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/test-model.gguf",
            // App-specific storage
            "/data/local/tmp/test-model.gguf",
        )

        private const val MODEL_LOAD_TIMEOUT_MS = 60_000L // 1 minute for model loading
        private const val INFERENCE_TIMEOUT_MS = 120_000L // 2 minutes for inference
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        adoptShellStoragePermissions()
        AgentDependencies.registerToolingApi(FakeIdeToolingApi())

        // Try to install Llama library from test assets if not already installed
        installLlamaLibraryFromTestAssets()

        // Check if Llama library is installed
        val llamaAarFile = File(context.getDir("dynamic_libs", Context.MODE_PRIVATE), "llama.aar")
        llamaLibraryInstalled = llamaAarFile.exists()

        // Skip all tests if Llama library is not installed
        assumeTrue(
            "Llama library not installed. Check that test assets are properly configured.",
            llamaLibraryInstalled
        )

        // Find a model file (must be after context is set but before engine creation)
        modelPath = findModelFile()

        // Skip tests if no model is available
        assumeTrue(
            "No GGUF model found. Place a model in Download folder or set debug.test_model_path",
            modelPath != null
        )

        // Create engine AFTER all skip checks pass
        engine = LlmInferenceEngine()
    }

    /**
     * Installs the Llama AAR from test assets to the expected location.
     * This allows integration tests to run without requiring the main app to be installed first.
     */
    private fun installLlamaLibraryFromTestAssets() {
        val destDir = context.getDir("dynamic_libs", Context.MODE_PRIVATE)
        val destFile = File(destDir, "llama.aar")
        val unzipDir = context.getDir("llama_unzipped", Context.MODE_PRIVATE)

        if (llamaInstalledForSession && destFile.exists() && unzipDir.exists()) {
            Log.d(TAG, "Llama library already installed for this test session.")
            return
        }

        // Refresh once per test session to avoid stale AARs across runs.
        if (destFile.exists()) {
            Log.d(TAG, "Removing existing Llama library at ${destFile.absolutePath}")
            destFile.delete()
        }
        if (unzipDir.exists()) {
            Log.d(TAG, "Removing existing unzipped Llama contents at ${unzipDir.absolutePath}")
            unzipDir.deleteRecursively()
        }

        // Determine which AAR to use based on device architecture
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: run {
            Log.e(TAG, "No supported ABIs found")
            return
        }

        val assetName = when {
            abi.contains("arm64") || abi.contains("aarch64") -> "dynamic_libs/llama-v8.aar"
            abi.contains("arm") -> "dynamic_libs/llama-v7.aar"
            else -> {
                Log.w(TAG, "Unsupported ABI: $abi")
                return
            }
        }

        try {
            Log.i(TAG, "Installing Llama library from test assets: $assetName")
            destDir.mkdirs()

            context.assets.open(assetName).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Llama library installed successfully to ${destFile.absolutePath}")
            llamaInstalledForSession = true
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Llama AAR not found in test assets: $assetName. " +
                    "Make sure the copyLlamaAarForTests task ran during build.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Llama library from test assets", e)
        }
    }

    @After
    fun tearDown() {
        if (adoptedShellPermissions) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
            adoptedShellPermissions = false
        }
        AgentDependencies.clear()
        runBlocking {
            engine?.let {
                if (it.isModelLoaded) {
                    it.unloadModel()
                }
            }
        }
    }

    @Test
    fun testEngineInitialization() = runBlocking {
        val initialized = engine!!.initialize(context)
        assertTrue("Engine should initialize successfully", initialized)
    }

    @Test
    fun testModelLoading() = runBlocking {
        withTimeout(MODEL_LOAD_TIMEOUT_MS) {
            val eng = engine!!
            val initialized = eng.initialize(context)
            assumeTrue("Engine must initialize for model loading test", initialized)

            // Convert file path to content URI format that the engine expects
            val modelFile = File(modelPath!!)
            assumeTrue("Model file must exist: $modelPath", modelFile.exists())

            val loaded = eng.initModelFromFile(context, modelFile.toURI().toString())
            assertTrue("Model should load successfully", loaded)
            assertTrue("isModelLoaded should be true", eng.isModelLoaded)
            assertNotNull("Loaded model name should not be null", eng.loadedModelName)
        }
    }

    @Test
    fun testSimpleInference() = runBlocking {
        withTimeout(INFERENCE_TIMEOUT_MS) {
            loadModelOrSkip()

            val prompt =
                "You are a helpful assistant.\nuser: What is 2 + 2? Answer with just the number.\nmodel:"
            var response = engine!!.runInference(
                prompt = prompt,
                stopStrings = emptyList()
            )
            if (response.isBlank()) {
                response = engine!!.runInference(
                    prompt = prompt,
                    stopStrings = listOf("\n")
                )
            }

            assertTrue("Response should not be empty", response.isNotBlank())
            println("Inference response: $response")

            // The response should contain "4" somewhere
            assertTrue(
                "Response should contain the answer '4'",
                response.contains("4") || response.contains("four", ignoreCase = true)
            )
        }
    }

    @Test
    fun testLocalAgenticRunnerSimplifiedWorkflow() = runBlocking {
        withTimeout(INFERENCE_TIMEOUT_MS) {
            loadModelOrSkip()

            val runner = LocalAgenticRunner(
                context = context,
                engine = engine!!,
                maxSteps = 20
            )

            // Simplified workflow is forced - we verify by checking the plan is not created

            // Capture states
            val states = mutableListOf<AgentState>()
            runner.onStateUpdate = { states.add(it) }

            // Run a simple query
            runner.generateASimpleResponse(
                prompt = "What is 2 + 2?",
                history = emptyList()
            )

            // Should NOT have any "Exceeded max steps" error
            val errorStates = states.filterIsInstance<AgentState.Error>()
            val hasMaxStepsError = errorStates.any {
                it.message.contains("max steps", ignoreCase = true) ||
                        it.message.contains("attempts", ignoreCase = true)
            }
            assertTrue(
                "Should NOT have max steps error. Errors: ${errorStates.map { it.message }}",
                !hasMaxStepsError
            )

            // Should have agent response
            val messages = runner.messages.value
            val agentMessages = messages.filter { it.sender == Sender.AGENT }
            assertTrue(
                "Should have at least one agent message",
                agentMessages.isNotEmpty()
            )

            println("Agent response: ${agentMessages.lastOrNull()?.text}")
        }
    }

    @Test
    fun testToolCallParsing() = runBlocking {
        withTimeout(INFERENCE_TIMEOUT_MS) {
            loadModelOrSkip()

            val runner = LocalAgenticRunner(
                context = context,
                engine = engine!!,
                maxSteps = 20
            )

            val states = mutableListOf<AgentState>()
            runner.onStateUpdate = { states.add(it) }

            // Ask for current time - should trigger get_current_datetime tool
            runner.generateASimpleResponse(
                prompt = "What time is it?",
                history = emptyList()
            )

            val messages = runner.messages.value
            println("Messages: ${messages.map { "${it.sender}: ${it.text}" }}")

            // The response should either:
            // 1. Call the get_current_datetime tool
            // 2. Or give a direct response about not knowing the time
            assertTrue(
                "Should have at least one agent message",
                messages.any { it.sender == Sender.AGENT }
            )
        }
    }

    @Test
    fun testNoMaxStepsErrorWithComplexQuery() = runBlocking {
        withTimeout(INFERENCE_TIMEOUT_MS * 2) { // Allow more time for complex query
            loadModelOrSkip()

            val runner = LocalAgenticRunner(
                context = context,
                engine = engine!!,
                maxSteps = 20
            )

            val states = mutableListOf<AgentState>()
            runner.onStateUpdate = { states.add(it) }

            // Complex query that might trigger multi-step thinking in full workflow
            runner.generateASimpleResponse(
                prompt = "Create a new Kotlin file called Calculator.kt with a function that adds two numbers",
                history = emptyList()
            )

            // Should NOT throw or have max steps error
            val errorStates = states.filterIsInstance<AgentState.Error>()
            errorStates.forEach { println("Error state: ${it.message}") }

            val hasMaxStepsError = errorStates.any {
                it.message.contains("max steps", ignoreCase = true) ||
                        it.message.contains("exceeded", ignoreCase = true) ||
                        it.message.contains("attempts", ignoreCase = true)
            }

            assertTrue(
                "Should NOT have max steps or retry exhaustion error",
                !hasMaxStepsError
            )
        }
    }

    // --- Helper methods ---

    private fun findModelFile(): String? {
        // Check explicit overrides first
        val overridePath = resolveOverrideModelPath()
        if (overridePath != null && File(overridePath).exists() && File(overridePath).canRead()) {
            return overridePath
        }

        // Check app's files directory
        val appModels = context.filesDir.listFiles { file ->
            file.extension == "gguf"
        }
        if (!appModels.isNullOrEmpty()) {
            return appModels.first().absolutePath
        }

        // Check app-specific external files directories (accessible without SAF)
        val externalAppDir = context.getExternalFilesDir(null)
        val externalModels = externalAppDir?.listFiles { file ->
            file.extension == "gguf"
        }
        if (!externalModels.isNullOrEmpty()) {
            return externalModels.first().absolutePath
        }

        val externalDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val externalDownloadModels = externalDownloadsDir?.listFiles { file ->
            file.extension == "gguf"
        }
        if (!externalDownloadModels.isNullOrEmpty()) {
            return externalDownloadModels.first().absolutePath
        }

        // Search common locations (only if readable)
        for (path in MODEL_SEARCH_PATHS) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return path
            }
        }

        return null
    }

    private fun resolveOverrideModelPath(): String? {
        val sysProp = System.getProperty("debug.test_model_path")
        if (!sysProp.isNullOrBlank()) {
            return sysProp
        }

        val argsPath = InstrumentationRegistry.getArguments().getString("test_model_path")
        if (!argsPath.isNullOrBlank()) {
            return argsPath
        }

        val systemProp = getSystemProperty("debug.test_model_path")
        if (!systemProp.isNullOrBlank()) {
            return systemProp
        }

        return null
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun adoptShellStoragePermissions() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            uiAutomation.adoptShellPermissionIdentity(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            adoptedShellPermissions = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adopt shell storage permissions: ${e.message}")
        }
    }

    private suspend fun loadModelOrSkip() {
        val eng = engine!!
        val initialized = eng.initialize(context)
        assumeTrue("Engine must initialize", initialized)

        if (!eng.isModelLoaded) {
            val modelFile = File(modelPath!!)
            val loaded = eng.initModelFromFile(context, modelFile.toURI().toString())
            assumeTrue("Model must load for this test", loaded)
        }
    }

    @Test
    fun testAllToolsAreInvokedFromPrompt() = runBlocking {
        withTimeout(INFERENCE_TIMEOUT_MS) {
            loadModelOrSkip()

            val toolCases = listOf(
                ToolCase("create_file", """{"path":"tool-tests/created.txt","content":"hello"}"""),
                ToolCase(
                    "read_file",
                    """{"file_path":"tool-tests/created.txt","offset":0,"limit":10}"""
                ),
                ToolCase("list_files", """{"path":".","recursive":false}"""),
                ToolCase(
                    "search_project",
                    """{"query":"LocalLlmIntegrationTest","path":".","max_results":5,"ignore_case":true}"""
                ),
                ToolCase("run_app", "{}"),
                ToolCase(
                    "add_dependency",
                    """{"dependency_string":"implementation(\"com.example:demo:1.0\")","build_file_path":"app/build.gradle.kts"}"""
                ),
                ToolCase(
                    "ask_user",
                    """{"question":"Pick one","options":"[\\\"A\\\",\\\"B\\\"]"}"""
                ),
                ToolCase(
                    "update_file",
                    """{"path":"tool-tests/updated.txt","content":"updated"}"""
                ),
                ToolCase("get_build_output", "{}"),
                ToolCase("get_device_battery", "{}"),
                ToolCase("get_current_datetime", "{}"),
                ToolCase("get_weather", """{"city":"Quito"}"""),
                ToolCase("add_string_resource", """{"name":"test_string","value":"Hello"}""")
            )

            for (case in toolCases) {
                val runner = LocalAgenticRunner(
                    context = context,
                    engine = engine!!,
                    maxSteps = 20
                )

                val states = mutableListOf<AgentState>()
                runner.onStateUpdate = { state ->
                    states.add(state)
                    if (state is AgentState.AwaitingApproval) {
                        runner.submitApprovalDecision(state.id, ReviewDecision.ApprovedForSession)
                    }
                }

                val toolCallJson = """{"name":"${case.toolName}","args":${case.argsJson}}"""
                val prompt = "Use the tool ${case.toolName} now. " +
                        "Respond ONLY with: <tool_call>$toolCallJson</tool_call>"
                runner.testResponseOverride = "<tool_call>$toolCallJson</tool_call>"

                runner.generateASimpleResponse(prompt = prompt, history = emptyList())

                val usedTool = states.any { state ->
                    state is AgentState.Thinking &&
                            state.thought.contains("Using ${case.toolName}", ignoreCase = true)
                }

                assertTrue(
                    "Expected tool '${case.toolName}' to be called. States=${states.map { it::class.simpleName to it }}",
                    usedTool
                )
            }
        }
    }

    @Test
    fun testLocalLlmBenchmarkSuite() {
        runBlocking {
            val benchmarkTimeoutMs = 20 * 60_000L
            withTimeout(benchmarkTimeoutMs) {
                loadModelOrSkip()

                val toolCases = listOf(
                    BenchmarkCase(
                        "Create a file at tool-tests/bench.txt with content hello.",
                        "create_file"
                    ),
                    BenchmarkCase("Read the file tool-tests/bench.txt.", "read_file"),
                    BenchmarkCase("List files in the project root.", "list_files"),
                    BenchmarkCase(
                        "Search the project for the word LocalLlmIntegrationTest.",
                        "search_project"
                    ),
                    BenchmarkCase("Run the app on the device.", "run_app"),
                    BenchmarkCase(
                        "Add dependency implementation(\"com.example:demo:1.0\") to app/build.gradle.kts.",
                        "add_dependency"
                    ),
                    BenchmarkCase(
                        "Update file tool-tests/bench.txt with content updated.",
                        "update_file"
                    ),
                    BenchmarkCase("Get the latest build output.", "get_build_output"),
                    BenchmarkCase("What's the device battery percentage?", "get_device_battery"),
                    BenchmarkCase("What time is it now?", "get_current_datetime"),
                    BenchmarkCase("What's the weather in Quito?", "get_weather"),
                    BenchmarkCase(
                        "Add string resource welcome_message with value Hello.",
                        "add_string_resource"
                    ),
                    BenchmarkCase("Ask the user to pick A or B.", "ask_user"),
                    BenchmarkCase("Hello! How are you?", null),
                    BenchmarkCase("Explain what a Kotlin data class is.", null)
                )

                val promptTemplate = buildBenchmarkPromptTemplate()
                val stopStrings = listOf(
                    "</tool_call>",
                    "\nuser:",
                    "\nUser:",
                    "\n\n"
                )

                val timestamp = System.currentTimeMillis()
                val outputFile =
                    File(context.getExternalFilesDir(null), "local_llm_benchmark_$timestamp.csv")
                FileWriter(outputFile).use { writer ->
                    writer.append("prompt,expected_tool,detected_tool,tool_match,ttft_ms,total_ms,output_chars\n")
                    for (case in toolCases) {
                        val prompt = promptTemplate.replace("{{USER_MESSAGE}}", case.prompt)
                        val start = SystemClock.elapsedRealtime()
                        var firstTokenMs = -1L
                        val responseBuilder = StringBuilder()

                        engine!!.runStreamingInference(prompt, stopStrings).collect { chunk ->
                            if (firstTokenMs < 0) {
                                firstTokenMs = SystemClock.elapsedRealtime() - start
                            }
                            responseBuilder.append(chunk)
                        }

                        val totalMs = SystemClock.elapsedRealtime() - start
                        val responseText = responseBuilder.toString()
                        val parsedCall = Util.parseToolCall(
                            responseText,
                            LocalLlmTools.allTools.map { it.name }.toSet()
                        )
                        val detectedTool = parsedCall?.name
                        val toolMatch = when {
                            case.expectedTool == null -> parsedCall == null
                            else -> detectedTool == case.expectedTool
                        }

                        writer.append(
                            "${csvEscape(case.prompt)}," +
                                    "${csvEscape(case.expectedTool)}," +
                                    "${csvEscape(detectedTool)}," +
                                    "$toolMatch," +
                                    "${firstTokenMs.coerceAtLeast(0)}," +
                                    "$totalMs," +
                                    "${responseText.length}\n"
                        )

                        Log.i(
                            TAG,
                            "Benchmark: prompt='${case.prompt.take(80)}' expected=${case.expectedTool} " +
                                    "detected=$detectedTool match=$toolMatch ttftMs=$firstTokenMs totalMs=$totalMs"
                        )
                    }
                }

                Log.i(TAG, "Benchmark results written to: ${outputFile.absolutePath}")
            }
        }
    }

    private data class ToolCase(
        val toolName: String,
        val argsJson: String
    )

    private data class BenchmarkCase(
        val prompt: String,
        val expectedTool: String?
    )

    private fun buildBenchmarkPromptTemplate(): String {
        val toolsJson = LocalLlmTools.allTools.joinToString(", ") { "\"${tool.name}\"" }

        return """
You are a helpful assistant. Answer directly unless a tool is required.

Available tools:
[
  $toolsJson
]

To use a tool, respond with a single <tool_call> XML tag containing a JSON object.
If no tool is needed, answer the user's question directly.

Respond ONLY with a single <tool_call> tag OR your direct text answer. Do not add any other text before or after.

user: {{USER_MESSAGE}}
model: """.trimIndent()
    }

    private fun csvEscape(value: String?): String {
        val raw = value ?: ""
        val escaped = raw.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private class FakeIdeToolingApi : IdeToolingApi {
        override fun createFile(path: String, content: String): ToolResult =
            ToolResult.success("created $path")

        override fun readFile(path: String, offset: Int?, limit: Int?): ToolResult =
            ToolResult.success("read $path", data = "stub")

        override fun readFileContent(path: String, offset: Int?, limit: Int?): Result<String> =
            Result.success("stub")

        override fun updateFile(path: String, content: String): ToolResult =
            ToolResult.success("updated $path")

        override fun deleteFile(path: String): ToolResult =
            ToolResult.success("deleted $path")

        override fun listFiles(path: String, recursive: Boolean): ToolResult =
            ToolResult.success("listed $path", data = "[]")

        override fun searchProject(
            query: String,
            path: String?,
            maxResults: Int,
            ignoreCase: Boolean
        ): ToolResult = ToolResult.success("search $query", data = "[]")

        override fun addDependency(dependencyString: String, buildFilePath: String): ToolResult =
            ToolResult.success("added dependency")

        override fun addStringResource(name: String, value: String): ToolResult =
            ToolResult.success("added string $name")

        override suspend fun runApp(): ToolResult =
            ToolResult.success("run app")

        override suspend fun triggerGradleSync(): ToolResult =
            ToolResult.success("sync")

        override fun getBuildOutput(): ToolResult =
            ToolResult.success("build output", data = "")

        override fun getBuildOutputContent(): String? = ""

        override suspend fun executeShellCommand(command: String): ShellCommandResult =
            ShellCommandResult(exitCode = 0, stdout = "", stderr = "")

        override fun getDeviceBattery(): ToolResult =
            ToolResult.success("battery 100%")

        override fun getCurrentDateTime(): ToolResult =
            ToolResult.success("time now")

        override fun getWeather(city: String?): ToolResult =
            ToolResult.success("weather ${city.orEmpty()}")
    }
}
