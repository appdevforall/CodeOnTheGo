package com.itsaky.androidide.agent.actions

import com.itsaky.androidide.agent.repository.AiBackend
import kotlin.text.appendLine

object SelectionAiPromptFactory {

    fun build(context: SelectedCodeContext, agentBackend: AiBackend): String {
        return when (agentBackend) {
            AiBackend.LOCAL_LLM -> buildForLocal(context)
            AiBackend.GEMINI -> buildForGemini(context)
        }
    }

    private fun buildForLocal(context: SelectedCodeContext): String {
        return buildString {
            appendLine("Code:")
            appendLine()
            appendLine(context.selectedText)
            appendLine()

            appendLine("Explain this code in a useful and detailed way.")
            appendLine()
            appendLine("Your explanation must:")
            appendLine("1. Describe what the code does.")
            appendLine("2. Explain how it works step by step.")
            appendLine("3. Mention the most important classes, methods, variables, or conditions in the selection.")
            appendLine("4. Point out any important behavior, side effects, assumptions, or risks visible in the code.")
            appendLine("5. Be clear and helpful for a developer reading the code for the first time.")
            appendLine()
            appendLine("Do not give a shallow answer.")
            appendLine("Do not only restate the file name, method name, or class name.")
            appendLine("If the snippet is incomplete, say what can be inferred from the visible code and mention missing context briefly.")
            appendLine()
            appendLine("Use this response structure:")
            appendLine("- Summary")
            appendLine("- Step-by-step explanation")
            appendLine("- Important details")
            appendLine("- Possible concerns or things to verify")
            appendLine()

            appendLine("Context:")
            append(buildContext(context))
        }
    }

    private fun buildForGemini(context: SelectedCodeContext): String {
        return buildString {
            appendLine("Explain this code:")
            appendLine()
            appendLine(context.selectedText)
            appendLine()

            appendLine("Focus on what the code does and how it works.")
            appendLine("Keep the explanation concise but useful.")
            appendLine()

            appendLine("Context:")
            append(buildContext(context))
        }
    }

    private fun buildContext(context: SelectedCodeContext): String {
        return buildString {
            context.fileName?.let { appendLine("File: $it") }
            context.filePath?.let { appendLine("Path: $it") }
            context.fileExtension?.let { appendLine("File type: $it") }
            if (context.lineStart != null && context.lineEnd != null) {
                appendLine("Lines: ${context.lineStart}-${context.lineEnd}")
            }
        }
    }
}
