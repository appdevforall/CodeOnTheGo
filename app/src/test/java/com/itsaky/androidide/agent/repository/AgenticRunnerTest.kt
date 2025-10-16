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

class AgenticRunnerTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `generateASimpleResponse updates messages with planner text`() = runBlocking {
        val tempDir = Files.createTempDirectory("agentic-runner").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            val executor = mockk<Executor>(relaxed = true)

            val planOutput = Content.builder()
                .role("model")
                .parts(Part.builder().text("Final answer").build())
                .build()

            every { planner.plan(any()) } returns planOutput

            val runner = AgenticRunner(
                context = context,
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
            verify(exactly = 1) { planner.plan(any()) }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `stop cancels current job and replaces runner scope`() {
        val tempDir = Files.createTempDirectory("agentic-runner-stop").toFile()
        try {
            val context = mockContext(tempDir)
            val runner = AgenticRunner(
                context = context,
                toolsOverride = emptyList(),
                plannerOverride = mockk<Planner>(relaxed = true),
                criticOverride = mockk<Critic>(relaxed = true),
                executorOverride = mockk<Executor>(relaxed = true)
            )

            val jobField = AgenticRunner::class.java.getDeclaredField("runnerJob").apply {
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
        val tempDir = Files.createTempDirectory("agentic-runner-retry").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            val executor = mockk<Executor>(relaxed = true)

            val planOutput = Content.builder()
                .role("model")
                .parts(Part.builder().text("Recovered answer").build())
                .build()

            every { planner.plan(any()) } throws IOException("network glitch") andThen planOutput

            val runner = AgenticRunner(
                context = context,
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
            verify(exactly = 2) { planner.plan(any()) }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `planner gives up after max retries`() = runBlocking {
        val tempDir = Files.createTempDirectory("agentic-runner-retry-fail").toFile()
        try {
            val context = mockContext(tempDir)
            val planner = mockk<Planner>()
            val critic = mockk<Critic>(relaxed = true)
            val executor = mockk<Executor>(relaxed = true)

            every { planner.plan(any()) } throws IOException("flaky network")

            val runner = AgenticRunner(
                context = context,
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
            verify(exactly = 3) { planner.plan(any()) }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun mockContext(tempDir: File): Context {
        val context = mockk<Context>()
        val assetManager = mockk<AssetManager>()
        every { context.assets } returns assetManager
        every { assetManager.open(any()) } throws FileNotFoundException("missing")
        every { context.getExternalFilesDir(null) } returns tempDir
        return context
    }
}
