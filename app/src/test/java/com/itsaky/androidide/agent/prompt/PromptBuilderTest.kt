package com.itsaky.androidide.agent.prompt

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.jvm.optionals.getOrNull

class PromptBuilderTest {

    private val modelFamily = ModelFamily(
        id = "test-model",
        baseInstructions = "Default instructions.",
        supportsParallelToolCalls = true,
        needsSpecialApplyPatchInstructions = true
    )

    @Test
    fun `getFullInstructions uses override when provided`() {
        val prompt = Prompt(
            input = emptyList(),
            tools = emptyList(),
            parallelToolCalls = false,
            baseInstructionsOverride = "You are a pirate.",
            outputSchema = null
        )

        val instructions = prompt.getFullInstructions(modelFamily)
        assertTrue(instructions.startsWith("You are a pirate."))
        assertFalse(instructions.contains("Default instructions."))
    }

    @Test
    fun `getFullInstructions appends schema instructions when schema is provided`() {
        val schema = buildJsonObject {
            put("type", "object")
        }
        val prompt = Prompt(
            input = emptyList(),
            tools = emptyList(),
            parallelToolCalls = false,
            baseInstructionsOverride = null,
            outputSchema = schema
        )

        val instructions = prompt.getFullInstructions(modelFamily)
        assertTrue(instructions.contains("You MUST respond with a single, valid JSON object"))
        assertTrue(instructions.contains("\"type\": \"object\""))
    }

    @Test
    fun `getFullInstructions appends special instructions when apply_patch missing`() {
        val prompt = Prompt(
            input = emptyList(),
            tools = emptyList(),
            parallelToolCalls = false,
            baseInstructionsOverride = null,
            outputSchema = null
        )

        val instructions = prompt.getFullInstructions(modelFamily)
        assertTrue(instructions.contains("[Special Instructions]"))
        assertTrue(instructions.contains("update_file"))
    }

    @Test
    fun `getFullInstructions does not append special instructions when apply_patch present`() {
        val prompt = Prompt(
            input = emptyList(),
            tools = listOf(toolWithFunction("apply_patch")),
            parallelToolCalls = false,
            baseInstructionsOverride = null,
            outputSchema = null
        )

        val instructions = prompt.getFullInstructions(modelFamily)
        assertFalse(instructions.contains("[Special Instructions]"))
    }

    @Test
    fun `getFormattedInput wraps only the last user message with semantic tags`() {
        val prompt = Prompt(
            input = listOf(
                ResponseItem.Message(role = "user", content = "Earlier instructions"),
                ResponseItem.Message(role = "assistant", content = "Acknowledged"),
                ResponseItem.Message(role = "user", content = "Final request")
            ),
            tools = emptyList(),
            parallelToolCalls = false,
            baseInstructionsOverride = null,
            outputSchema = null
        )

        val formatted = prompt.getFormattedInput()
        val firstUser = formatted.first() as ResponseItem.Message
        val lastUser = formatted.last() as ResponseItem.Message

        assertEquals("Earlier instructions", firstUser.content)
        assertTrue(lastUser.content.startsWith("<user_instructions>"))
        assertTrue(lastUser.content.endsWith("</user_instructions>"))
    }

    @Test
    fun `buildMessagesForChatAPI combines instructions and maps roles correctly`() {
        val turn = TurnContext(
            modelFamily = modelFamily,
            toolsConfig = emptyList(),
            baseInstructionsOverride = "Focus on the user's final prompt."
        )
        val prompt = buildPrompt(
            turn,
            listOf(
                ResponseItem.Message(role = "user", content = "Do the thing."),
                ResponseItem.Message(role = "assistant", content = "Working on it.")
            )
        )

        val messages = buildMessagesForChatAPI(prompt, modelFamily)

        assertEquals(2, messages.size)

        val firstText =
            messages[0].parts().getOrNull()?.firstOrNull()?.text()?.getOrNull().orEmpty()
        assertTrue(firstText.contains("Focus on the user's final prompt."))
        assertTrue(firstText.contains("Do the thing."))

        val secondRole = messages[1].role().getOrNull()
        val secondText =
            messages[1].parts().getOrNull()?.firstOrNull()?.text()?.getOrNull().orEmpty()

        assertEquals("model", secondRole)
        assertEquals("Working on it.", secondText)
    }

    private fun toolWithFunction(name: String): Tool {
        val declaration = FunctionDeclaration.builder()
            .name(name)
            .build()
        return Tool.builder()
            .functionDeclarations(declaration)
            .build()
    }
}
