package com.appdevforall.forms.plugin.ocr

/**
 * Compact test DSL for constructing synthetic [OcrResult] inputs.
 *
 * Real ML Kit output is a tree of `Text -> TextBlock -> Line -> Element`
 * with bounding rects on every node. Building those by hand in test code
 * is unbearable, so this DSL gives sensible defaults: blocks at given
 * (x, y) origins, lines stacked vertically inside the block at a fixed
 * line-height, elements split on whitespace with synthetic horizontal
 * positions.
 *
 * Example:
 *
 *     val ocr = ocr {
 *         block(0, 0) {
 *             line("VACCINATION CAMP — INTAKE FORM", style = Style.LARGE_BOLD)
 *         }
 *         block(0, 60) {
 *             line("Name: ___________________________")
 *         }
 *         block(0, 90) {
 *             line("DOB: __ / __ / ____")
 *         }
 *         block(0, 120) {
 *             line("☐ Pregnant?")
 *         }
 *     }
 *
 * The DSL is approximate — it doesn't try to model real OCR jitter. It's
 * for stable, readable assertions about the classifier; the real ML Kit
 * coverage lives in the (deferred) instrumented-test suite.
 */

/** Visual emphasis hint used to set realistic line heights. */
internal enum class Style(val height: Int) {
    NORMAL(20),
    LARGE_BOLD(40),
    SMALL(14),
}

internal class OcrBuilder {
    private val blocks = mutableListOf<OcrBlock>()

    fun block(x: Int, y: Int, init: BlockBuilder.() -> Unit) {
        val b = BlockBuilder(x, y).apply(init)
        blocks += b.build()
    }

    fun build(): OcrResult = OcrResult(blocks)
}

internal class BlockBuilder(private val originX: Int, private val originY: Int) {
    private val lines = mutableListOf<OcrLine>()
    private var nextY = originY

    /**
     * Add a line to this block. By default, the line is positioned at the
     * next vertical slot (originY + cumulative line heights). Pass [yOverride]
     * to position it explicitly.
     */
    fun line(
        text: String,
        style: Style = Style.NORMAL,
        x: Int = originX,
        yOverride: Int? = null,
        elementCharWidth: Int = 12,
    ) {
        val y = yOverride ?: nextY
        val height = style.height
        // Split the line into elements on whitespace; each element gets a
        // synthetic horizontal extent based on character count.
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val elements = mutableListOf<OcrElement>()
        var cursor = x
        for (token in tokens) {
            val width = token.length * elementCharWidth
            elements += OcrElement(
                text = token,
                bounds = Rect(cursor, y, cursor + width, y + height),
            )
            cursor += width + elementCharWidth // gap = one char-width
        }
        val lineRight = elements.lastOrNull()?.bounds?.right ?: x
        lines += OcrLine(
            text = text,
            bounds = Rect(x, y, lineRight, y + height),
            elements = elements,
        )
        nextY = y + height + 4 // small gutter between lines
    }

    fun build(): OcrBlock {
        val bounds = if (lines.isEmpty()) {
            Rect(originX, originY, originX, originY)
        } else {
            Rect(
                left = lines.minOf { it.bounds.left },
                top = lines.minOf { it.bounds.top },
                right = lines.maxOf { it.bounds.right },
                bottom = lines.maxOf { it.bounds.bottom },
            )
        }
        return OcrBlock(
            text = lines.joinToString("\n") { it.text },
            bounds = bounds,
            lines = lines.toList(),
        )
    }
}

internal fun ocr(init: OcrBuilder.() -> Unit): OcrResult =
    OcrBuilder().apply(init).build()

/**
 * Build an [OcrLine] directly from text + position. Useful for tests that
 * exercise a single heuristic primitive without the [ocr] DSL ceremony.
 */
internal fun lineOf(
    text: String,
    left: Int = 0,
    top: Int = 0,
    height: Int = 20,
    elementCharWidth: Int = 12,
): OcrLine {
    val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val elements = mutableListOf<OcrElement>()
    var cursor = left
    for (token in tokens) {
        val width = token.length * elementCharWidth
        elements += OcrElement(
            text = token,
            bounds = Rect(cursor, top, cursor + width, top + height),
        )
        cursor += width + elementCharWidth
    }
    val right = elements.lastOrNull()?.bounds?.right ?: left
    return OcrLine(
        text = text,
        bounds = Rect(left, top, right, top + height),
        elements = elements,
    )
}
