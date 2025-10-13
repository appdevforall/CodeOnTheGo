package com.itsaky.androidide.editor.processing

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.Cursor
import io.github.rosemoe.sora.text.TextRange
import java.io.File

/**
 * Defines a processor that can analyze and transform text in the editor.
 */
interface TextProcessor {
    /**
     * A quick check to see if this processor should even attempt to handle the change.
     * This should be a lightweight operation (e.g., a regex match on the current line).
     *
     * @param line The text of the line where the cursor is.
     * @param cursorPosition The column of the cursor on that line.
     * @return True if this processor might handle the text, false otherwise.
     */
    fun canProcess(line: String, cursorPosition: Int): Boolean

    /**
     * Performs the actual processing. This can be a long-running operation.
     *
     * @param context The full context of the editor state.
     * @return A [ProcessResult] containing the text to insert and the range to replace,
     * or null if this processor cannot handle the request.
     */
    suspend fun process(context: ProcessContext): ProcessResult?
}

/**
 * Data class holding all the information a TextProcessor might need.
 */
data class ProcessContext(
    val content: Content,
    val file: File,
    val cursor: Cursor
)

/**
 * Data class representing the result of a text processing operation.
 */
data class ProcessResult(
    val replacement: String,
    val range: TextRange
)