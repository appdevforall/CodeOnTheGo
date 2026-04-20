package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.max
import kotlin.math.roundToInt

object YoloToXmlConverter {

    private const val TAG = "YoloToXmlConverter"
    private const val MIN_W_ANY = 8
    private const val MIN_H_ANY = 8
    private const val OVERLAP_THRESHOLD = 0.6

    private val TAG_REGEX = Regex("^(?i)(B|P|D|T|C|R|SW|S)-\\d+$")
    private val TAG_EXTRACT_REGEX = Regex("^(?i)([BPDTCRS8]\\s*W?)[^a-zA-Z0-9]*([\\dlIoO!]+)$")

    private fun normalizeOcrDigits(raw: String): String =
        raw.replace('l', '1').replace('I', '1').replace('!', '1')
            .replace('o', '0').replace('O', '0')

    private class ScaledBox(
        val label: String, var text: String, val x: Int, val y: Int, val w: Int, val h: Int,
        val centerX: Int, val centerY: Int, val rect: Rect
    )

    private fun normalizeTagText(text: String): String {
        val trimmed = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = TAG_EXTRACT_REGEX.find(trimmed) ?: return trimmed.uppercase()

        var prefix = match.groupValues[1].replace(Regex("\\s+"), "").uppercase()
        if (prefix == "8") prefix = "B"
        if (prefix == "8W" || prefix == "S8") prefix = "SW"

        return "$prefix-${normalizeOcrDigits(match.groupValues[2])}"
    }

    private fun isTag(text: String): Boolean = normalizeTagText(text).matches(TAG_REGEX)

    private fun getTagType(tag: String): String? {
        val upperTag = tag.uppercase()
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

    fun generateXmlLayout(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int,
        wrapInScroll: Boolean = true
    ): String {
        val widgets = detections
            .filter { it.isYolo && it.label != "widget_tag" }
            .distinctBy {
                if (it.label.startsWith("switch")) {
                    "${((it.boundingBox.top + it.boundingBox.bottom) / 2f).toInt() / 50}"
                } else {
                    "${it.label}:${it.boundingBox.left}:${it.boundingBox.top}:${it.boundingBox.right}:${it.boundingBox.bottom}"
                }
            }
        var scaledBoxes = widgets.map { scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight) }

        val parents = scaledBoxes.filter { it.label != "text" && !isTag(it.text) }
        val texts = scaledBoxes.filter { it.label == "text" && !isTag(it.text) }

        scaledBoxes = assignTextToParents(parents, texts, scaledBoxes)

        val uiElements = scaledBoxes.filter { !isTag(it.text) }
        val widgetTags = detections.filter { it.label == "widget_tag" || (!it.isYolo && isTag(it.text)) }
        val canvasTags = widgetTags.map { scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight) }

        val finalAnnotations = matchAnnotationsToElements(canvasTags, uiElements, annotations)

