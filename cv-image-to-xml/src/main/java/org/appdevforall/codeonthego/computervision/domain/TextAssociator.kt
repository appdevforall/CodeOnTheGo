package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.utils.TextCleaner.cleanTextPreservingLeadingO
import org.appdevforall.codeonthego.computervision.utils.TextCleaner.cleanTextStrippingLeadingO
import kotlin.math.abs
import kotlin.math.max

/**
 * Applies spatial proximity and intersection heuristics to associate
 * loose text blocks (OCR) with their corresponding visual widget (YOLO).
 */
object TextAssociator {
    private const val OVERLAP_THRESHOLD = 0.6

    fun assignTextToParents(parents: List<ScaledBox>, texts: List<ScaledBox>, allBoxes: List<ScaledBox>): List<ScaledBox> {
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

    fun assignNearbyTextToWidgets(boxes: List<ScaledBox>, availableTexts: List<ScaledBox>): List<ScaledBox> {
        val consumedTexts = mutableSetOf<ScaledBox>()
        val updatedWidgets = mutableMapOf<ScaledBox, ScaledBox>()

        val labelableWidgets = boxes.filter { isLabelableWidget(it) }.sortedWith(compareBy({ it.y }, { it.x }))

        for (widget in labelableWidgets) {
            val nearbyText = availableTexts
                .asSequence()
                .filter { it !in consumedTexts }
                .filter { text -> widget.isVerticallyAlignedWith(text, tolerance = max(widget.h * 2.5, 40.0)) }
                .minByOrNull { text -> widget.calculateProximityScoreTo(text) }

            if (nearbyText != null) {
                val finalText = cleanWidgetText(widget, nearbyText.text)
                updatedWidgets[widget] = widget.copy(text = finalText)
                consumedTexts.add(nearbyText)
            }
        }

        return boxes.mapNotNull { box ->
            when (box) {
                in consumedTexts -> null
                in updatedWidgets -> updatedWidgets[box]
                else -> box
            }
        }
    }

    private fun isLabelableWidget(box: ScaledBox): Boolean {
        return box.label in setOf(
            "radio_button_unchecked", "radio_button_checked",
            "checkbox_unchecked", "checkbox_checked",
            "switch_on", "switch_off"
        )
    }

    private fun cleanWidgetText(widget: ScaledBox, rawText: String): String {
        return if (widget.label.contains("radio", ignoreCase = true)) {
            cleanTextStrippingLeadingO(rawText)
        } else {
            cleanTextPreservingLeadingO(rawText)
        }
    }

    private fun ScaledBox.isVerticallyAlignedWith(other: ScaledBox, tolerance: Double): Boolean {
        return abs(this.centerY - other.centerY) < tolerance
    }

    private fun ScaledBox.calculateProximityScoreTo(other: ScaledBox): Double {
        val dx = this.rect.horizontalDistanceTo(other.rect).toDouble()
        val dy = abs(this.centerY - other.centerY).toDouble()
        return (dx * dx) + (dy * dy * 5)
    }

    private fun Rect.horizontalDistanceTo(other: Rect): Int = when {
        this.right < other.left -> other.left - this.right
        this.left > other.right -> this.left - other.right
        else -> 0
    }
}
