package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.xml.AndroidXmlGenerator
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import org.appdevforall.codeonthego.computervision.utils.buildPlaceholderOverrides
import kotlin.comparisons.compareBy

class YoloToXmlConverter(
    private val annotationMatcher: WidgetAnnotationMatcher,
    private val xmlGenerator: AndroidXmlGenerator
) {

    fun generateXmlLayout(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        selectedImagesByPlaceholderId: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int,
        wrapInScroll: Boolean = true
    ): Pair<String, String> {
        // 1. Filter and prepare base UI candidates
        val uiCandidates = extractUiCandidates(detections)

        // 2. Scale detections to target DP dimensions
        val scaledBoxes = scaleDetections(uiCandidates, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight)

        // 3. Associate isolated text detections to their respective UI widgets
        val associatedBoxes = associateTextToWidgets(scaledBoxes)

        // 4. Clean up and finalize the UI elements list
        val uiElements = finalizeUiElements(associatedBoxes)

        // 5. Extract and scale reference tags (e.g., T-1, B-1) from the canvas
        val canvasTags = extractCanvasTags(detections, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight)

        // 6. Match margin annotations with the extracted UI elements
        val finalAnnotations = annotationMatcher.matchAnnotationsToElements(canvasTags, uiElements, annotations)

        // 7. Sort boxes top-to-bottom, left-to-right for sequential XML rendering
        val sortedBoxes = uiElements.sortedWith(compareBy({ it.y }, { it.x }))

        // 8. Prepare local drawable resources overrides for image placeholders
        val selectedImageOverrides = uiElements.buildPlaceholderOverrides(selectedImagesByPlaceholderId)

        // 9. Generate final XML output
        return xmlGenerator.buildXml(
            boxes = sortedBoxes,
            annotations = finalAnnotations,
            selectedImageOverrides = selectedImageOverrides,
            targetDpHeight = targetDpHeight,
            wrapInScroll = wrapInScroll
        )
    }

    private fun extractUiCandidates(detections: List<DetectionResult>): List<DetectionResult> {
        return detections
            .filter { (it.isYolo || it.label == "text") && it.label != "widget_tag" }
            .filterNot { MetadataDetector.isMetadataDetection(it.label, it.text) }
            .distinctBy {
                if (it.label.startsWith("switch")) {
                    // Deduplicate switches by grouping them within a 50px vertical band
                    "${((it.boundingBox.top + it.boundingBox.bottom) / 2f).toInt() / 50}"
                } else {
                    // Exact coordinate deduplication for other widgets
                    "${it.label}:${it.boundingBox.left}:${it.boundingBox.top}:${it.boundingBox.right}:${it.boundingBox.bottom}"
                }
            }
    }

    private fun scaleDetections(
        candidates: List<DetectionResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<ScaledBox> {
        return candidates.map { 
            DetectionScaler.scale(it, sourceWidth, sourceHeight, targetWidth, targetHeight)
        }
    }

    private fun associateTextToWidgets(scaledBoxes: List<ScaledBox>): List<ScaledBox> {
        val parents = scaledBoxes.filter { it.label != "text" }
        val initialTexts = scaledBoxes.filter { it.label == "text" && !annotationMatcher.isTag(it.text) }

        val textAssignedBoxes = TextAssociator.assignTextToParents(parents, initialTexts, scaledBoxes)
        val remainingTexts = textAssignedBoxes.filter { it.label == "text" && !annotationMatcher.isTag(it.text) }

        return TextAssociator.assignNearbyTextToWidgets(textAssignedBoxes, remainingTexts)
    }

    private fun finalizeUiElements(boxes: List<ScaledBox>): List<ScaledBox> {
        return boxes.filter {
            // Keep the widget if it's not pure text, or if it is text but not recognized as a tag.
            (it.label != "text" || !annotationMatcher.isTag(it.text)) && 
            !MetadataDetector.isMetadataDetection(it.label, it.text)
        }
    }

    private fun extractCanvasTags(
        detections: List<DetectionResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<ScaledBox> {
        val widgetTags = detections.filter { 
            it.label == "widget_tag" || (!it.isYolo && annotationMatcher.isTag(it.text)) 
        }
        return widgetTags.map { 
            DetectionScaler.scale(it, sourceWidth, sourceHeight, targetWidth, targetHeight)
        }
    }

    companion object {
        fun generateXmlLayout(
            detections: List<DetectionResult>,
            annotations: Map<String, String>,
            selectedImagesByPlaceholderId: Map<String, String> = emptyMap(),
            sourceImageWidth: Int,
            sourceImageHeight: Int,
            targetDpWidth: Int,
            targetDpHeight: Int,
            wrapInScroll: Boolean = true
        ): Pair<String, String> {
            val matcher = WidgetAnnotationMatcher()
            val generator = AndroidXmlGenerator()

            val converter = YoloToXmlConverter(matcher, generator)

            return converter.generateXmlLayout(
                detections = detections,
                annotations = annotations,
                selectedImagesByPlaceholderId = selectedImagesByPlaceholderId,
                sourceImageWidth = sourceImageWidth,
                sourceImageHeight = sourceImageHeight,
                targetDpWidth = targetDpWidth,
                targetDpHeight = targetDpHeight,
                wrapInScroll = wrapInScroll
            )
        }
    }
}
