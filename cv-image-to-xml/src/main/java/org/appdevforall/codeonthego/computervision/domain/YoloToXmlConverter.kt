package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object YoloToXmlConverter {

    private const val TAG = "YoloToXmlConverter"
    private const val MIN_W_ANY = 8
    private const val MIN_H_ANY = 8
    private const val DEFAULT_SPACING_DP = 16

    private const val HORIZONTAL_ALIGN_THRESHOLD = 20
    private const val VERTICAL_ALIGN_THRESHOLD = 20
    private const val RADIO_GROUP_GAP_THRESHOLD = 24
    private const val OVERLAP_THRESHOLD = 0.6

    private val TAG_REGEX = Regex("^(B|P|D|T|C|R|SW|S)-\\d+$")
    private val TAG_EXTRACT_REGEX = Regex("^([BPDTCRS8]W?)[^a-zA-Z0-9]*([\\dlIoO!]+)$")

    private fun normalizeOcrDigits(raw: String): String =
        raw.replace('l', '1').replace('I', '1').replace('!', '1')
            .replace('o', '0').replace('O', '0')

    private data class ScaledBox(
        val label: String, var text: String, val x: Int, val y: Int, val w: Int, val h: Int,
        val centerX: Int, val centerY: Int, val rect: Rect
    )

    private fun normalizeTagText(text: String): String {
        val trimmed = text.trim().trimEnd('.', ',', ';', ':', '_', '|')
        val match = TAG_EXTRACT_REGEX.find(trimmed) ?: return trimmed
        var prefix = match.groupValues[1]
        if (prefix == "8") prefix = "B"
        return "$prefix-${normalizeOcrDigits(match.groupValues[2])}"
    }

    private fun isTag(text: String): Boolean = normalizeTagText(text).matches(TAG_REGEX)

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

    private fun distance(box1: ScaledBox, box2: ScaledBox): Float {
        val dx = (box1.centerX - box2.centerX).toFloat()
        val dy = (box1.centerY - box2.centerY).toFloat()
        return sqrt(dx.pow(2) + dy.pow(2))
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

        val widgetTags = detections.filter {
            it.label == "widget_tag" || (!it.isYolo && isTag(it.text))
        }
        val widgets = detections.filter { it.isYolo }.filter { it.label != "widget_tag" }

        var scaledBoxes = widgets.map {
            scaleDetection(
                it,
                sourceImageWidth,
                sourceImageHeight,
                targetDpWidth,
                targetDpHeight
            )
        }

        val parents = scaledBoxes.filter { it.label != "text" && !isTag(it.text) }.toMutableList()
        val texts = scaledBoxes.filter { it.label == "text" && !isTag(it.text) }
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
        scaledBoxes = scaledBoxes.filter { !consumedTexts.contains(it) }

        val uiElements = scaledBoxes.filter { !isTag(it.text) }
        val canvasTags = widgetTags.map {
            scaleDetection(
                it,
                sourceImageWidth,
                sourceImageHeight,
                targetDpWidth,
                targetDpHeight
            )
        }
        val finalAnnotations = mutableMapOf<ScaledBox, String>()
        val claimedWidgets = mutableSetOf<ScaledBox>()
        val appliedAnnotationKeys = mutableSetOf<String>()

        val deduplicatedTags = canvasTags
            .groupBy { normalizeTagText(it.text) }
            .map { (_, group) -> group.first() }

        for (tagBox in deduplicatedTags) {
            val normalizedText = normalizeTagText(tagBox.text)
            val tagType = getTagType(normalizedText) ?: continue
            val annotation = annotations[normalizedText] ?: continue

            val closestElement = uiElements
                .filter { it.label.startsWith(tagType) && it !in claimedWidgets }
                .minByOrNull { distance(tagBox, it) }

            if (closestElement != null) {
                finalAnnotations[closestElement] = annotation
                claimedWidgets.add(closestElement)
                appliedAnnotationKeys.add(normalizedText)
            }
        }

        for ((tagText, annotation) in annotations) {
            if (tagText in appliedAnnotationKeys) continue
            val tagType = getTagType(tagText) ?: continue
            val unclaimed = uiElements
                .filter { it.label.startsWith(tagType) && it !in claimedWidgets }
                .sortedWith(compareBy({ it.y }, { it.x }))
                .firstOrNull()
            if (unclaimed != null) {
                finalAnnotations[unclaimed] = annotation
                claimedWidgets.add(unclaimed)
            }
        }

        val sortedBoxes = uiElements.sortedWith(compareBy({ it.y }, { it.x }))
        return buildXml(sortedBoxes, finalAnnotations, targetDpWidth, targetDpHeight, wrapInScroll)
    }

    private fun scaleDetection(
        detection: DetectionResult, sourceWidth: Int, sourceHeight: Int, targetW: Int, targetH: Int
    ): ScaledBox {
        if (sourceWidth == 0 || sourceHeight == 0) {
            return ScaledBox(detection.label, detection.text, 0, 0, MIN_W_ANY, MIN_H_ANY, MIN_W_ANY / 2, MIN_H_ANY / 2, Rect(0, 0, MIN_W_ANY, MIN_H_ANY))
        }
        val rect = detection.boundingBox
        val normCx = rect.centerX() / sourceWidth
        val normCy = rect.centerY() / sourceHeight
        val normW = rect.width() / sourceWidth
        val normH = rect.height() / sourceHeight
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
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun viewTagFor(label: String): String = when (label) {
        "text" -> "TextView"
        "button" -> "Button"
        "image_placeholder", "icon" -> "ImageView"
        "checkbox_unchecked", "checkbox_checked" -> "CheckBox"
        "radio_unchecked", "radio_checked" -> "RadioButton"
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
        targetDpWidth: Int,
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

        val width = parsedAttrs["android:layout_width"] ?: "wrap_content"
        val height = parsedAttrs["android:layout_height"] ?: "wrap_content"
        val id = parsedAttrs["android:id"]?.substringAfterLast('/') ?: defaultId

        val writtenAttrs = mutableSetOf(
            "android:id", "android:layout_width", "android:layout_height"
        )

        xml.append("$indent<$tag\n")
        xml.append("$indent    android:id=\"@+id/${escapeXmlAttr(id)}\"\n")
        xml.append("$indent    android:layout_width=\"${escapeXmlAttr(width)}\"\n")
        xml.append("$indent    android:layout_height=\"${escapeXmlAttr(height)}\"\n")

        when (tag) {
            "TextView", "Button", "CheckBox", "RadioButton", "Switch" -> {
                val viewText = parsedAttrs["android:text"]
                    ?: box.text.takeIf { it.isNotEmpty() && it != box.label }
                    ?: box.label
                xml.append("$indent    android:text=\"${escapeXmlAttr(viewText)}\"\n")
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

            "EditText" -> {
                val hint = parsedAttrs["android:hint"]
                    ?: box.text.ifEmpty { "Enter text..." }
                xml.append("$indent    android:hint=\"${escapeXmlAttr(hint)}\"\n")
                writtenAttrs.add("android:hint")
                val inputType = parsedAttrs["android:inputType"] ?: "text"
                xml.append("$indent    android:inputType=\"${escapeXmlAttr(inputType)}\"\n")
                writtenAttrs.add("android:inputType")
                xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
                writtenAttrs.add("tools:ignore")
            }

            "ImageView" -> {
                xml.append("$indent    android:contentDescription=\"${escapeXmlAttr(label)}\"\n")
                writtenAttrs.add("android:contentDescription")
                val scaleType = parsedAttrs["android:scaleType"] ?: "centerCrop"
                xml.append("$indent    android:scaleType=\"${escapeXmlAttr(scaleType)}\"\n")
                writtenAttrs.add("android:scaleType")
                val bg = parsedAttrs["android:background"] ?: "#E0E0E0"
                xml.append("$indent    android:background=\"${escapeXmlAttr(bg)}\"\n")
                writtenAttrs.add("android:background")
            }
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

    private fun parseMarginAnnotations(annotation: String?, tag: String): Map<String, String> {
        return FuzzyAttributeParser.parse(annotation, tag)
    }
}