package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import kotlin.math.abs

object MarginAnnotationParser {

    /**
     * Extracts canvas UI elements and parses margin annotations, linking them together.
     * * @param detections The full list of detections from YOLO and OCR.
     * @param imageWidth The width of the source image in pixels.
     * @param leftGuidePct The percentage (0.0 to 1.0) defining the left margin boundary.
     * @param rightGuidePct The percentage (0.0 to 1.0) defining the right margin boundary.
     * @return A Pair containing the valid canvas UI detections and a mapped dictionary of [Widget Tag -> Annotation Text].
     */
    fun parse(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Pair<List<DetectionResult>, Map<String, String>> {
        val sanitizedDetections = detections.filterNot { MetadataDetector.isMetadataLabel(it.label) }

        val distribution = distributeDetections(sanitizedDetections, imageWidth, leftGuidePct, rightGuidePct)
        val canvasTags = extractCanvasTags(distribution.canvas)

        val annotationMap = parseMarginsGlobally(
            leftMargin = distribution.leftMargin,
            rightMargin = distribution.rightMargin,
            canvasTags = canvasTags
        )

        return Pair(distribution.canvas, annotationMap)
    }

    /**
     * Distributes raw detections into three zones: left margin, right margin, and the main canvas.
     * Metadata detections invading the canvas are forcefully pushed back to the margins.
     */
    private fun distributeDetections(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): DetectionDistribution {
        val leftMarginPx = imageWidth * leftGuidePct
        val rightMarginPx = imageWidth * rightGuidePct

        val canvas = mutableListOf<DetectionResult>()
        val leftMargin = mutableListOf<DetectionResult>()
        val rightMargin = mutableListOf<DetectionResult>()

        for (detection in detections) {
            val isMetadata = MetadataDetector.isCanvasMetadata(detection.text)
            val centerX = centerX(detection)

            when {
                isMetadata && centerX < (imageWidth / 2f) -> leftMargin.add(detection)
                isMetadata && centerX >= (imageWidth / 2f) -> rightMargin.add(detection)
                centerX > leftMarginPx && centerX < rightMarginPx -> canvas.add(detection)
                centerX <= leftMarginPx -> leftMargin.add(detection)
                else -> rightMargin.add(detection)
            }
        }

        return DetectionDistribution(canvas, leftMargin, rightMargin)
    }

    /**
     * Identifies and extracts valid widget tags from the canvas detections.
     */
    private fun extractCanvasTags(canvasDetections: List<DetectionResult>): List<Pair<String, DetectionResult>> {
        return canvasDetections.mapNotNull { det ->
            WidgetTagParser.extractTag(det.text)?.let { (tag, _) -> tag to det }
        }
    }

    /**
     * Processes both margins simultaneously to prevent cross-margin collisions.
     * Gathers all explicit annotations first, then resolves all implicit blocks
     * against a shared pool of remaining tags.
     */
    private fun parseMarginsGlobally(
        leftMargin: List<DetectionResult>,
        rightMargin: List<DetectionResult>,
        canvasTags: List<Pair<String, DetectionResult>>
    ): Map<String, String> {
        val leftBlocks = extractBlocks(leftMargin.sortedBy { it.boundingBox.top })
        val rightBlocks = extractBlocks(rightMargin.sortedBy { it.boundingBox.top })

        val globalExplicitAnnotations = mergeAnnotations(
            leftBlocks.explicitAnnotations,
            rightBlocks.explicitAnnotations
        )

        val allImplicitBlocks = leftBlocks.implicitBlocks + rightBlocks.implicitBlocks

        val resolvedImplicitAnnotations = resolveImplicitBlocks(
            implicitBlocks = allImplicitBlocks,
            canvasTags = canvasTags,
            existingAnnotations = globalExplicitAnnotations
        )

        return mergeAnnotations(globalExplicitAnnotations, resolvedImplicitAnnotations)
    }

    /**
     * Merges multiple annotation maps. If a tag exists in multiple maps,
     * their values are combined separated by " | ".
     */
    private fun mergeAnnotations(vararg maps: Map<String, String>): MutableMap<String, String> {
        return maps.flatMap { it.toList() }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.joinToString(" | ") }
            .toMutableMap()
    }

