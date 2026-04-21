package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox

class WidgetAnnotationMatcher {
    companion object {
        private val TAG_REGEX = Regex("^(?i)(B|P|D|T|C|R|SW|S)-\\d+$")
        private val TAG_EXTRACT_REGEX = Regex("^(?i)(SW|S\\s*8|8\\s*W|[BPDTCRS8]\\s*W?)[^a-zA-Z0-9]*([\\dlIoO!]+)$")
    }

    internal fun matchAnnotationsToElements(
        canvasTags: List<ScaledBox>,
        uiElements: List<ScaledBox>,
        annotations: Map<String, String>
    ): Map<ScaledBox, String> {
        val finalAnnotations = mutableMapOf<ScaledBox, String>()
        val claimedWidgets = mutableSetOf<ScaledBox>()

        val deduplicatedTags = canvasTags
            .distinctBy { normalizeTagText(it.text) }

        val tagsByWidgetType = annotations
            .mapNotNull { (tagText, annotationText) ->
                val normalizedTag = normalizeTagText(tagText)
                val widgetType = getTagType(normalizedTag) ?: return@mapNotNull null

                val matchingTagBox = deduplicatedTags.find { normalizeTagText(it.text) == normalizedTag }

                TaggedAnnotation(
                    normalizedTag = normalizedTag,
                    widgetType = widgetType,
                    annotation = annotationText,
                    tagBox = matchingTagBox
                )
            }
            .groupBy { it.widgetType }

        val widgetsByType = uiElements.groupBy { normalizeWidgetType(it.label) }

        for ((widgetType, taggedAnnotations) in tagsByWidgetType) {
            val candidateWidgets = widgetsByType[widgetType]
                ?.sortedWith(compareBy({ it.y }, { it.x }))
                ?: continue

            val sortedTags = taggedAnnotations.sortedWith(
                compareBy(
                    { extractTagOrdinal(it.normalizedTag) ?: Int.MAX_VALUE },
                    { it.tagBox?.y ?: Int.MAX_VALUE },
                    { it.tagBox?.x ?: Int.MAX_VALUE }
                )
            )

            for (taggedAnnotation in sortedTags) {
                val ordinal = extractTagOrdinal(taggedAnnotation.normalizedTag)
                val matchedWidget = findWidgetByOrdinalOrFallback(
                    ordinal = ordinal,
                    tagBox = taggedAnnotation.tagBox,
                    candidates = candidateWidgets,
                    claimedWidgets = claimedWidgets
                ) ?: continue

                finalAnnotations[matchedWidget] = taggedAnnotation.annotation
                claimedWidgets.add(matchedWidget)
            }
        }

        return finalAnnotations
    }

    private fun normalizeOcrDigits(raw: String): String =
        raw.replace('l', '1').replace('I', '1').replace('!', '1')
            .replace('o', '0').replace('O', '0')

    private fun normalizeTagText(text: String): String {
        val trimmed = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = TAG_EXTRACT_REGEX.find(trimmed) ?: return trimmed.uppercase()

        var prefix = match.groupValues[1].replace(Regex("\\s+"), "").uppercase()
        if (prefix == "8") prefix = "B"
        if (prefix == "8W" || prefix == "S8") prefix = "SW"

        return "$prefix-${normalizeOcrDigits(match.groupValues[2])}"
    }

    internal fun isTag(text: String): Boolean = normalizeTagText(text).matches(TAG_REGEX)

    private fun getTagType(tag: String): String? {
        return when {
            tag.startsWith("B-") -> "button"
            tag.startsWith("P-") -> "image_placeholder"
            tag.startsWith("D-") -> "dropdown"
            tag.startsWith("T-") -> "text_entry_box"
            tag.startsWith("C-") -> "checkbox"
            tag.startsWith("R-") -> "radio"
            tag.startsWith("SW-") -> "switch"
            tag.startsWith("S-") -> "slider"
            else -> null
        }
    }

    private fun normalizeWidgetType(label: String): String = when {
        label.startsWith("text_entry_box") -> "text_entry_box"
        label.startsWith("button") -> "button"
        label.startsWith("switch") -> "switch"
        label.startsWith("checkbox") -> "checkbox"
        label.startsWith("radio") -> "radio"
        label.startsWith("dropdown") -> "dropdown"
        label.startsWith("slider") -> "slider"
        label.startsWith("image_placeholder") || label.startsWith("icon") -> "image_placeholder"
        else -> label
    }

    private fun extractTagOrdinal(tag: String): Int? {
        return tag.substringAfter('-', "").toIntOrNull()
    }

    private fun findWidgetByOrdinalOrFallback(
        ordinal: Int?,
        tagBox: ScaledBox?,
        candidates: List<ScaledBox>,
        claimedWidgets: Set<ScaledBox>
    ): ScaledBox? {
        val available = candidates.filter { it !in claimedWidgets }
        if (available.isEmpty()) return null

        if (ordinal != null) {
            val oneBasedMatch = candidates.getOrNull(ordinal - 1)
            if (oneBasedMatch != null && oneBasedMatch !in claimedWidgets) {
                return oneBasedMatch
            }

            val zeroBasedMatch = candidates.getOrNull(ordinal)
            if (zeroBasedMatch != null && zeroBasedMatch !in claimedWidgets) {
                return zeroBasedMatch
            }
        }

        if (tagBox != null) {
            return available.minByOrNull { candidate ->
                val verticalDistance = kotlin.math.abs(tagBox.centerY - candidate.centerY)
                val horizontalDistance = kotlin.math.abs(tagBox.centerX - candidate.centerX)
                (verticalDistance * 2) + horizontalDistance
            }
        }

        return available.minByOrNull { it.y }
    }

    private data class TaggedAnnotation(
        val normalizedTag: String,
        val widgetType: String,
        val annotation: String,
        val tagBox: ScaledBox?
    )
}
