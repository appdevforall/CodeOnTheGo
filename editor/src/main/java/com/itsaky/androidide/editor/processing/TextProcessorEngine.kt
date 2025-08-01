// in: com/itsaky/androidide/editor/processing/TextProcessorEngine.kt
package com.itsaky.androidide.editor.processing

class TextProcessorEngine {

    companion object {
        val additionalProcessors = mutableListOf<TextProcessor>()
    }

    // The order here is important. The first processor to handle the text wins.
    private val processors = listOf(
        SimpleMacroProcessor(),
    ) + additionalProcessors

    suspend fun process(context: ProcessContext): ProcessResult? {
        if (context.cursor.isSelected) return null

        val line = context.content.getLineString(context.cursor.leftLine)
        val cursorCol = context.cursor.leftColumn

        for (processor in processors) {
            if (processor.canProcess(line, cursorCol)) {
                // Return the result from the first processor that can handle it
                return processor.process(context)
            }
        }

        return null
    }
}