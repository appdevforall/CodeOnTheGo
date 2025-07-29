package com.itsaky.androidide.editor.processing

import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.TextRange

class SimpleMacroProcessor : TextProcessor {

    private val macro = "//abc "

    override fun canProcess(line: String, cursorPosition: Int): Boolean {
        // Check if the macro text appears right before the cursor
        val start = cursorPosition - macro.length
        return start >= 0 && line.substring(start, cursorPosition) == macro
    }

    override suspend fun process(context: ProcessContext): ProcessResult {
        val line = context.cursor.rightLine
        val column = context.cursor.rightColumn

        val startColumn = column - macro.length
        val endColumn = column

        return ProcessResult(
            replacement = "//def ",
            range = TextRange(
                CharPosition(line, startColumn), CharPosition(line, endColumn)
            )
        )
    }
}