        val sortedBoxes = uiElements.sortedWith(compareBy({ it.y }, { it.x }))
        return buildXml(sortedBoxes, finalAnnotations, targetDpHeight, wrapInScroll)
    }

    private fun assignTextToParents(parents: List<ScaledBox>, texts: List<ScaledBox>, allBoxes: List<ScaledBox>): List<ScaledBox> {
        val consumedTexts = mutableSetOf<ScaledBox>()

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
                parent.text = it.text
                consumedTexts.add(it)
            }
        }
        return allBoxes.filter { !consumedTexts.contains(it) }
    }

    private fun matchAnnotationsToElements(
        canvasTags: List<ScaledBox>,
        uiElements: List<ScaledBox>,
        annotations: Map<String, String>
    ): Map<ScaledBox, String> {
        val finalAnnotations = mutableMapOf<ScaledBox, String>()
        val claimedWidgets = mutableSetOf<ScaledBox>()

        val deduplicatedTags = canvasTags
            .groupBy { normalizeTagText(it.text) }
            .map { (_, group) -> group.first() }

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

    private data class TaggedAnnotation(
        val normalizedTag: String,
        val widgetType: String,
        val annotation: String,
        val tagBox: ScaledBox?
    )

    private fun normalizeWidgetType(label: String): String = when {
        label.startsWith("text_entry_box") -> "text_entry_box"
        label.startsWith("button") -> "button"
        label.startsWith("switch") -> "switch"
        label.startsWith("checkbox") -> "checkbox"
        label.startsWith("radio") -> "radio"
        label.startsWith("dropdown") -> "dropdown"
        label.startsWith("slider") -> "slider"
        label.startsWith("image_placeholder") -> "image_placeholder"
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
            val zeroBasedMatch = candidates.getOrNull(ordinal)
            if (zeroBasedMatch != null && zeroBasedMatch !in claimedWidgets) {
                return zeroBasedMatch
            }

            val oneBasedMatch = candidates.getOrNull(ordinal - 1)
            if (oneBasedMatch != null && oneBasedMatch !in claimedWidgets) {
                return oneBasedMatch
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

    private fun scaleDetection(
        detection: DetectionResult, sourceWidth: Int, sourceHeight: Int, targetW: Int, targetH: Int
    ): ScaledBox {
        if (sourceWidth == 0 || sourceHeight == 0) {
            return ScaledBox(detection.label, detection.text, 0, 0, MIN_W_ANY, MIN_H_ANY, MIN_W_ANY / 2, MIN_H_ANY / 2, Rect(0, 0, MIN_W_ANY, MIN_H_ANY))
        }
        val rect = detection.boundingBox
        val normCx = ((rect.left + rect.right) / 2f) / sourceWidth
        val normCy = ((rect.top + rect.bottom) / 2f) / sourceHeight
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

    private fun escapeXmlAttr(value: String): String =
        value.replace("|", "")
            .trim()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun viewTagFor(label: String): String = when (label) {
        "text" -> "TextView"
        "button" -> "Button"
        "image_placeholder", "icon" -> "ImageView"
        "checkbox_unchecked", "checkbox_checked" -> "CheckBox"
        "radio_button_unchecked", "radio_button_checked" -> "RadioButton"
        "switch_off", "switch_on" -> "Switch"
        "text_entry_box" -> "EditText"
        "dropdown" -> "Spinner"
        "card" -> "androidx.cardview.widget.CardView"
        "slider" -> "com.google.android.material.slider.Slider"
        else -> "View"
    }

    private fun buildXml(
        boxes: List<ScaledBox>,
        annotations: Map<ScaledBox, String>,
        targetDpHeight: Int,
        wrapInScroll: Boolean
    ): String {
        val xml = StringBuilder()
        val maxBottom = boxes.maxOfOrNull { it.y + it.h } ?: 0
        val needScroll = wrapInScroll && maxBottom > targetDpHeight
        val namespaces =
            """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" xmlns:tools="http://schemas.android.com/tools""""

        xml.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        if (needScroll) {
            xml.appendLine("<ScrollView $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:fillViewport=\"true\">")
            xml.appendLine("    <LinearLayout android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        } else {
            xml.appendLine("<LinearLayout $namespaces android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\" android:padding=\"16dp\">")
        }
        xml.appendLine()

        val counters = mutableMapOf<String, Int>()
        boxes.forEach { box ->
            appendSimpleView(xml, box, counters, "        ", annotations)
            xml.appendLine()
        }

        xml.appendLine(if (needScroll) "    </LinearLayout>\n</ScrollView>" else "</LinearLayout>")
        return xml.toString()
    }

    private fun appendSimpleView(
        xml: StringBuilder,
        box: ScaledBox,
        counters: MutableMap<String, Int>,
        indent: String,
        annotations: Map<ScaledBox, String>
    ) {
        val label = box.label
        val tag = viewTagFor(label)
        val count = counters.getOrPut(label) { 0 }.let { counters[label] = it + 1; it }
        val defaultId = "${label.replace(Regex("[^a-zA-Z0-9_]"), "_")}_$count"

        val parsedAttrs = parseMarginAnnotations(annotations[box], tag)

        val width = parsedAttrs["android:layout_width"] ?: "${box.w}dp"
        val height = parsedAttrs["android:layout_height"] ?: "${box.h}dp"
        val id = parsedAttrs["android:id"]?.substringAfterLast('/') ?: defaultId

        val writtenAttrs = mutableSetOf(
            "android:id", "android:layout_width", "android:layout_height"
        )

        xml.append("$indent<$tag\n")
        xml.append("$indent    android:id=\"@+id/${escapeXmlAttr(id)}\"\n")
        xml.append("$indent    android:layout_width=\"${escapeXmlAttr(width)}\"\n")
        xml.append("$indent    android:layout_height=\"${escapeXmlAttr(height)}\"\n")

        when (tag) {
            "TextView", "Button", "CheckBox", "RadioButton", "Switch" ->
                appendTextViewAttributes(xml, indent, parsedAttrs, box, label, tag, writtenAttrs)

            "EditText" ->
                appendEditTextAttributes(xml, indent, parsedAttrs, box, writtenAttrs)

            "ImageView" ->
                appendImageViewAttributes(xml, indent, parsedAttrs, label, writtenAttrs)
        }

        parsedAttrs.forEach { (key, value) ->
            if (key !in writtenAttrs) {
                xml.append("$indent    $key=\"${escapeXmlAttr(value)}\"\n")
                writtenAttrs.add(key)
            }
        }
        xml.append("$indent/>")

        Log.d(TAG, "appendSimpleView: $xml")
    }

    private fun appendTextViewAttributes(
        xml: StringBuilder,
        indent: String,
        parsedAttrs: Map<String, String>,
        box: ScaledBox,
        label: String,
        tag: String,
        writtenAttrs: MutableSet<String>
    ) {
        val rawViewText = parsedAttrs["android:text"]
            ?: box.text.takeIf { it.isNotEmpty() && it != box.label }
            ?: when (tag) {
                "Switch" -> "Switch"
                "CheckBox" -> "CheckBox"
                "RadioButton" -> "RadioButton"
                else -> box.label
            }

        xml.append("$indent    android:text=\"${escapeXmlAttr(rawViewText)}\"\n")
        writtenAttrs.add("android:text")
        if (tag == "TextView") {
            val textSize = parsedAttrs["android:textSize"] ?: "16sp"
            xml.append("$indent    android:textSize=\"${escapeXmlAttr(textSize)}\"\n")
            writtenAttrs.add("android:textSize")
        }
        if (label.contains("_checked") || label.contains("_on")) {
            val checked = parsedAttrs["android:checked"] ?: "true"
            xml.append("$indent    android:checked=\"${escapeXmlAttr(checked)}\"\n")
            writtenAttrs.add("android:checked")
        }
        xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
        writtenAttrs.add("tools:ignore")
    }

    private fun appendEditTextAttributes(
        xml: StringBuilder,
        indent: String,
        parsedAttrs: Map<String, String>,
        box: ScaledBox,
        writtenAttrs: MutableSet<String>
    ) {
        val rawHint = parsedAttrs["android:hint"] ?: box.text.ifEmpty { "Enter text..." }

        xml.append("$indent    android:hint=\"${escapeXmlAttr(rawHint)}\"\n")
        writtenAttrs.add("android:hint")

        val inputType = parsedAttrs["android:inputType"] ?: "text"
        xml.append("$indent    android:inputType=\"${escapeXmlAttr(inputType)}\"\n")
        writtenAttrs.add("android:inputType")

        xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
        writtenAttrs.add("tools:ignore")
    }

    private fun appendImageViewAttributes(
        xml: StringBuilder,
        indent: String,
        parsedAttrs: Map<String, String>,
        label: String,
        writtenAttrs: MutableSet<String>
    ) {
        xml.append("$indent    android:contentDescription=\"${escapeXmlAttr(label)}\"\n")
        writtenAttrs.add("android:contentDescription")
        val scaleType = parsedAttrs["android:scaleType"] ?: "centerCrop"
        xml.append("$indent    android:scaleType=\"${escapeXmlAttr(scaleType)}\"\n")
        writtenAttrs.add("android:scaleType")
        val bg = parsedAttrs["android:background"] ?: "#E0E0E0"
        xml.append("$indent    android:background=\"${escapeXmlAttr(bg)}\"\n")
        writtenAttrs.add("android:background")
    }

    private fun parseMarginAnnotations(annotation: String?, tag: String): Map<String, String> {
        return FuzzyAttributeParser.parse(annotation, tag)
    }
}
