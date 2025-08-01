package com.itsaky.androidide.data

import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.editor.processing.ProcessContext
import com.itsaky.androidide.editor.processing.ProcessResult
import com.itsaky.androidide.editor.processing.TextProcessor
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.TextRange
import java.util.regex.Pattern

/**
 * A TextProcessor to handle code generation via a comment macro like "//ai: <prompt>".
 * It calls the Gemini API and replaces the macro line with the generated code.
 *
 * @param geminiRepository An instance of your repository to communicate with the Gemini API.
 */
class GeminiMacroProcessor(private val geminiRepository: GeminiRepository) : TextProcessor {

    private val macroRegex = Pattern.compile("""^.*//ai:\s*(.+)""")

    /**
     * The engine now confirms this is an "Enter" press.
     * This method just needs to confirm the line is a valid AI macro.
     */
    override fun canProcess(line: String, cursorPosition: Int): Boolean {
        return macroRegex.matcher(line).matches()
    }

    /**
     * Processes the macro: calls Gemini and returns the generated code.
     */
    override suspend fun process(context: ProcessContext): ProcessResult? {
        val lineNum = context.cursor.leftLine - 1
        val line = context.content.getLineString(lineNum)
        val matcher = macroRegex.matcher(line)

        // If the regex doesn't match, we can't process it.
        // This is a safeguard in case canProcess was too lenient.
        if (!matcher.matches()) {
            return null
        }

        // Extract the prompt from the regex capture group.
        val prompt = matcher.group(1)?.trim()
        if (prompt.isNullOrEmpty()) {
            return ProcessResult(
                replacement = "// Error: No prompt provided.",
                range = TextRange(CharPosition(lineNum, 0), CharPosition(lineNum, line.length))
            )
        }

        try {
            val generatedCode = geminiRepository.generateCode(prompt)

            // Preserve the indentation of the original line.
            val indentation = getIndentation(line)
            val indentedCode = generatedCode.lines().joinToString("\n") { "$indentation$it" }

            return ProcessResult(
                // The replacement text is the indented code from Gemini.
                replacement = indentedCode,
                // The range to replace is the entire line containing the macro.
                range = TextRange(
                    CharPosition(lineNum, 0), CharPosition(lineNum, line.length)
                )
            )
        } catch (e: Exception) {
            // Handle exceptions (e.g., network error) gracefully.
            val errorMessage = "// Error generating code: ${e.message}"
            return ProcessResult(
                replacement = errorMessage,
                range = TextRange(CharPosition(lineNum, 0), CharPosition(lineNum, line.length))
            )
        }
    }

    /**
     * A helper function to get the leading whitespace (indentation) from a line.
     */
    private fun getIndentation(line: String): String {
        return line.takeWhile { it.isWhitespace() }
    }
}