package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import kotlin.math.max
import kotlin.math.roundToInt

class LayoutGeometryProcessor {
    companion object {
        private const val MIN_W_ANY = 8
        private const val MIN_H_ANY = 8
        private const val OVERLAP_THRESHOLD = 0.6
        private const val VERTICAL_ALIGN_THRESHOLD = 20
    }

    private fun isRadioButton(box: ScaledBox): Boolean =
        box.label == "radio_button_unchecked" || box.label == "radio_button_checked"

    private class LayoutRow(initialBox: ScaledBox) {
        private val _boxes = mutableListOf(initialBox)
        val boxes: List<ScaledBox> get() = _boxes

        var top: Int = initialBox.y
            private set
        var bottom: Int = initialBox.y + initialBox.h
            private set

        val height: Int get() = bottom - top
        val centerY: Int get() = top + height / 2

        fun add(box: ScaledBox) {
            _boxes.add(box)
            top = minOf(top, box.y)
            bottom = maxOf(bottom, box.y + box.h)
        }

        fun accepts(box: ScaledBox): Boolean {
            val verticalOverlap = minOf(box.y + box.h, bottom) - maxOf(box.y, top)
            val minHeight = minOf(box.h, height).coerceAtLeast(1)
            val overlapRatio = verticalOverlap.toFloat() / minHeight.toFloat()
            val centerDelta = kotlin.math.abs(box.centerY - centerY)
            val centerThreshold = max(VERTICAL_ALIGN_THRESHOLD, minHeight / 2)

            return overlapRatio >= OVERLAP_THRESHOLD || centerDelta <= centerThreshold
        }
    }

    internal fun assignTextToParents(parents: List<ScaledBox>, texts: List<ScaledBox>, allBoxes: List<ScaledBox>): List<ScaledBox> {
        val consumedTexts = mutableSetOf<ScaledBox>()
        val updatedParents = mutableMapOf<ScaledBox, ScaledBox>()

        for (parent in parents) {
            texts.firstOrNull { text ->
                !consumedTexts.contains(text) &&
                    Rect(parent.rect).let { intersection ->
                        intersection.intersect(text.rect) &&
                            (intersection.width() * intersection.height()).let { intersectionArea ->
                                val textArea = text.w * text.h
                                textArea > 0 && (intersectionArea.toFloat() / textArea.toFloat()) > OVERLAP_THRESHOLD
                            }
                    }
            }?.let {
                updatedParents[parent] = parent.copy(text = it.text)
                consumedTexts.add(it)
            }
        }

        return allBoxes.mapNotNull { box ->
            when {
                consumedTexts.contains(box) -> null
                updatedParents.containsKey(box) -> updatedParents[box]
                else -> box
            }
        }
    }

    internal fun groupIntoRows(boxes: List<ScaledBox>): List<List<ScaledBox>> {
        val rows = mutableListOf<LayoutRow>()

        boxes.sortedWith(compareBy({ it.y }, { it.x })).forEach { box ->
            val row = rows.firstOrNull { it.accepts(box) }
            if (row == null) {
                rows.add(LayoutRow(box))
            } else {
                row.add(box)
            }
        }

        return rows
            .sortedBy { it.top }
            .map { it.boxes.sortedBy(ScaledBox::x) }
    }

    internal fun scaleDetection(
        detection: DetectionResult, sourceWidth: Int, sourceHeight: Int, targetW: Int, targetH: Int
    ): ScaledBox {
        if (sourceWidth == 0 || sourceHeight == 0) {
            return ScaledBox(detection.label, detection.text, 0, 0, MIN_W_ANY, MIN_H_ANY, MIN_W_ANY / 2, MIN_H_ANY / 2, Rect(0, 0, MIN_W_ANY, MIN_H_ANY))
        }
        val rect = detection.boundingBox
        val normCx = ((rect.left + rect.right) / 2f) / sourceWidth.toFloat()
        val normCy = ((rect.top + rect.bottom) / 2f) / sourceHeight.toFloat()
        val normW = (rect.right - rect.left) / sourceWidth
        val normH = (rect.bottom - rect.top) / sourceHeight
        val x = max(0, ((normCx - normW / 2.0) * targetW).roundToInt())
        val y = max(0, ((normCy - normH / 2.0) * targetH).roundToInt())
        val w = max(MIN_W_ANY, (normW * targetW).roundToInt())
        val h = max(MIN_H_ANY, (normH * targetH).roundToInt())
        return ScaledBox(
            detection.label,
            detection.text,
            x,
            y,
            w,
            h,
            x + w / 2,
            y + h / 2,
            Rect(x, y, x + w, y + h)
        )
    }

    private fun mergeRadioLabels(row: List<ScaledBox>): List<ScaledBox> {
        val merged = mutableListOf<ScaledBox>()
        var i = 0

        while (i < row.size) {
            val current = row[i]

            if (isRadioButton(current) && i + 1 < row.size && row[i + 1].label == "text") {
                val nextText = row[i + 1]
                merged.add(current.copy(text = nextText.text))
                i += 2
            }
            else if (current.label == "text" && i + 1 < row.size && isRadioButton(row[i + 1])) {
                val nextRadio = row[i + 1]
                merged.add(nextRadio.copy(text = current.text))
                i += 2
            }
            else {
                merged.add(current)
                i++
            }
        }
        return merged
    }

    internal fun buildLayoutTree(boxes: List<ScaledBox>): List<LayoutItem> {
        val rows = groupIntoRows(boxes)
        val items = mutableListOf<LayoutItem>()
        val verticalRadioRun = mutableListOf<ScaledBox>()

        fun flushVerticalRadioRun() {
            if (verticalRadioRun.isNotEmpty()) {
                items.add(LayoutItem.RadioGroup(verticalRadioRun.toList(), "vertical"))
                verticalRadioRun.clear()
            }
        }

        rows.forEach { rawRow ->
            val row = mergeRadioLabels(rawRow)
            when {
                row.all { isRadioButton(it) } && row.size == 1 -> verticalRadioRun.add(row.first())
                row.all { isRadioButton(it) } -> {
                    flushVerticalRadioRun()
                    items.add(LayoutItem.RadioGroup(row, "horizontal"))
                }
                else -> {
                    flushVerticalRadioRun()
                    if (row.size == 1) {
                        items.add(LayoutItem.SimpleView(row.first()))
                    } else {
                        items.add(LayoutItem.HorizontalRow(row))
                    }
                }
            }
        }
        flushVerticalRadioRun()

        return items
    }
}
