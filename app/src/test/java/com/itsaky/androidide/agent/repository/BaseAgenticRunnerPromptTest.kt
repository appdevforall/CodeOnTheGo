package com.itsaky.androidide.agent.repository

import android.content.Context
import com.google.genai.types.Content
import com.itsaky.androidide.agent.ChatMessage
import com.itsaky.androidide.agent.Sender
import com.itsaky.androidide.agent.prompt.ModelFamily
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import kotlin.jvm.optionals.getOrNull

class BaseAgenticRunnerPromptTest {

    private lateinit var context: Context
    private val testModelFamily = ModelFamily(
        id = "unknown",
        baseInstructions = "BASE INSTRUCTIONS",
        supportsParallelToolCalls = false
    )

    @Before
    fun setUp() {
        context = Mockito.mock(Context::class.java)
    }

    @Test
    fun `buildInitialContent preserves user prompt and wraps instructions`() {
        val runner = PromptTestRunner(context)
        val environment = """
            <environment_context>
              <cwd>/tmp/project</cwd>
              <approval_policy>on-request</approval_policy>
              <sandbox_mode>workspace-write</sandbox_mode>
              <network_access>restricted</network_access>
              <shell>bash</shell>
            </environment_context>
        """.trimIndent()
        val userPrompt = "create an app that doubles a number"

        runner.prepareMessages(
            listOf(
                ChatMessage(text = environment, sender = Sender.SYSTEM),
                ChatMessage(text = userPrompt, sender = Sender.USER),
                ChatMessage(text = "", sender = Sender.AGENT)
            )
        )
        runner.setEnvironmentContextSnapshotForTest(environment)
        runner.setLatestUserPromptForTest(userPrompt)

        val content = runner.invokeBuildInitialContent()
        val firstPart = content.parts()?.getOrNull()?.firstOrNull()
        val payload = firstPart?.text()?.getOrNull().orEmpty()

        assertTrue(payload.contains("<user_instructions>"))
        assertTrue(payload.contains(userPrompt))
        assertTrue(payload.contains(environment.trim()))
    }

    @Test
    fun `buildInitialContent injects fallback user message when missing`() {
        val runner = PromptTestRunner(context)
        val environment = "<environment_context><cwd>/tmp</cwd></environment_context>"
        val userPrompt = "fallback prompt content"

        runner.prepareMessages(
            listOf(
                ChatMessage(text = environment, sender = Sender.SYSTEM),
                ChatMessage(text = "", sender = Sender.AGENT)
            )
        )
        runner.setEnvironmentContextSnapshotForTest(environment)
        runner.setLatestUserPromptForTest(userPrompt)

        val content = runner.invokeBuildInitialContent()
        val firstPart = content.parts()?.getOrNull()?.firstOrNull()
        val payload = firstPart?.text()?.getOrNull().orEmpty()

        assertTrue(payload.contains("<user_instructions>"))
        assertTrue(payload.contains(userPrompt))
    }

    private inner class PromptTestRunner(context: Context) : BaseAgenticRunner(
        context = context,
        modelFamily = testModelFamily,
        maxSteps = 1,
        toolsOverride = emptyList()
    ) {

        override fun baseInstructions(): String = "BASE INSTRUCTIONS"

        fun prepareMessages(messages: List<ChatMessage>) {
            loadHistory(messages)
        }

        fun setLatestUserPromptForTest(value: String) {
            BaseAgenticRunner::class.java.getDeclaredField("latestUserPrompt").apply {
                isAccessible = true
                set(this@PromptTestRunner, value)
            }
        }

        fun setEnvironmentContextSnapshotForTest(value: String) {
            BaseAgenticRunner::class.java.getDeclaredField("environmentContextSnapshot").apply {
                isAccessible = true
                set(this@PromptTestRunner, value)
            }
        }

        fun invokeBuildInitialContent(): Content = buildInitialContent()

        override suspend fun createInitialPlan(history: List<Content>): Plan {
            error("Not required for prompt tests")
        }

        override suspend fun planForStep(
            history: List<Content>,
            plan: Plan,
            stepIndex: Int
        ): Content {
            error("Not required for prompt tests")
        }
    }
}
