package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.abs

object MarginAnnotationParser {
    private const val GAP_MULTIPLIER = 1.5f
    private const val HEIGHT_FRACTION = 0.8f

    private val TAG_REGEX = Regex("^(?i)(B|P|D|T|C|R|SW|S)-\\d+$")
    private val TAG_EXTRACT_REGEX = Regex("^(?i)([BPDTCRS8]\\s*W?)[^a-zA-Z0-9]*([\\dlIoO!]+)(?:\\s+(.+))?$")

    private fun normalizeOcrDigits(raw: String): String =
        raw.replace('l', '1').replace('I', '1').replace('!', '1')
            .replace('o', '0').replace('O', '0')

    private fun isTag(text: String): Boolean = text.matches(TAG_REGEX)

    private fun extractTag(text: String): Pair<String, String?>? {
        val trimmed = text.trim().trimEnd('.', ',', ';', '_', '|')
        val match = TAG_EXTRACT_REGEX.find(trimmed) ?: return null

        var prefix = match.groupValues[1].replace(Regex("\\s+"), "").uppercase()
        if (prefix == "8") prefix = "B"
        if (prefix == "8W" || prefix == "S8") prefix = "SW"

        val digit = normalizeOcrDigits(match.groupValues[2])
        val remaining = match.groupValues[3].takeIf { it.isNotBlank() }
        val tag = "$prefix-$digit"

        if (isTag(tag)) return tag to remaining
        return null
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
        val leftMarginDetections = mutableListOf<DetectionResult>()
        val rightMarginDetections = mutableListOf<DetectionResult>()

        for (detection in detections) {
            val centerX = centerX(detection)
            when {
                centerX > leftMarginPx && centerX < rightMarginPx -> canvasDetections.add(detection)
                centerX <= leftMarginPx -> leftMarginDetections.add(detection)
                else -> rightMarginDetections.add(detection)
            }
        }

        val canvasTags = canvasDetections.mapNotNull { det ->
            extractTag(det.text)?.let { (tag, _) -> tag to det }
        }

        val canvasMidX = imageWidth * (leftGuidePct + rightGuidePct) / 2f
        val leftCanvasTags = canvasTags.filter { (_, det) -> centerX(det) < canvasMidX }
        val rightCanvasTags = canvasTags.filter { (_, det) -> centerX(det) >= canvasMidX }

        val annotationMap = mutableMapOf<String, String>()
        annotationMap.putAll(parseMarginGroup(leftMarginDetections, leftCanvasTags))
        annotationMap.putAll(parseMarginGroup(rightMarginDetections, rightCanvasTags))

        return Pair(canvasDetections, annotationMap)
    }

    private data class ParsedBlock(
        val tag: String?,
        val annotationText: String,
        val centerY: Float,
        val lineCount: Int
    )

    private fun parseMarginGroup(
        detections: List<DetectionResult>,
        canvasTags: List<Pair<String, DetectionResult>>
    ): Map<String, String> {
        if (detections.isEmpty()) return emptyMap()

        val validPrefixes = canvasTags.map { (tag, _) -> tag.substringBefore('-') }.toSet()

        val sorted = detections.sortedBy { it.boundingBox.top }
        val gapBlocks = clusterIntoBlocks(sorted)
        val refinedBlocks = gapBlocks.flatMap { splitAtTags(it, validPrefixes) }

        val parsedBlocks = refinedBlocks.mapIndexed { _, block ->
            val result = parseBlock(block)
            val centerY = block.map { centerY(it) }.average().toFloat()
            val annotationText = result?.second
                ?: block.joinToString(" ") { it.text.trim() }.trim()

            ParsedBlock(result?.first, annotationText, centerY, block.size)
        }

        val annotationMap = mutableMapOf<String, String>()

        val canvasTagsByPrefix = canvasTags
            .groupBy { (tag, _) -> tag.substringBefore('-') }
            .mapValues { (_, tags) ->
                tags.sortedBy { (_, det) -> centerY(det) }
            }

        val explicitBlocks = parsedBlocks
            .filter { it.tag != null && it.annotationText.isNotBlank() }

        val implicitBlocks = parsedBlocks
            .filter { it.tag == null && it.annotationText.length >= 5 }

        for (block in explicitBlocks) {
            val tag = block.tag ?: continue
            if (canvasTags.isEmpty() || canvasTags.any { (canvasTag, _) -> canvasTag == tag }) {
                annotationMap[tag] = block.annotationText
            }
        }

        if (canvasTags.isEmpty()) return annotationMap

        val unresolvedTagsByPrefix = canvasTagsByPrefix
            .mapValues { (_, tags) ->
                tags.map { it.first }
                    .filter { tag -> tag !in annotationMap }
                    .sortedBy { tag -> extractOrdinal(tag) ?: Int.MAX_VALUE }
                    .toMutableList()
            }
            .toMutableMap()

        val implicitBlocksSorted = implicitBlocks.sortedBy { it.centerY }

        for (block in implicitBlocksSorted) {
            val closestPrefix = unresolvedTagsByPrefix
                .filterValues { it.isNotEmpty() }
                .minByOrNull { (prefix, remainingTags) ->
                    val nearestTagY = canvasTagsByPrefix[prefix]
                        ?.firstOrNull { (tag, _) -> tag == remainingTags.firstOrNull() }
                        ?.second
                        ?.let { centerY(it) }
                        ?: Float.MAX_VALUE

                    abs(nearestTagY - block.centerY)
                }
                ?.key
                ?: continue

            val assignedTag = unresolvedTagsByPrefix[closestPrefix]?.removeFirstOrNull() ?: continue
            annotationMap[assignedTag] = block.annotationText
        }

        return annotationMap
    }

