package org.appdevforall.codeonthego.computervision.domain

import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.roundToInt

object MarginAnnotationParser {

    private const val TAG = "MarginAnnotationParser"

    // A scalable list of correction rules. Each rule is a pair of a Regex to find
    // and a String to replace it with. More rules can be easily added here.
    private val ocrCorrectionRules = listOf(
        // Rule 1: Correct a '!' that follows a hyphen to a '1'. E.g., "B-!" -> "B-1"
        Regex("(?<=-)!") to "1",
        // Rule 2: Correct a lowercase 'l' that follows a hyphen to a '1'. E.g., "B-l" -> "B-1"
        Regex("(?<=-)l") to "1"
        // Add more rules here, e.g., Regex("0") to "O" if needed
    )

    /**
     * Corrects common OCR errors in text by applying a set of predefined rules.
     */
    private fun correctOcrErrors(text: String): String {
        var correctedText = text
        for ((pattern, replacement) in ocrCorrectionRules) {
            correctedText = correctedText.replace(pattern, replacement)
        }
        return correctedText
    }

    private fun isTag(text: String): Boolean {
        return text.startsWith("B-") || text.startsWith("I-")
    }

    /**
     * Partitions detections into canvas items and a map of margin annotations.
     * It also logs the parsed annotations for verification.
     *
     * @param detections The complete list of merged (YOLO + OCR) detections.
     * @param imageWidth The width of the source image, used for calculating pixel boundaries.
     * @param leftGuidePct The percentage from the left edge defining the start of the canvas.
     * @param rightGuidePct The percentage from the left edge defining the end of the canvas.
     * @return A Pair where the first element is the list of *corrected* canvas detections and the second
     *         is a map of margin annotations, with the tag as the key and the annotation as the value.
     */
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

        // 1. Partition detections into canvas and margin lists
        for (detection in detections) {
            val centerX = detection.boundingBox.centerX()
            if (centerX > leftMarginPx && centerX < rightMarginPx) {
                canvasDetections.add(detection)
            } else {
                marginDetections.add(detection)
            }
        }

        // 2. Parse the margin detections to create the annotation map (NEW LOGIC)
        val annotationMap = mutableMapOf<String, String>()
        val sortedMarginDetections = marginDetections.sortedBy { it.boundingBox.top }
        var currentTag: String? = null
        val currentAnnotation = StringBuilder()

        for (detection in sortedMarginDetections) {
            val correctedText = correctOcrErrors(detection.text.trim())
            if (isTag(correctedText)) {
                // We've hit a new tag. Finalize the previous one.
                if (currentTag != null && currentAnnotation.isNotBlank()) {
                    annotationMap[currentTag!!] = currentAnnotation.toString().trim()
                }
                // Start the new tag
                currentTag = correctedText
                currentAnnotation.clear()
            } else if (currentTag != null) {
                // This is content for the current tag.
                currentAnnotation.append(" ").append(correctedText)
            }
        }
        // Finalize the very last tag in the list
        if (currentTag != null && currentAnnotation.isNotBlank()) {
            annotationMap[currentTag!!] = currentAnnotation.toString().trim()
        }


        // 3. Correct OCR errors in the canvas detections
        val correctedCanvasDetections = canvasDetections.map {
            it.copy(text = correctOcrErrors(it.text))
        }

        // 4. Log the parsed margin content for verification.
        val marginLogOutput = annotationMap.entries.joinToString(", ") { "'${it.key}' -> '${it.value}'" }
        Log.d(TAG, "Parsed Margin Annotations: {$marginLogOutput}")


        // 5. Log the *corrected* canvas content for verification.
        val canvasLogOutput = correctedCanvasDetections.joinToString(", ") {
            val box = it.boundingBox
            val left = box.left.roundToInt()
            val top = box.top.roundToInt()
            val width = box.width().roundToInt()
            val height = box.height().roundToInt()
            "'${it.text}', [left:$left, top:$top, width:$width, height:$height]"
        }
        Log.d(TAG, "Parsed Canvas Content (Corrected): $canvasLogOutput")


        return Pair(correctedCanvasDetections, annotationMap)
    }
}