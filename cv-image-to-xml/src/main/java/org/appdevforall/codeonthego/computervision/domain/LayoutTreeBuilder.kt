package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import kotlin.math.abs
import kotlin.math.max

/**
 * Analyzes the spatial distribution of the detected boxes and builds
 * a logical visual hierarchy (tree of Layouts, Rows, RadioGroups, etc.)
 * based on vertical alignment and overlap.
 */
object LayoutTreeBuilder {
    private const val OVERLAP_THRESHOLD = 0.6
    private const val VERTICAL_ALIGN_THRESHOLD = 20

    fun buildLayoutTree(boxes: List<ScaledBox>): List<LayoutItem> {
        val rows = groupIntoRows(boxes)
        val items = mutableListOf<LayoutItem>()
        val verticalRadioRun = mutableListOf<ScaledBox>()
        val verticalCheckboxRun = mutableListOf<ScaledBox>()

        fun flushRuns() {
            if (verticalRadioRun.isNotEmpty()) {
                items.add(LayoutItem.RadioGroup(verticalRadioRun.toList(), "vertical"))
                verticalRadioRun.clear()
            }
            if (verticalCheckboxRun.isNotEmpty()) {
                items.add(LayoutItem.CheckboxGroup(verticalCheckboxRun.toList(), "vertical"))
                verticalCheckboxRun.clear()
            }
        }

        rows.forEach { row ->
            val isRadioRow = row.all { isRadioButton(it) }
            val isCheckboxRow = row.all { isCheckbox(it) }

            if (!isRadioRow && verticalRadioRun.isNotEmpty()) flushRuns()
            if (!isCheckboxRow && verticalCheckboxRun.isNotEmpty()) flushRuns()

            when {
                isRadioRow && row.size == 1 -> verticalRadioRun.add(row.first())
                isRadioRow -> items.add(LayoutItem.RadioGroup(row, "horizontal"))
                isCheckboxRow && row.size == 1 -> verticalCheckboxRun.add(row.first())
                isCheckboxRow -> items.add(LayoutItem.CheckboxGroup(row, "horizontal"))
                else -> {
                    flushRuns()
                    if (row.size == 1) {
                        items.add(LayoutItem.SimpleView(row.first()))
                    } else {
                        items.add(LayoutItem.HorizontalRow(row))
                    }
                }
            }
        }
        flushRuns()

        return items
    }

    private fun groupIntoRows(boxes: List<ScaledBox>): List<List<ScaledBox>> {
        val rows = mutableListOf<LayoutRow>()

        boxes.sortedWith(compareBy({ it.y }, { it.x })).forEach { box ->
            val row = rows.firstOrNull { it.accepts(box) }
            if (row == null) {
                rows.add(LayoutRow(box))
            } else {
                row.add(box)
            }
        }

        return rows.sortedBy { it.top }.map { it.boxes.sortedBy(ScaledBox::x) }
    }

    private fun isRadioButton(box: ScaledBox): Boolean =
        box.label == "radio_button_unchecked" || box.label == "radio_button_checked"

    private fun isCheckbox(box: ScaledBox): Boolean =
        box.label == "checkbox_unchecked" || box.label == "checkbox_checked"

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
            val centerDelta = abs(box.centerY - centerY)
            val centerThreshold = max(VERTICAL_ALIGN_THRESHOLD, minHeight / 2)

            return overlapRatio >= OVERLAP_THRESHOLD || centerDelta <= centerThreshold
        }
    }
}