    private fun extractOrdinal(tag: String): Int? {
        return tag.substringAfter('-', "").toIntOrNull()
    }

    private fun centerX(detection: DetectionResult): Float {
        return (detection.boundingBox.left + detection.boundingBox.right) / 2f
    }

    private fun centerY(detection: DetectionResult): Float {
        return (detection.boundingBox.top + detection.boundingBox.bottom) / 2f
    }

    private fun clusterIntoBlocks(sorted: List<DetectionResult>): List<List<DetectionResult>> {
        if (sorted.size <= 1) return listOf(sorted)

        val avgHeight = sorted.map { it.boundingBox.bottom - it.boundingBox.top }.average().toFloat()
        val gaps = (0 until sorted.size - 1).map { i ->
            sorted[i + 1].boundingBox.top - sorted[i].boundingBox.bottom
        }
        val avgGap = gaps.average().toFloat()
        val gapThreshold = maxOf(avgGap * GAP_MULTIPLIER, avgHeight * HEIGHT_FRACTION)

        val blocks = mutableListOf<List<DetectionResult>>()
        var currentBlock = mutableListOf(sorted.first())

        for (i in gaps.indices) {
            if (gaps[i] > gapThreshold) {
                blocks.add(currentBlock.toList())
                currentBlock = mutableListOf()
            }
            currentBlock.add(sorted[i + 1])
        }
        blocks.add(currentBlock.toList())

        return blocks
    }

    private fun splitAtTags(
        block: List<DetectionResult>,
        validPrefixes: Set<String>
    ): List<List<DetectionResult>> {
        if (block.size <= 1) return listOf(block)

        val result = mutableListOf<List<DetectionResult>>()
        var currentBlock = mutableListOf<DetectionResult>()

        for (detection in block) {
            val tagExtraction = extractTag(detection.text.trim())
            val isValidSplit = tagExtraction != null &&
                (validPrefixes.isEmpty() || tagExtraction.first.substringBefore('-') in validPrefixes)

            if (currentBlock.isNotEmpty() && isValidSplit) {
                result.add(currentBlock.toList())
                currentBlock = mutableListOf()
            }
            currentBlock.add(detection)
        }
        if (currentBlock.isNotEmpty()) result.add(currentBlock.toList())

        return result
    }

    private fun parseBlock(block: List<DetectionResult>): Pair<String, String>? {
        var tag: String? = null
        val annotationLines = mutableListOf<String>()

        for ((index, detection) in block.withIndex()) {
            val text = detection.text
                .trim()
                .trimStart('|', ':', ';', '.', ',', '_')

            val tagExtraction = extractTag(text)

            if (tag == null && tagExtraction != null && index <= 2) {
                tag = tagExtraction.first
                tagExtraction.second
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(annotationLines::add)
                continue
            }

            annotationLines.add(text)
        }

        val cleanedAnnotation = annotationLines
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (tag == null) return null
        return tag to cleanedAnnotation
    }
}
