package org.appdevforall.codeonthego.computervision.domain

import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.roundToInt

object MarginAnnotationParser {

    private const val TAG = "MarginAnnotationParser"

    private val ocrCorrectionRules = listOf(
        Regex("(?<=-)!") to "1",
        Regex("(?<=-)l") to "1"
    )

    private fun correctOcrErrors(text: String): String {
        var correctedText = text
        for ((pattern, replacement) in ocrCorrectionRules) {
            correctedText = correctedText.replace(pattern, replacement)
        }
        return correctedText
    }

    /**
     * A tag is any string that starts with B, P, D, T, C, R, or S, followed by a hyphen and one or more digits.
     */
    private fun isTag(text: String): Boolean {
        return text.matches(Regex("^(B-|P-|D-|T-|C-|R-|S-)\\d+$"))
    }

    fun parse(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Pair<List<DetectionResult>, Map<String, String>> {
        val leftMarginPx = imageWidth * leftGuidePct
        val rightMarginPx = imageWidth * rightGuidePct

        // 1. Partition detections into canvas and margin lists.
        val canvasDetections = mutableListOf<DetectionResult>()
        val marginDetections = mutableListOf<DetectionResult>()

        for (detection in detections) {
            val centerX = detection.boundingBox.centerX()
            if (centerX > leftMarginPx && centerX < rightMarginPx) {
                canvasDetections.add(detection)
            } else {
                marginDetections.add(detection)
            }
        }

        // --- Logging Step-by-Step ---

        Log.d(TAG, "--- Raw OCR Content by Region ---")

        // 2. Log Canvas OCR content.
        val canvasOcrLog = canvasDetections.joinToString(", ") { "'${correctOcrErrors(it.text)}'" }
        Log.d(TAG, "Canvas OCR: [$canvasOcrLog]")

        // 3. Group and log Margin OCR content.
        Log.d(TAG, "--- Grouped Margin OCR ---")
        val groupedMarginOcr = mutableMapOf<String, MutableList<String>>()
        var currentMarginTag: String? = null

        // Sort all margin detections by their vertical position to ensure correct grouping.
        val sortedMarginDetections = marginDetections.sortedBy { it.boundingBox.top }

        for (detection in sortedMarginDetections) {
            val correctedText = correctOcrErrors(detection.text.trim())
            if (isTag(correctedText)) {
                currentMarginTag = correctedText
                if (!groupedMarginOcr.containsKey(currentMarginTag)) {
                    groupedMarginOcr[currentMarginTag!!] = mutableListOf()
                }
            } else if (currentMarginTag != null) {
                groupedMarginOcr[currentMarginTag!!]?.add(correctedText)
            }
        }

        groupedMarginOcr.forEach { (tag, texts) ->
            val contentLog = texts.joinToString(", ") { "'$it'" }
            Log.d(TAG, "Tag: '$tag', Content: [$contentLog]")
        }
        Log.d(TAG, "--------------------------")

        // --- End of Logging ---


        // --- Original Parsing Logic (to be preserved) ---
        val annotationMap = mutableMapOf<String, String>()
        var currentTag: String? = null
        val currentAnnotation = StringBuilder()

        for (detection in sortedMarginDetections) {
            val correctedText = correctOcrErrors(detection.text.trim())
            if (isTag(correctedText)) {
                if (currentTag != null && currentAnnotation.isNotBlank()) {
                    annotationMap[currentTag!!] = currentAnnotation.toString().trim()
                }
                currentTag = correctedText
                currentAnnotation.clear()
            } else if (currentTag != null) {
                currentAnnotation.append(" ").append(correctedText)
            }
        }
        if (currentTag != null && currentAnnotation.isNotBlank()) {
            annotationMap[currentTag!!] = currentAnnotation.toString().trim()
        }

        val correctedCanvasDetections = canvasDetections.map {
            it.copy(text = correctOcrErrors(it.text))
        }

        // Log the final findings
        val marginTags = annotationMap.keys.joinToString(", ")
        Log.d(TAG, "Margin Annotations Found: $marginTags")
        val canvasWidgetTags = correctedCanvasDetections.filter { isTag(it.text.trim()) }
        val canvasTagsLog = canvasWidgetTags.joinToString(", ") { "'${it.text.trim()}'" }
        Log.d(TAG, "Canvas Widget Tags Found: $canvasTagsLog")


        return Pair(correctedCanvasDetections, annotationMap)
    }
}