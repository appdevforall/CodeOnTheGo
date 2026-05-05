package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.xml.AndroidXmlGenerator
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import org.appdevforall.codeonthego.computervision.utils.getSortedScaledPlaceholders
import kotlin.comparisons.compareBy

class YoloToXmlConverter(
    private val geometryProcessor: LayoutGeometryProcessor,
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
        val uiCandidates = detections
            .filter { (it.isYolo || it.label == "text") && it.label != "widget_tag" }
            .filterNot { MetadataDetector.isMetadataDetection(it.label, it.text) }
            .distinctBy {
                if (it.label.startsWith("switch")) {
                    "${((it.boundingBox.top + it.boundingBox.bottom) / 2f).toInt() / 50}"
                } else {
                    "${it.label}:${it.boundingBox.left}:${it.boundingBox.top}:${it.boundingBox.right}:${it.boundingBox.bottom}"
                }
            }

        var scaledBoxes = uiCandidates.map { geometryProcessor.scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight) }

        val parents = scaledBoxes.filter { it.label != "text" && !annotationMatcher.isTag(it.text) }
        var texts = scaledBoxes.filter { it.label == "text" && !annotationMatcher.isTag(it.text) }

        scaledBoxes = geometryProcessor.assignTextToParents(parents, texts, scaledBoxes)
        texts = scaledBoxes.filter { it.label == "text" && !annotationMatcher.isTag(it.text) }
        scaledBoxes = geometryProcessor.assignNearbyTextToWidgets(scaledBoxes, texts)

        val uiElements = scaledBoxes.filter {
            !annotationMatcher.isTag(it.text) && !MetadataDetector.isMetadataDetection(it.label, it.text)
        }
        val widgetTags = detections.filter { it.label == "widget_tag" || (!it.isYolo && annotationMatcher.isTag(it.text)) }
        val canvasTags = widgetTags.map { geometryProcessor.scaleDetection(it, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight) }

        val finalAnnotations = annotationMatcher.matchAnnotationsToElements(canvasTags, uiElements, annotations)

        val sortedBoxes = uiElements.sortedWith(compareBy({ it.y }, { it.x }))

        val selectedImageOverrides = buildSelectedImageOverrides(
            uiElements = uiElements,
            selectedImagesByPlaceholderId = selectedImagesByPlaceholderId
        )

        return xmlGenerator.buildXml(
            boxes = sortedBoxes,
            annotations = finalAnnotations,
            selectedImageOverrides = selectedImageOverrides,
            targetDpHeight = targetDpHeight,
            wrapInScroll = wrapInScroll
        )
    }

    private fun buildSelectedImageOverrides(
        uiElements: List<ScaledBox>,
        selectedImagesByPlaceholderId: Map<String, String>
    ): Map<ScaledBox, String> {
        val placeholders = uiElements.getSortedScaledPlaceholders()

        return placeholders.mapIndexedNotNull { index, box ->
            val drawableReference = selectedImagesByPlaceholderId["ph_$index"]
                ?: return@mapIndexedNotNull null
            box to drawableReference
        }.toMap()
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
            val geometry = LayoutGeometryProcessor()
            val matcher = WidgetAnnotationMatcher()
            val generator = AndroidXmlGenerator(geometry)

            val converter = YoloToXmlConverter(geometry, matcher, generator)

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
