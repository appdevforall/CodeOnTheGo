package org.appdevforall.codeonthego.computervision.domain

import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.min

object MarginAnnotationParser {

    private const val TAG = "MarginAnnotationParser"

    // --- Dictionaries for Widgets and Attributes ---
    private val widgetTypeMap = mapOf(
        'B' to "Button",
        'I' to "ImageView",
        'P' to "ImageView" // P is also an ImageView as seen in logs
    )

    private val widgetAttributes = mapOf(
        "Button" to setOf("layout_width", "layout_height", "id", "text", "background"),
        "ImageView" to setOf("layout_width", "layout_height", "id", "src", "layout_gravity", "contentDescription")
    )

    // --- OCR Correction and Value Keywords ---
    private val attributeCorrectionMap = mapOf(
        "ld" to "id",
        "scr" to "src",
        "background" to "background",
        "layout width" to "layout_width",
        "layout height" to "layout_height"
    )

    private val valueCorrectionMap = mapOf(
        "red" to "#FFFF0000",
        "blue" to "#FF0000FF"
    )

    private fun isTag(text: String): Boolean {
        return text.matches(Regex("^(B-|P-|I-)\\d+$"))
    }

    /**
     * Parses a raw annotation string into a clean map of XML attributes.
     */
    private fun parseAnnotationString(rawText: String, widgetType: String): Map<String, String> {
        val attributesForWidget = widgetAttributes[widgetType] ?: return emptyMap()

        // Pre-process the text to normalize attribute keywords
        var correctedText = rawText.lowercase()
        attributeCorrectionMap.forEach { (ocrError, correctAttr) ->
            correctedText = correctedText.replace(ocrError, correctAttr)
        }
        // Specific regex for handling variations of layout attributes
        correctedText = correctedText.replace(Regex("layout\\s*[-_]?\\s*width"), "layout_width")
        correctedText = correctedText.replace(Regex("layout\\s*[-_]?\\s*height"), "layout_height")
        correctedText = correctedText.replace(Regex("layout\\s*[-_]?\\s*gravity"), "layout_gravity")


        val parsedAttributes = mutableMapOf<String, String>()
        val attributeRegex = attributesForWidget.joinToString("|") { Regex.escape(it) }.toRegex()
        val matches = attributeRegex.findAll(correctedText).toList()

        matches.forEachIndexed { i, match ->
            val key = match.value
            val valueStartIndex = match.range.last + 1
            val valueEndIndex = if (i + 1 < matches.size) matches[i + 1].range.first else correctedText.length
            var value = correctedText.substring(valueStartIndex, valueEndIndex).replace(":", "").trim()

            // Clean and format the extracted value
            value = when (key) {
                "id" -> value.replace(Regex("\\s+"), "_").replace(Regex("[^a-zA-Z0-9_]"), "")
                "background" -> valueCorrectionMap[value] ?: value
                "src" -> valueCorrectionMap[value] ?: "@drawable/${value.replace(' ', '_')}"
                "layout_width", "layout_height" -> value.filter { it.isDigit() || it == 'd' || it == 'p' }
                else -> value
            }
            parsedAttributes[key] = value
        }
        return parsedAttributes
    }

    fun parse(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Pair<List<DetectionResult>, Map<String, String>> {
        val leftMarginPx = imageWidth * leftGuidePct
        val rightMarginPx = imageWidth * rightGuidePct

        val (canvasDetections, marginDetections) = detections.partition {
            it.boundingBox.centerX().toInt() in (leftMarginPx.toInt() + 1)..rightMarginPx.toInt()
        }

        val groupedMarginOcr = mutableMapOf<String, MutableList<String>>()
        var currentMarginTag: String? = null
        marginDetections.sortedBy { it.boundingBox.top }.forEach { detection ->
            val text = detection.text.trim()
            if (isTag(text)) {
                currentMarginTag = text
                if (!groupedMarginOcr.containsKey(currentMarginTag)) {
                    groupedMarginOcr[currentMarginTag!!] = mutableListOf()
                }
            } else if (currentMarginTag != null) {
                groupedMarginOcr[currentMarginTag!!]?.add(text)
            }
        }

        val finalAnnotationMap = mutableMapOf<String, String>()
        groupedMarginOcr.forEach { (tag, texts) ->
            val widgetPrefix = tag.first()
            val widgetType = widgetTypeMap[widgetPrefix]
            if (widgetType != null) {
                val fullAnnotationText = texts.joinToString(" ")
                val parsedAttrs = parseAnnotationString(fullAnnotationText, widgetType)
                if (parsedAttrs.isNotEmpty()) {
                    val attrString = parsedAttrs.map { (k, v) ->
                        val key = when(k) {
                            "background" -> "app:backgroundTint"
                            else -> "android:$k"
                        }
                        "$key=\"$v\""
                    }.joinToString("\n")
                    finalAnnotationMap[tag] = attrString
                }
            }
        }

        // --- Logging for Verification ---
        Log.d(TAG, "--- Raw OCR Content by Region ---")
        Log.d(TAG, "Canvas OCR: [${canvasDetections.joinToString(", ") { "'${it.text}'" }}]")
        Log.d(TAG, "--- Grouped Margin OCR ---")
        groupedMarginOcr.forEach { (tag, texts) ->
            Log.d(TAG, "Tag: '$tag', Content: [${texts.joinToString(", ") { "'$it'" }}]")
        }
        Log.d(TAG, "--- Parsed Annotations ---")
        finalAnnotationMap.forEach { (tag, attrs) ->
            Log.d(TAG, "Tag: '$tag', Attributes:\n$attrs")
        }
        Log.d(TAG, "--------------------------")
        val canvasWidgetTags = canvasDetections.filter { isTag(it.text.trim()) }
        Log.d(TAG, "Canvas Widget Tags Found: ${canvasWidgetTags.joinToString(", ") { "'${it.text.trim()}'" }}")

        return Pair(canvasDetections, finalAnnotationMap)
    }
}