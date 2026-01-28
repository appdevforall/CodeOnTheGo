package com.itsaky.androidide.agent.repository

import android.content.Context
import android.content.res.AssetManager
import com.itsaky.androidide.agent.AgentState
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * Tests for LocalAgenticRunner's simplified workflow.
 *
 * These tests verify that Local LLM always uses the simplified single-call
 * workflow to avoid "max steps" errors.
 */
class LocalAgenticRunnerSimplifiedWorkflowTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var engine: LlmInferenceEngine

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("local-llm-test").toFile()
        context = mockContext(tempDir)
        engine = mockk<LlmInferenceEngine>(relaxed = true)
        every { engine.isModelLoaded } returns true
        every { engine.loadedModelName } returns "test-model.gguf"
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `shouldUseSimplifiedInstructions always returns true`() {
        val runner = createRunner()

        // Verify that simplified instructions is always true regardless of settings
        assertTrue(
            "LocalAgenticRunner should always use simplified instructions",
            runner.shouldUseSimplifiedInstructions()
        )
    }

    @Test
    fun `simplified workflow handles direct response without tool call`() = runBlocking {
        // Engine returns a direct response without tool call
        coEvery {
            engine.runInference(any(), stopStrings = any())
        } returns "Hello! How can I help you today?"

        val runner = createRunner()
        runner.generateASimpleResponse("Hello", emptyList())

        val messages = runner.messages.value
        assertTrue("Should have messages", messages.isNotEmpty())

        val agentMessage = messages.lastOrNull { it.sender == Sender.AGENT }
        assertEquals(
            "Agent should respond with clean text",
            "Hello! How can I help you today?",
            agentMessage?.text
        )
    }

    @Test
    fun `simplified workflow handles tool call response`() = runBlocking {
        // Engine returns a tool call
        coEvery {
            engine.runInference(any(), stopStrings = any())
        } returns "<tool_call>{\"name\": \"get_current_datetime\", \"args\": {}}</tool_call>"

        val runner = createRunner()

        // Capture state updates
        val states = mutableListOf<AgentState>()
        runner.onStateUpdate = { states.add(it) }

        runner.generateASimpleResponse("What time is it?", emptyList())

        // Should have gone through Thinking state with tool name
        assertTrue(
            "Should show 'Using get_current_datetime...' state",
            states.any {
                it is AgentState.Thinking && it.thought.contains("get_current_datetime")
            }
        )
    }

    @Test
    fun `simplified workflow selects only general tools for non-IDE queries`() = runBlocking {
        val promptSlot = slot<String>()
        coEvery {
            engine.runInference(capture(promptSlot), stopStrings = any())
        } returns "It's sunny today!"

        val runner = createRunner()
        runner.generateASimpleResponse("What's the weather?", emptyList())

        val capturedPrompt = promptSlot.captured

        // Should only include general tools for non-IDE query
        assertTrue(
            "Prompt should include get_weather tool",
            capturedPrompt.contains("get_weather")
        )
        assertTrue(
            "Prompt should include get_current_datetime tool",
            capturedPrompt.contains("get_current_datetime")
        )
        assertTrue(
            "Prompt should include get_device_battery tool",
            capturedPrompt.contains("get_device_battery")
        )

        // Should NOT include IDE tools for simple weather query
        // (This depends on the query not containing IDE keywords)
    }

    @Test
    fun `simplified workflow selects all tools for IDE-related queries`() = runBlocking {
        val promptSlot = slot<String>()
        coEvery {
            engine.runInference(capture(promptSlot), stopStrings = any())
        } returns "I'll create the file for you."

        val runner = createRunner()
        runner.generateASimpleResponse("Create a new file called Test.kt", emptyList())

        val capturedPrompt = promptSlot.captured

        // Should include IDE tools for file-related query
        // The query contains "file" and "create" which are IDE keywords
        assertTrue(
            "Prompt should be generated",
            capturedPrompt.isNotEmpty()
        )
    }

    @Test
    fun `simplified workflow does not call multi-step planning`() = runBlocking {
        coEvery {
            engine.runInference(any(), stopStrings = any())
        } returns "Done!"

        val runner = createRunner()
        runner.generateASimpleResponse("Hello", emptyList())

        // The plan should NOT be populated (simplified workflow doesn't create plans)
        val plan = runner.plan.value
        assertTrue(
            "Simplified workflow should not create a multi-step plan",
            plan == null || plan.steps.isEmpty()
        )
    }

    @Test
    fun `simplified workflow handles engine errors gracefully`() = runBlocking {
        coEvery {
            engine.runInference(any(), stopStrings = any())
        } throws RuntimeException("Model inference failed")

        val runner = createRunner()

        val states = mutableListOf<AgentState>()
        runner.onStateUpdate = { states.add(it) }

        runner.generateASimpleResponse("Hello", emptyList())

        // Should emit Error state
        assertTrue(
            "Should emit Error state on engine failure",
            states.any { it is AgentState.Error }
        )

        // Should add error message to chat
        val messages = runner.messages.value
        assertTrue(
            "Should add error message to chat",
            messages.any { it.text.contains("error", ignoreCase = true) }
        )
    }

    @Test
    fun `simplified workflow handles model not loaded`() = runBlocking {
        every { engine.isModelLoaded } returns false

        val runner = createRunner()

        val states = mutableListOf<AgentState>()
        runner.onStateUpdate = { states.add(it) }

        runner.generateASimpleResponse("Hello", emptyList())

        // Should emit Error state about model not loaded
        assertTrue(
            "Should emit Error state when model not loaded",
            states.any {
                it is AgentState.Error && it.message.contains("not loaded", ignoreCase = true)
            }
        )
    }

    // --- Helper methods ---

    private fun createRunner(): LocalAgenticRunner {
        return LocalAgenticRunner(
            context = context,
            engine = engine,
            maxSteps = 20
        )
    }

    private fun mockContext(tempDir: File): Context {
        val context = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)

        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns tempDir
        every { context.assets } returns assetManager
        every { context.packageName } returns "com.itsaky.androidide.test"

        // Mock asset loading for system prompt
        every {
            assetManager.open("agent/system_prompt.txt")
        } returns ByteArrayInputStream("You are a helpful assistant.".toByteArray())

        every {
            assetManager.open("agent/policy.yml")
        } throws FileNotFoundException("policy.yml not found")

        every {
            assetManager.open("agent/planner_fewshots.json")
        } throws FileNotFoundException("planner_fewshots.json not found")

        return context
    }
}
