package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.types.Content
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.prompt.ModelFamily
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BaseAgenticRunnerRetryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = Mockito.mock(Context::class.java)
    }

    @Test
    fun `runWithRetry returns immediately on success`() = runTest {
        val runner = TestRunner(context)

        val result = runner.invokeRunWithRetry("simple_operation") {
            "success"
        }

        assertEquals("success", result)
        assertTrue("No retry messages should be recorded", runner.messages().isEmpty())
    }

    @Test
    fun `runWithRetry retries network errors and records progress`() = runTest {
        val runner = TestRunner(context)
        var attempts = 0

        val result = runner.invokeRunWithRetry("network_call") {
            attempts++
            if (attempts < 3) {
                throw IOException("temporary outage")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals("Expected three attempts", 3, attempts)

        val messages = runner.messages()
        assertEquals("Two interim retry messages expected", 2, messages.size)
        assertEquals(
            "⚠️ Network issue while operation 'network call' (attempt 1/5): temporary outage. Retrying in 100ms...",
            messages[0].text
        )
        assertEquals(
            "⚠️ Network issue while operation 'network call' (attempt 2/5): temporary outage. Retrying in 200ms...",
            messages[1].text
        )
    }

    @Test
    fun `runWithRetry stops after max attempts and reports failure`() = runTest {
        val runner = TestRunner(context)
        var attempts = 0

        val exception = kotlin.runCatching {
            runner.invokeRunWithRetry("critical_request") {
                attempts++
                throw IOException("offline")
            }
        }.exceptionOrNull()

        assertTrue("Expected IOException after retries", exception is IOException)
        assertEquals("Expected max attempts", 5, attempts)

        val messages = runner.messages()
        assertEquals("Four interim warnings plus final failure", 5, messages.size)
        assertEquals(
            "⚠️ Network issue while operation 'critical request' (attempt 1/5): offline. Retrying in 100ms...",
            messages[0].text
        )
        assertEquals(
            "⚠️ Network issue while operation 'critical request' (attempt 2/5): offline. Retrying in 200ms...",
            messages[1].text
        )
        assertEquals(
            "⚠️ Network issue while operation 'critical request' (attempt 3/5): offline. Retrying in 400ms...",
            messages[2].text
        )
        assertEquals(
            "⚠️ Network issue while operation 'critical request' (attempt 4/5): offline. Retrying in 800ms...",
            messages[3].text
        )
        assertEquals(
            "❌ Network error while operation 'critical request' after 5 attempts: offline. Please check your connection and try again.",
            messages[4].text
        )
    }

    private class TestRunner(context: Context) : BaseAgenticRunner(
        context = context,
        modelFamily = ModelFamily.UNKNOWN,
        maxSteps = 1,
        toolsOverride = emptyList()
    ) {

        suspend fun <T> invokeRunWithRetry(
            operationName: String,
            block: suspend () -> T
        ): T = runWithRetry(operationName, block)

        fun messages(): List<ChatMessage> = messages.value

        override suspend fun createInitialPlan(history: List<Content>): Plan {
            error("Not required for retry tests")
        }

        override suspend fun planForStep(
            history: List<Content>,
            plan: Plan,
            stepIndex: Int
        ): Content {
            error("Not required for retry tests")
        }
    }
}
