package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.abs
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

    private val colorMap = mapOf(
        "red" to "#FF0000", "green" to "#00FF00", "blue" to "#0000FF",
        "black" to "#000000", "white" to "#FFFFFF", "gray" to "#808080",
        "grey" to "#808080", "dark_gray" to "#A9A9A9", "yellow" to "#FFFF00",
        "cyan" to "#00FFFF", "magenta" to "#FF00FF", "purple" to "#800080",
        "orange" to "#FFA500", "brown" to "#A52A2A", "pink" to "#FFC0CB",
        "transparent" to "@android:color/transparent"
    )

    private data class ScaledBox(
        val label: String, var text: String, val x: Int, val y: Int, val w: Int, val h: Int,
        val centerX: Int, val centerY: Int, val rect: Rect
    )

    private fun isTag(text: String): Boolean = text.matches(Regex("^(B-|P-|D-|T-|C-|R-|S-)\\d+$"))

    private fun getTagType(tag: String): String? {
        return when {
            tag.startsWith("B-") -> "button"
            tag.startsWith("P-") -> "image_placeholder"
            tag.startsWith("D-") -> "dropdown"
            tag.startsWith("T-") -> "text_entry_box"
            tag.startsWith("C-") -> "checkbox_unchecked"
            tag.startsWith("R-") -> "radio_unchecked"
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
        var scaledBoxes = detections.map { scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight) }

        val parents = scaledBoxes.filter { it.label != "text" && !isTag(it.text) }.toMutableList()
        val texts = scaledBoxes.filter { it.label == "text" && !isTag(it.text) }
        val consumedTexts = mutableSetOf<ScaledBox>()

        for (parent in parents) {
            texts.firstOrNull { text ->
                !consumedTexts.contains(text) && Rect(parent.rect).intersect(text.rect) &&
                        (Rect(parent.rect).width() * Rect(parent.rect).height()).let { intersectionArea ->
                            val textArea = text.w * text.h
                            textArea > 0 && (intersectionArea.toFloat() / textArea.toFloat()) > OVERLAP_THRESHOLD
                        }
            }?.let {
                parent.text = it.text
                consumedTexts.add(it)
            }
        }
        scaledBoxes = scaledBoxes.filter { !consumedTexts.contains(it) }

        val uiElements = scaledBoxes.filter { !isTag(it.text) }
        val canvasTags = scaledBoxes.filter { isTag(it.text) }
        val finalAnnotations = mutableMapOf<ScaledBox, String>()

        for (tagBox in canvasTags) {
            val tagType = getTagType(tagBox.text) ?: continue
            val annotation = annotations[tagBox.text] ?: continue

            val closestElement = uiElements
                .filter { it.label.startsWith(tagType) }
                .minByOrNull { distance(tagBox, it) }

            if (closestElement != null) {
                finalAnnotations[closestElement] = annotation
            }
        }
        
        Log.d(TAG, "Final Annotation Associations: ${finalAnnotations.entries.joinToString { "'${it.key.label}' -> '${it.value}'" }}")

        val sortedBoxes = uiElements.sortedWith(compareBy({ it.y }, { it.x }))
        return buildXml(sortedBoxes, finalAnnotations, targetDpWidth, targetDpHeight, wrapInScroll)
    }

    private fun scaleDetection(
        detection: DetectionResult, sourceWidth: Int, sourceHeight: Int, targetW: Int, targetH: Int
    ): ScaledBox {
        val rect = detection.boundingBox
        val normCx = rect.centerX() / sourceWidth
        val normCy = rect.centerY() / sourceHeight
        val normW = rect.width() / sourceWidth
        val normH = rect.height() / sourceHeight
        val x = max(0, ((normCx - normW / 2.0) * targetW).roundToInt())
        val y = max(0, ((normCy - normH / 2.0) * targetH).roundToInt())
        val w = max(MIN_W_ANY, (normW * targetW).roundToInt())
        val h = max(MIN_H_ANY, (normH * targetH).roundToInt())
        return ScaledBox(detection.label, detection.text, x, y, w, h, x + w / 2, y + h / 2, Rect(x, y, x + w, y + h))
    }

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
        boxes: List<ScaledBox>, annotations: Map<ScaledBox, String>, targetDpWidth: Int, targetDpHeight: Int, wrapInScroll: Boolean
    ): String {
        val xml = StringBuilder()
        val maxBottom = boxes.maxOfOrNull { it.y + it.h } ?: 0
        val needScroll = wrapInScroll && maxBottom > targetDpHeight
        val namespaces = """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" xmlns:tools="http://schemas.android.com/tools""""

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
        xml: StringBuilder, box: ScaledBox, counters: MutableMap<String, Int>, indent: String, annotations: Map<ScaledBox, String>
    ) {
        val label = box.label
        val tag = viewTagFor(label)
        val count = counters.getOrPut(label) { 0 }.let { counters[label] = it + 1; it }
        val defaultId = "${label.replace(Regex("[^a-zA-Z0-9_]"), "_")}_$count"

        val parsedAttrs = parseMarginAnnotations(annotations[box], tag)

        val width = parsedAttrs["android:layout_width"] ?: "wrap_content"
        val height = parsedAttrs["android:layout_height"] ?: "wrap_content"
        val id = parsedAttrs["android:id"]?.substringAfterLast('/') ?: defaultId

        xml.append("$indent<$tag\n")
        xml.append("$indent    android:id=\"@+id/$id\"\n")
        xml.append("$indent    android:layout_width=\"$width\"\n")
        xml.append("$indent    android:layout_height=\"$height\"\n")

        when (tag) {
            "TextView", "Button", "CheckBox", "RadioButton", "Switch" -> {
                val viewText = box.text.takeIf { it.isNotEmpty() && it != box.label } ?: box.label
                xml.append("$indent    android:text=\"$viewText\"\n")
                if (tag == "TextView") xml.append("$indent    android:textSize=\"16sp\"\n")
                if (label.contains("_checked") || label.contains("_on")) xml.append("$indent    android:checked=\"true\"\n")
                xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
            }
            "EditText" -> {
                xml.append("$indent    android:hint=\"${box.text.ifEmpty { "Enter text..." }}\"\n")
                xml.append("$indent    android:inputType=\"text\"\n")
                xml.append("$indent    tools:ignore=\"HardcodedText\"\n")
            }
            "ImageView" -> {
                xml.append("$indent    android:contentDescription=\"$label\"\n")
                xml.append("$indent    android:scaleType=\"centerCrop\"\n")
                xml.append("$indent    android:background=\"#E0E0E0\"\n")
            }
        }

        parsedAttrs.forEach { (key, value) ->
            if (key !in listOf("android:layout_width", "android:layout_height", "android:id")) {
                xml.append("$indent    $key=\"$value\"\n")
            }
        }
        xml.append("$indent/>")
    }

    private fun parseMarginAnnotations(annotation: String?, tag: String): Map<String, String> {
        if (annotation.isNullOrBlank()) return emptyMap()

        val parsed = mutableMapOf<String, String>()
        val knownKeys = listOf(
            "layout_width", "layout-width", "width",
            "layout_height", "layout-height", "height", "layout height",
            "id", "text", "background",
            "src", "scr",
            "entries", "inputtype", "input_type",
            "hint", "textcolor", "text_color",
            "textsize", "text_size",
            "style", "layout_weight", "layout-weight",
            "layout_gravity", "layout-gravity", "gravity"
        )
        val keysRegex = Regex("(?i)\\b(${knownKeys.joinToString("|")})\\s*:")
        val matches = keysRegex.findAll(annotation).toList()

        matches.forEachIndexed { index, match ->
            val key = match.groupValues[1]
            val startIndex = match.range.last + 1
            val endIndex = if (index + 1 < matches.size) matches[index + 1].range.first else annotation.length
            val value = annotation.substring(startIndex, endIndex).trim()

            if (value.isNotEmpty()) {
                formatAttribute(key, value, tag)?.let { (attr, fValue) -> parsed[attr] = fValue }
            }
        }
        return parsed
    }

    private fun formatAttribute(key: String, value: String, tag: String): Pair<String, String>? {
        val canonicalKey = key.lowercase().replace("-", "_").replace(" ", "_")
        
        return when (canonicalKey) {
            "width", "layout_width" -> "android:layout_width" to formatDimension(value)
            "height", "layout_height" -> "android:layout_height" to formatDimension(value)
            "background" -> {
                if (tag == "Button") {
                    "app:backgroundTint" to (colorMap[value.lowercase()] ?: value)
                } else {
                    "android:background" to (colorMap[value.lowercase()] ?: value)
                }
            }
            "text" -> "android:text" to value
            "id" -> "android:id" to value.replace(" ", "_")
            "src", "scr" -> "android:src" to "@drawable/${value.substringBeforeLast('.')}"
            "entries" -> "tools:entries" to value
            "inputtype", "input_type" -> "android:inputType" to value
            "hint" -> "android:hint" to value
            "textcolor", "text_color" -> "android:textColor" to (colorMap[value.lowercase()] ?: value)
            "textsize", "text_size" -> "android:textSize" to if (value.matches(Regex("\\d+"))) "${value}sp" else value
            "style" -> "style" to value
            "layout_weight" -> "android:layout_weight" to value
            "gravity", "layout_gravity" -> "android:layout_gravity" to value
            else -> null
        }
    }

    private fun formatDimension(value: String): String {
        val trimmed = value.replace("dp", "").trim()
        return if (trimmed.matches(Regex("-?\\d+"))) "${trimmed}dp" else value
    }
}