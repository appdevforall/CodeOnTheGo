package org.appdevforall.codeonthego.computervision.utils

import com.google.mlkit.vision.text.Text

object OcrTextAssembler {
    const val DEFAULT_SPACE_GAP_TOLERANCE_PX = 15f

    fun extractTextWithTolerance(
        textBlocks: List<Text.TextBlock>,
        maxSpaceGap: Float = DEFAULT_SPACE_GAP_TOLERANCE_PX
    ): String {
        return textBlocks.joinToString(" ") { block ->
            block.lines.joinToString(" ") { line ->
                joinElementsWithTolerance(line, maxSpaceGap)
            }
        }.trim()
    }

    fun joinElementsWithTolerance(line: Text.Line, maxSpaceGap: Float = DEFAULT_SPACE_GAP_TOLERANCE_PX): String {
        val elements = line.elements.sortedBy { it.boundingBox?.left ?: 0 }
        if (elements.isEmpty()) return ""

        val builder = StringBuilder()
        var prevRight = elements.first().boundingBox?.right ?: 0
        builder.append(elements.first().text)

        for (i in 1 until elements.size) {
            val current = elements[i]
            val currentBox = current.boundingBox

            if (currentBox != null) {
                val gap = currentBox.left - prevRight
                if (gap > maxSpaceGap) {
                    builder.append(" ")
                }
                prevRight = currentBox.right
            } else {
                builder.append(" ")
            }
            builder.append(current.text)
        }
        return builder.toString().trim()
    }
}
