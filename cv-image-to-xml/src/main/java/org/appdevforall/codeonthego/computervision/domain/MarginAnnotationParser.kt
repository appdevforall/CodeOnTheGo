package org.appdevforall.codeonthego.computervision.domain

import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.roundToInt

object MarginAnnotationParser {

    private const val TAG = "MarginAnnotationParser"

    /**
     * Partitions detections into canvas items and a map of margin annotations.
     * It also logs the parsed annotations for verification.
     *
     * @param detections The complete list of merged (YOLO + OCR) detections.
     * @param imageWidth The width of the source image, used for calculating pixel boundaries.
     * @param leftGuidePct The percentage from the left edge defining the start of the canvas.
     * @param rightGuidePct The percentage from the left edge defining the end of the canvas.
     * @return A Pair where the first element is the list of canvas detections and the second
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

        // 2. Parse the margin detections to create the annotation map
        val annotationMap = mutableMapOf<String, String>()
        for (marginDetection in marginDetections) {
            val text = marginDetection.text.trim()
            if (text.isBlank()) continue

            val parts = text.split(Regex("\\s+"), 2)
            val tag = parts.getOrNull(0)
            val annotation = parts.getOrNull(1)

            if (tag != null && annotation != null) {
                annotationMap[tag] = annotation
            }
        }

        // 3. Log the parsed map for verification, as requested, with rounded integer coordinates.
        val logOutput = marginDetections.joinToString(", ") {
            val box = it.boundingBox
            val left = box.left.roundToInt()
            val top = box.top.roundToInt()
            val width = box.width().roundToInt()
            val height = box.height().roundToInt()
            "'${it.text}', [left:$left, top:$top, width:$width, height:$height]"
        }
        Log.d(TAG, "Parsed Margin Content: $logOutput")

        return Pair(canvasDetections, annotationMap)
    }
}