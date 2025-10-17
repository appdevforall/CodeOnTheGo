package com.itsaky.androidide.agent.repository

import android.content.Context
import android.content.res.AssetManager
import com.google.genai.types.Content
import com.google.genai.types.Part
import com.itsaky.androidide.agent.Sender
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

class GeminiAgenticRunnerTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `generateASimpleResponse updates messages with planner text`() = runBlocking {
        val tempDir = Files.createTempDirectory("gemini-runner").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            val executor = mockk<Executor>(relaxed = true)

            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Prepare response"))
            )
            val planOutput = Content.builder()
                .role("model")
                .parts(Part.builder().text("Final answer").build())
                .build()

            every { planner.planForStep(any(), any(), any()) } returns planOutput

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Please respond",
                history = emptyList()
            )

            val messages = runner.messages.value
            assertEquals(2, messages.size)
            assertEquals("Please respond", messages.first().text)
            assertEquals(Sender.USER, messages.first().sender)
            assertEquals("Final answer", messages.last().text)
            assertEquals(Sender.AGENT, messages.last().sender)
            verify(exactly = 1) { planner.planForStep(any(), any(), any()) }
            verify(exactly = 1) { planner.createInitialPlan(any()) }
            val planState = runner.plan.value!!
            assertEquals(StepStatus.DONE, planState.steps.first().status)
            assertEquals("Final answer", planState.steps.first().result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `stop cancels current job and replaces runner scope`() {
        val tempDir = Files.createTempDirectory("gemini-runner-stop").toFile()
        try {
            val context = mockContext(tempDir)
            val runner = GeminiAgenticRunner(
                appContext = context,
                toolsOverride = emptyList(),
                plannerOverride = mockk<Planner>(relaxed = true),
                criticOverride = mockk<Critic>(relaxed = true),
                executorOverride = mockk<Executor>(relaxed = true)
            )

            val jobField = BaseAgenticRunner::class.java.getDeclaredField("runnerJob").apply {
                isAccessible = true
            }
            val initialJob = jobField.get(runner) as Job
            assertTrue(initialJob.isActive)

            runner.stop()

            val newJob = jobField.get(runner) as Job
            assertTrue(newJob.isActive)
            assertNotEquals(initialJob, newJob)
            assertTrue(initialJob.isCancelled)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `planner retries on transient failure`() = runBlocking {
        val tempDir = Files.createTempDirectory("gemini-runner-retry").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            val executor = mockk<Executor>(relaxed = true)

            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Attempt execution"))
            )
            val planOutput = Content.builder()
                .role("model")
                .parts(Part.builder().text("Recovered answer").build())
                .build()

            every {
                planner.planForStep(
                    any(),
                    any(),
                    any()
                )
            } throws IOException("network glitch") andThen planOutput

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Handle retry",
                history = emptyList()
            )

            val messages = runner.messages.value
            assertEquals(2, messages.size)
            assertEquals("Recovered answer", messages.last().text)
            verify(exactly = 2) { planner.planForStep(any(), any(), any()) }
            verify(exactly = 1) { planner.createInitialPlan(any()) }
            val planState = runner.plan.value!!
            assertEquals(StepStatus.DONE, planState.steps.first().status)
            assertEquals("Recovered answer", planState.steps.first().result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `planner gives up after max retries`() = runBlocking {
        val tempDir = Files.createTempDirectory("gemini-runner-retry-fail").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            val executor = mockk<Executor>(relaxed = true)

            every { planner.createInitialPlan(any()) } returns Plan(
                mutableListOf(TaskStep("Attempt execution"))
            )
            every { planner.planForStep(any(), any(), any()) } throws IOException("flaky network")

            val runner = GeminiAgenticRunner(
                appContext = context,
                maxSteps = 1,
                toolsOverride = emptyList(),
                plannerOverride = planner,
                criticOverride = critic,
                executorOverride = executor
            )

            runner.generateASimpleResponse(
                prompt = "Handle failure",
                history = emptyList()
            )

            val messages = runner.messages.value
            assertEquals(2, messages.size)
            assertEquals("An error occurred: flaky network", messages.last().text)
            verify(exactly = 3) { planner.planForStep(any(), any(), any()) }
            verify(exactly = 1) { planner.createInitialPlan(any()) }
            val planState = runner.plan.value!!
            assertEquals(StepStatus.FAILED, planState.steps.first().status)
            assertEquals("flaky network", planState.steps.first().result)
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
