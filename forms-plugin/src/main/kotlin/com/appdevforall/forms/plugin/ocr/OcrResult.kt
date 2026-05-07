package com.appdevforall.forms.plugin.ocr

/**
 * OCR output adapter — mirrors ML Kit's `Text` shape but doesn't depend on
 * Android. Pure-JVM data; can be constructed from synthetic fixtures (in
 * unit tests) or from ML Kit's [com.google.mlkit.vision.text.Text] at
 * runtime via [MlKitOcrAdapter].
 *
 * The hierarchy mirrors ML Kit Text Recognition v2:
 *
 *     Text -> TextBlock -> Line -> Element
 *
 * Each level has its own bounding box. For the heuristic classifier we
 * mostly care about [OcrLine] and [OcrElement] — labels, masks, glyphs are
 * usually one element each, and "label + mask on the same line" is the
 * dominant paper-form pattern.
 *
 * Coordinates are in image pixels. Origin is top-left, x increases right,
 * y increases down — same as Android `Rect` and ML Kit's `Rect`. We don't
 * use `android.graphics.Rect` here so the classifier and its tests remain
 * pure-JVM (no Robolectric needed).
 */
data class OcrResult(val blocks: List<OcrBlock>) {

    /** Flatten to all lines across all blocks, preserving block then line order. */
    fun allLines(): List<OcrLine> = blocks.flatMap { it.lines }

    /** Flatten to all elements across all blocks/lines. */
    fun allElements(): List<OcrElement> = blocks.flatMap { b -> b.lines.flatMap { it.elements } }
}

data class OcrBlock(
    val text: String,
    val bounds: Rect,
    val lines: List<OcrLine>,
)

data class OcrLine(
    val text: String,
    val bounds: Rect,
    val elements: List<OcrElement>,
)

data class OcrElement(
    val text: String,
    val bounds: Rect,
)

/**
 * Pure-data rectangle. Mirrors `android.graphics.Rect` semantics
 * (right/bottom are exclusive in Android, but for OCR bounds we treat them
 * as the inclusive far edge — ML Kit uses the same convention).
 */
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2
    val centerX: Int get() = (left + right) / 2

    /** Vertical overlap fraction with [other], 0..1. Used to test "on the same line". */
    fun verticalOverlapFraction(other: Rect): Double {
        val overlap = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        val minHeight = minOf(height, other.height).coerceAtLeast(1)
        return overlap.toDouble() / minHeight.toDouble()
    }
}