    /**
     * Reads vertically through margin detections and groups them into text blocks.
     * Blocks starting with an explicit tag become explicit annotations, while untagged blocks are stored as implicit.
     * Side-based prefix heuristics are intentionally not applied here because OCR can place
     * a valid explicit tag on the opposite margin from its detected canvas tag.
     */
    private fun extractBlocks(
        sortedDetections: List<DetectionResult>
    ): GroupedBlocks {
        val blocks = GroupedBlocks()
        var currentTag: String? = null
        var currentText = StringBuilder()
        var blockStartY = 0f

        fun saveCurrentBlock() {
            if (currentTag != null) {
                blocks.explicitAnnotations[currentTag!!] = currentText.toString().trim()
            } else if (currentText.isNotBlank()) {
                blocks.implicitBlocks.add(ParsedBlock(currentText.toString().trim(), blockStartY))
            }
        }

        for (det in sortedDetections) {
            val text = det.text.trim().trimStart('|', ':', ';', '.', ',', '_')
            val extraction = WidgetTagParser.extractTag(text)

            val isExplicitTag = extraction != null

            if (isExplicitTag) {
                saveCurrentBlock()

                currentTag = extraction.first
                currentText = StringBuilder()
                blockStartY = centerY(det)

                val trailing = extraction.second?.trim()
                if (!trailing.isNullOrBlank() && WidgetTagParser.normalizeTagText(trailing) != currentTag) {
                    currentText.append(trailing).append(" ")
                }
            } else {
                if (currentText.isEmpty()) blockStartY = centerY(det)
                currentText.append(text).append(" ")
            }
        }
        saveCurrentBlock()

        return blocks
    }

    /**
     * Resolves implicit (untagged) text blocks by associating them with the nearest vertical canvas tag
     * of the most appropriate prefix.
     */
    private fun resolveImplicitBlocks(
        implicitBlocks: List<ParsedBlock>,
        canvasTags: List<Pair<String, DetectionResult>>,
        existingAnnotations: Map<String, String>
    ): Map<String, String> {
        val resolvedAnnotations = mutableMapOf<String, String>()

        val canvasTagsByPrefix = canvasTags
            .groupBy { (tag, _) -> tag.substringBefore('-') }
            .mapValues { (_, tags) -> tags.sortedBy { (_, det) -> centerY(det) } }

        val unresolvedTagsByPrefix = canvasTagsByPrefix
            .mapValues { (_, tags) ->
                tags.map { it.first }
                    .filter { tag -> tag !in existingAnnotations }
                    .sortedBy { tag -> WidgetTagParser.extractOrdinal(tag) ?: Int.MAX_VALUE }
                    .toMutableList()
            }.toMutableMap()

        for (block in implicitBlocks.sortedBy { it.centerY }) {
            val closestPrefix = unresolvedTagsByPrefix
                .filterValues { it.isNotEmpty() }
                .minByOrNull { (prefix, remainingTags) ->
                    val nearestTagY = canvasTagsByPrefix[prefix]
                        ?.firstOrNull { (tag, _) -> tag == remainingTags.firstOrNull() }
                        ?.second?.let { centerY(it) } ?: Float.MAX_VALUE
                    abs(nearestTagY - block.centerY)
                }?.key ?: continue

            val assignedTag = unresolvedTagsByPrefix[closestPrefix]?.removeFirstOrNull() ?: continue
            resolvedAnnotations[assignedTag] = block.annotationText
        }

        return resolvedAnnotations
    }

    private fun centerX(detection: DetectionResult): Float {
        return (detection.boundingBox.left + detection.boundingBox.right) / 2f
    }

    private fun centerY(detection: DetectionResult): Float {
        return (detection.boundingBox.top + detection.boundingBox.bottom) / 2f
    }

    private data class DetectionDistribution(
        val canvas: List<DetectionResult>,
        val leftMargin: List<DetectionResult>,
        val rightMargin: List<DetectionResult>
    )

    private data class GroupedBlocks(
        val explicitAnnotations: MutableMap<String, String> = mutableMapOf(),
        val implicitBlocks: MutableList<ParsedBlock> = mutableListOf()
    )

    private data class ParsedBlock(
        val annotationText: String,
        val centerY: Float
    )
}
