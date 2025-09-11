package com.itsaky.androidide.editor.processing

class TextProcessorEngine {

    companion object {
        val additionalProcessors = mutableListOf<TextProcessor>()
    }

    // The order here is important. The first processor to handle the text wins.
    private val processors = listOf(
        SimpleMacroProcessor(),
    ) + additionalProcessors

    /**
     * @param context The current editor context.
     * @param isEnterPress True if this check was triggered by a newline insertion.
     */
    suspend fun process(context: ProcessContext, isEnterPress: Boolean): ProcessResult? {
        // The processor should only run on an Enter press and if there's no text selection.
        if (!isEnterPress || context.cursor.isSelected) return null

        // We check the line where the cursor was *before* the Enter key was pressed.
        val lineToProcess = context.cursor.leftLine - 1
        val lineContent = context.content.getLineString(lineToProcess)
        val cursorCol = context.content.getLine(lineToProcess).length

        for (processor in processors) {
            if (processor.canProcess(lineContent, cursorCol)) {
                return processor.process(context)
            }
        }

        return null
    }
}