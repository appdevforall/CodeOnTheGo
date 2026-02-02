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

        val marginLogOutput = marginDetections.joinToString(", ") {
            val box = it.boundingBox
            "'${it.text}', [left:${box.left.roundToInt()}, top:${box.top.roundToInt()}, width:${box.width().roundToInt()}, height:${box.height().roundToInt()}]"
        }
        Log.d(TAG, "Parsed Margin Content: $marginLogOutput")

        val annotationMap = mutableMapOf<String, String>()
        val sortedMarginDetections = marginDetections.sortedBy { it.boundingBox.top }
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

        val finalAnnotationLog = annotationMap.entries.joinToString(", ") { "'${it.key}' -> '${it.value}'" }
        Log.d(TAG, "Processed Margin Annotations: {$finalAnnotationLog}")

        val canvasLogOutput = correctedCanvasDetections.joinToString(", ") {
            val box = it.boundingBox
            "'${it.text}', [left:${box.left.roundToInt()}, top:${box.top.roundToInt()}, width:${box.width().roundToInt()}, height:${box.height().roundToInt()}]"
        }
        Log.d(TAG, "Parsed Canvas Content (Corrected): $canvasLogOutput")

        return Pair(correctedCanvasDetections, annotationMap)
    }
}