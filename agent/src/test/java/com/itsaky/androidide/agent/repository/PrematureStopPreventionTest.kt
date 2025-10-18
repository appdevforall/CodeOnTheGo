package com.itsaky.androidide.agent.repository

import android.content.Context
import android.content.res.AssetManager
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.Part
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * Tests to prevent premature stopping of agent execution.
 *
 * These tests verify that the agent correctly distinguishes between:
 * 1. Exploration steps (finding/inspecting files) - can complete without tool calls
 * 2. Implementation steps (adding/modifying code) - must call tools to complete
 */
class PrematureStopPreventionTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `exploration step completes without tool calls`() = runBlocking {
        val tempDir = Files.createTempDirectory("exploration-test").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            every { critic.reviewAndSummarize(any()) } returns "OK"
            val executor = mockk<Executor>(relaxed = true)

            // Create a plan with an exploration step
            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Determine the UI framework being used"))
            )

            // Planner returns text without function calls (exploration complete)
            val textResponse = Content.builder()
                .role("model")
                .parts(
                    Part.builder().text("Found that the project uses Android Views framework")
                        .build()
                )
                .build()

            every { planner.planForStep(any(), any(), 0) } returns textResponse

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "What UI framework is this project using?",
                history = emptyList()
            )

            // Verify the step completed successfully
            val plan = runner.plan.value!!
            assertEquals(StepStatus.DONE, plan.steps[0].status)
            assertEquals(
                "Found that the project uses Android Views framework",
                plan.steps[0].result
            )

            // Verify planner was called only once (no retry needed)
            verify(exactly = 1) { planner.planForStep(any(), any(), 0) }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `implementation step without tool calls triggers retry`() = runBlocking {
        val tempDir = Files.createTempDirectory("implementation-test").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            every { critic.reviewAndSummarize(any()) } returns "OK"
            val executor = mockk<Executor>(relaxed = true)

            // Create a plan with an implementation step
            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Add a button to MainActivity"))
            )

            // First attempt: planner returns text without tool calls (WRONG)
            val textOnlyResponse = Content.builder()
                .role("model")
                .parts(Part.builder().text("I found the MainActivity file").build())
                .build()

            // Second attempt: planner correctly calls the update_file tool
            val toolCallResponse = Content.builder()
                .role("model")
                .parts(
                    Part.builder().functionCall(
                        FunctionCall.builder()
                            .name("update_file")
                            .args(
                                mapOf(
                                    "path" to "app/src/main/java/MainActivity.java",
                                    "content" to "// Updated content with button"
                                )
                            )
                            .build()
                    ).build()
                )
                .build()

            every {
                planner.planForStep(
                    any(),
                    any(),
                    0
                )
            } returns textOnlyResponse andThen toolCallResponse
            coEvery { executor.execute(any()) } returns listOf(
                Part.builder().functionResponse(
                    com.google.genai.types.FunctionResponse.builder()
                        .name("update_file")
                        .response(mapOf("success" to true))
                        .build()
                ).build()
            )

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Add a button to MainActivity",
                history = emptyList()
            )

            // Verify the step eventually completed
            val plan = runner.plan.value!!
            assertEquals(StepStatus.DONE, plan.steps[0].status)

            // Verify planner was called TWICE (retry happened)
            verify(exactly = 2) { planner.planForStep(any(), any(), 0) }

            // Verify executor was called (tool was executed)
            coVerify(exactly = 1) { executor.execute(any()) }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `implementation step fails after max retries without tool calls`() = runBlocking {
        val tempDir = Files.createTempDirectory("implementation-fail-test").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            every { critic.reviewAndSummarize(any()) } returns "OK"
            val executor = mockk<Executor>(relaxed = true)

            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Modify the layout file"))
            )

            // Planner keeps returning text without tool calls (stubborn bug)
            val textOnlyResponse = Content.builder()
                .role("model")
                .parts(Part.builder().text("I see the layout file exists").build())
                .build()

            every { planner.planForStep(any(), any(), any()) } returns textOnlyResponse

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Modify the layout",
                history = emptyList()
            )

            // Verify the step failed after max attempts
            val plan = runner.plan.value!!
            assertEquals(StepStatus.FAILED, plan.steps[0].status)
            assertTrue(plan.steps[0].result?.contains("requires actions but no tools were called") == true)

            // Verify planner was called MAX_STEP_ATTEMPTS times
            verify(exactly = 3) { planner.planForStep(any(), any(), 0) }

            // Verify executor was NEVER called (no tools to execute)
            coVerify(exactly = 0) { executor.execute(any()) }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `multi-step plan executes all implementation steps with retries`() = runBlocking {
        val tempDir = Files.createTempDirectory("multi-step-test").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            every { critic.reviewAndSummarize(any()) } returns "OK"
            val executor = mockk<Executor>(relaxed = true)

            // Create a realistic 3-step plan
            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(
                    TaskStep("Locate the MainActivity file"),  // Exploration step
                    TaskStep("Add input field and button"),    // Implementation step
                    TaskStep("Implement multiplication logic")  // Implementation step
                )
            )

            // Step 0: Exploration - can complete with text only
            val step0Response = Content.builder()
                .role("model")
                .parts(Part.builder().text("Found MainActivity.java").build())
                .build()

            // Step 1: Implementation - first returns text (needs retry), then tool calls
            val step1TextResponse = Content.builder()
                .role("model")
                .parts(Part.builder().text("I found the layout file").build())
                .build()
            val step1ToolResponse = Content.builder()
                .role("model")
                .parts(
                    Part.builder().functionCall(
                        FunctionCall.builder()
                            .name("update_file")
                            .args(mapOf("path" to "activity_main.xml", "content" to "..."))
                            .build()
                    ).build()
                )
                .build()

            // Step 2: Implementation - directly calls tools (no retry needed)
            val step2Response = Content.builder()
                .role("model")
                .parts(
                    Part.builder().functionCall(
                        FunctionCall.builder()
                            .name("update_file")
                            .args(mapOf("path" to "MainActivity.java", "content" to "..."))
                            .build()
                    ).build()
                )
                .build()

            every { planner.planForStep(any(), any(), 0) } returns step0Response
            every {
                planner.planForStep(
                    any(),
                    any(),
                    1
                )
            } returns step1TextResponse andThen step1ToolResponse
            every { planner.planForStep(any(), any(), 2) } returns step2Response

            coEvery { executor.execute(any()) } returns listOf(
                Part.builder().functionResponse(
                    com.google.genai.types.FunctionResponse.builder()
                        .name("update_file")
                        .response(mapOf("success" to true))
                        .build()
                ).build()
            )

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 3,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Add feature to multiply input by 2",
                history = emptyList()
            )

            // Verify all steps completed
            val plan = runner.plan.value!!
            assertEquals(3, plan.steps.size)
            assertEquals(StepStatus.DONE, plan.steps[0].status)  // Exploration
            assertEquals(StepStatus.DONE, plan.steps[1].status)  // Implementation with retry
            assertEquals(StepStatus.DONE, plan.steps[2].status)  // Implementation

            // Verify correct number of planner calls
            verify(exactly = 1) { planner.planForStep(any(), any(), 0) }  // Step 0: no retry
            verify(exactly = 2) { planner.planForStep(any(), any(), 1) }  // Step 1: 1 retry
            verify(exactly = 1) { planner.planForStep(any(), any(), 2) }  // Step 2: no retry

            // Verify executor was called for implementation steps
            coVerify(exactly = 2) { executor.execute(any()) }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `step with add keyword requires tool calls`() = runBlocking {
        val tempDir = Files.createTempDirectory("add-keyword-test").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            every { critic.reviewAndSummarize(any()) } returns "OK"
            val executor = mockk<Executor>(relaxed = true)

            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Add necessary UI elements"))
            )

            // First attempt: text only (should trigger retry)
            val textResponse = Content.builder()
                .role("model")
                .parts(Part.builder().text("Found the files").build())
                .build()

            // Second attempt: tool call
            val toolResponse = Content.builder()
                .role("model")
                .parts(
                    Part.builder().functionCall(
                        FunctionCall.builder().name("create_file").args(mapOf()).build()
                    ).build()
                )
                .build()

            every { planner.planForStep(any(), any(), 0) } returns textResponse andThen toolResponse
            coEvery { executor.execute(any()) } returns listOf(
                Part.builder().functionResponse(
                    com.google.genai.types.FunctionResponse.builder()
                        .name("create_file")
                        .response(mapOf("success" to true))
                        .build()
                ).build()
            )

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Add UI elements",
                history = emptyList()
            )

            val plan = runner.plan.value!!
            assertEquals(StepStatus.DONE, plan.steps[0].status)
            verify(exactly = 2) { planner.planForStep(any(), any(), 0) }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `step with modify keyword requires tool calls`() = runBlocking {
        val tempDir = Files.createTempDirectory("modify-keyword-test").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            every { critic.reviewAndSummarize(any()) } returns "OK"
            val executor = mockk<Executor>(relaxed = true)

            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Modify the existing code"))
            )

            val textResponse = Content.builder()
                .role("model")
                .parts(Part.builder().text("Code located").build())
                .build()

            val toolResponse = Content.builder()
                .role("model")
                .parts(
                    Part.builder().functionCall(
                        FunctionCall.builder().name("update_file").args(mapOf()).build()
                    ).build()
                )
                .build()

            every { planner.planForStep(any(), any(), 0) } returns textResponse andThen toolResponse
            coEvery { executor.execute(any()) } returns listOf(
                Part.builder().functionResponse(
                    com.google.genai.types.FunctionResponse.builder()
                        .name("update_file")
                        .response(mapOf("success" to true))
                        .build()
                ).build()
            )

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Modify the code",
                history = emptyList()
            )

            verify(exactly = 2) { planner.planForStep(any(), any(), 0) }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `exploration keywords correctly identified`() = runBlocking {
        val explorationKeywords = listOf(
            "Find the main file",
            "Locate all components",
            "Identify the framework",
            "Determine project structure",
            "Inspect the codebase",
            "Examine the layout files",
            "Check for dependencies",
            "Look for configuration",
            "Search for MainActivity",
            "Discover available resources",
            "Understand the architecture",
            "Analyze the code structure"
        )

        val tempDir = Files.createTempDirectory("keyword-test").toFile()
        try {
            val context = mockContext(tempDir)

            for (description in explorationKeywords) {
                val planner = mockk<Planner>()
                val critic = mockk<Critic>(relaxed = true)
                every { critic.reviewAndSummarize(any()) } returns "OK"
                val executor = mockk<Executor>(relaxed = true)

                every { planner.createInitialPlan(any()) } returns Plan(
                    mutableListOf(TaskStep(description))
                )

                val textResponse = Content.builder()
                    .role("model")
                    .parts(Part.builder().text("Exploration complete").build())
                    .build()

                every { planner.planForStep(any(), any(), 0) } returns textResponse

                val runner = GeminiAgenticRunner(
                    appContext = context,
                    maxSteps = 1,
                    toolsOverride = emptyList(),
                    plannerOverride = planner,
                    criticOverride = critic,
                    executorOverride = executor
                )

                runner.generateASimpleResponse(
                    prompt = description,
                    history = emptyList()
                )

                // Verify step completed without retry (exploration step)
                val plan = runner.plan.value!!
                assertEquals(
                    "Step '$description' should be treated as exploration and complete without tool calls",
                    StepStatus.DONE,
                    plan.steps[0].status
                )
                verify(exactly = 1) { planner.planForStep(any(), any(), 0) }
            }

        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun mockContext(filesDir: File): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(null) } returns filesDir

        val assets = mockk<AssetManager>()
        every { context.assets } returns assets

        every { assets.open("agent/policy.yml") } answers {
            throw FileNotFoundException("policy.yml not found")
        }
        every { assets.open("agent/planner_fewshots.json") } answers {
            throw FileNotFoundException("planner_fewshots.json not found")
        }
        return context
    }
}
