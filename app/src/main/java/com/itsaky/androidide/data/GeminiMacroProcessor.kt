package com.itsaky.androidide.data

import com.itsaky.androidide.data.repository.GeminiRepository
import com.itsaky.androidide.editor.processing.ProcessContext
import com.itsaky.androidide.editor.processing.ProcessResult
import com.itsaky.androidide.editor.processing.TextProcessor
import com.itsaky.androidide.projects.IProjectManager
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.TextRange
import java.util.regex.Pattern

/**
 * A TextProcessor to handle code generation via a comment macro like "//ai: <prompt>".
 * It calls the Gemini API and replaces the macro line with the generated code.
 *
 * @param agentRepository An instance of your repository to communicate with the Gemini API.
 */
class GeminiMacroProcessor(
    private val agentRepository: GeminiRepository,
) : TextProcessor {

    private val macroRegex = Pattern.compile("""^.*//ai:\s*(.+)""")

    override fun canProcess(line: String, cursorPosition: Int): Boolean {
        return macroRegex.matcher(line).matches()
    }

    override suspend fun process(context: ProcessContext): ProcessResult? {
        val lineNum = context.cursor.leftLine - 1
        val line = context.content.getLineString(lineNum)
        val matcher = macroRegex.matcher(line)

        if (!matcher.matches()) {
            return null
        }

        val prompt = matcher.group(1)?.trim()
        if (prompt.isNullOrEmpty()) {
            return ProcessResult(
                replacement = "// Error: No prompt provided.",
                range = TextRange(CharPosition(lineNum, 0), CharPosition(lineNum, line.length))
            )
        }

        try {
            val fileContent = context.content.toString()
            val projectRoot = IProjectManager.getInstance().projectDir
            val fileRelativePath = context.file.relativeTo(projectRoot).path
            val fileName = context.file.name

            val generatedCode = agentRepository.generateCode(
                prompt = prompt,
                fileContent = fileContent,
                fileName = fileName,
                fileRelativePath = fileRelativePath
            )

            val indentation = getIndentation(line)
            val indentedCode = generatedCode.lines().joinToString("\n") { "$indentation$it" }

            return ProcessResult(
                replacement = indentedCode,
                range = TextRange(
                    CharPosition(lineNum, 0), CharPosition(lineNum, line.length)
                )
            )
        } catch (e: Exception) {
            val errorMessage = "// Error generating code: ${e.message}"
            return ProcessResult(
                replacement = errorMessage,
                range = TextRange(CharPosition(lineNum, 0), CharPosition(lineNum, line.length))
            )
        }
    }

    private fun getIndentation(line: String): String {
        return line.takeWhile { it.isWhitespace() }
    }
}