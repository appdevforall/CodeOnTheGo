package com.itsaky.androidide.agent.repository

import android.content.Context
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.Sender
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for Local LLM using actual GGUF models.
 *
 * Prerequisites:
 * 1. Download a GGUF model (e.g., gemma-3-1b-it.Q4_K_M.gguf from Unsloth)
 * 2. Place it in the device's Download folder or specify path via TEST_MODEL_PATH env var
 *
 * Run with:
 * ```
 * ./gradlew :agent:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.itsaky.androidide.agent.repository.LocalLlmIntegrationTest
 * ```
 *
 * Or with custom model path:
 * ```
 * adb shell setprop debug.test_model_path "/sdcard/Download/gemma-3-1b-it.Q4_K_M.gguf"
 * ./gradlew :agent:connectedAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LocalLlmIntegrationTest {

    private lateinit var context: Context
    private var engine: LlmInferenceEngine? = null
    private var modelPath: String? = null
    private var llamaLibraryInstalled = false

    companion object {
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

        // Check if Llama library is installed (required for these integration tests)
        // The library is installed via app's asset loading, not available in test context
        val llamaAarFile = File(context.getDir("dynamic_libs", Context.MODE_PRIVATE), "llama.aar")
        llamaLibraryInstalled = llamaAarFile.exists()

        // Skip all tests if Llama library is not installed
        // This check MUST be before creating LlmInferenceEngine to avoid Logback initialization errors
        assumeTrue(
            "Llama library not installed. These tests require running the app first to install the library.",
            llamaLibraryInstalled
        )

        // Find a model file (must be after context is set but before engine creation)
        modelPath = findModelFile()

        // Skip tests if no model is available
        assumeTrue(
            "No GGUF model found. Place a model in Download folder or set debug.test_model_path",
            modelPath != null
        )

        // Create engine AFTER all skip checks pass (avoids Logback issues on older Android)
        engine = LlmInferenceEngine()
    }

    @After
    fun tearDown() {
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

            val response = engine!!.runInference(
                prompt = "What is 2 + 2? Answer with just the number.",
                stopStrings = listOf("\n")
            )

            assertTrue("Response should not be empty", response.isNotBlank())
            println("Inference response: $response")

            // The response should contain "4" somewhere
            assertTrue(
                "Response should contain the answer '4'",
                response.contains("4")
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
        // Check system property first
        val propPath = System.getProperty("debug.test_model_path")
        if (propPath != null && File(propPath).exists()) {
            return propPath
        }

        // Search common locations
        for (path in MODEL_SEARCH_PATHS) {
            if (File(path).exists()) {
                return path
            }
        }

        // Check app's files directory
        val appModels = context.filesDir.listFiles { file ->
            file.extension == "gguf"
        }
        if (!appModels.isNullOrEmpty()) {
            return appModels.first().absolutePath
        }

        return null
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
}
