package org.appdevforall.codeonthego.computervision.domain

import android.util.Log
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.abs
import kotlin.math.roundToInt

object MarginAnnotationParser {

    private const val TAG = "MarginAnnotationParser"
    private const val GAP_MULTIPLIER = 1.5f
    private const val HEIGHT_FRACTION = 0.8f

    private val TAG_REGEX = Regex("^(B|P|D|T|C|R|SW|S)-\\d+$")
    private val TAG_EXTRACT_REGEX = Regex("^([BPDTCRS8]W?)[^a-zA-Z0-9]*([\\dlIoO!]+)(?:\\s+(.+))?$")

    private fun normalizeOcrDigits(raw: String): String =
        raw.replace('l', '1').replace('I', '1').replace('!', '1')
            .replace('o', '0').replace('O', '0')

    private fun isTag(text: String): Boolean = text.matches(TAG_REGEX)

    private fun extractTag(text: String): Pair<String, String?>? {
        val trimmed = text.trim().trimEnd('.', ',', ';', '_', '|')
        val match = TAG_EXTRACT_REGEX.find(trimmed) ?: return null
        var prefix = match.groupValues[1]
        if (prefix == "8") prefix = "B"
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
            val centerX = detection.boundingBox.centerX()
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
        val leftCanvasTags = canvasTags.filter { (_, det) -> det.boundingBox.centerX() < canvasMidX }
        val rightCanvasTags = canvasTags.filter { (_, det) -> det.boundingBox.centerX() >= canvasMidX }

        val annotationMap = mutableMapOf<String, String>()
        annotationMap.putAll(parseMarginGroup(leftMarginDetections, leftCanvasTags))
        annotationMap.putAll(parseMarginGroup(rightMarginDetections, rightCanvasTags))

        val correctedCanvasDetections = canvasDetections

        val finalAnnotationLog = annotationMap.entries.joinToString(", ") { "'${it.key}' -> '${it.value}'" }
        Log.d(TAG, "Processed Margin Annotations: {$finalAnnotationLog}")

        val canvasLogOutput = correctedCanvasDetections.joinToString(", ") {
            val box = it.boundingBox
            "'${it.text}', [left:${box.left.roundToInt()}, top:${box.top.roundToInt()}, width:${box.width().roundToInt()}, height:${box.height().roundToInt()}]"
        }
        Log.d(TAG, "Parsed Canvas Content (Corrected): $canvasLogOutput")

        return Pair(correctedCanvasDetections, annotationMap)
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

        Log.d(TAG, "Spatial clustering: ${detections.size} lines -> ${gapBlocks.size} gap-blocks -> ${refinedBlocks.size} refined-blocks")

        val parsedBlocks = refinedBlocks.mapIndexed { i, block ->
            val result = parseBlock(block)
            val centerY = block.map { it.boundingBox.centerY() }.average().toFloat()
            val annotationText = result?.second
                ?: block.joinToString(" ") { it.text.trim() }.trim()

            Log.d(TAG, "Block $i: tag=${result?.first ?: "none"}, ${block.size} lines, text='${annotationText.take(40)}'")
            ParsedBlock(result?.first, annotationText, centerY, block.size)
        }

        val annotationMap = mutableMapOf<String, String>()
        val matchedBlockIndices = mutableSetOf<Int>()

        val tagCounts = parsedBlocks
            .mapNotNull { it.tag }
            .groupingBy { it }
            .eachCount()

        for ((i, parsed) in parsedBlocks.withIndex()) {
            if (parsed.tag == null || parsed.annotationText.isBlank()) continue
            val isUnique = tagCounts[parsed.tag] == 1
            if (isUnique && canvasTags.any { (tag, _) -> tag == parsed.tag }) {
                annotationMap[parsed.tag] = parsed.annotationText
                matchedBlockIndices.add(i)
                Log.d(TAG, "Pass1: tag='${parsed.tag}' matched by unique tag text")
            } else if (!isUnique) {
                Log.d(TAG, "Pass1: tag='${parsed.tag}' duplicated ${tagCounts[parsed.tag]} times, deferring to Pass2")
            }
        }

        val remainingBlocks = parsedBlocks.indices
            .filter { it !in matchedBlockIndices }
            .map { it to parsedBlocks[it] }
            .filter { (_, parsed) -> parsed.annotationText.length >= 5 }
            .sortedBy { (_, parsed) -> parsed.centerY }

        val usedCanvasTags = mutableSetOf<String>()
        for ((idx, parsed) in remainingBlocks) {
            val matchingTag = canvasTags
                .filter { (tag, _) -> tag !in annotationMap && tag !in usedCanvasTags }
                .minByOrNull { (_, det) -> abs(det.boundingBox.centerY() - parsed.centerY) }

            if (matchingTag != null) {
                Log.d(TAG, "Pass2: Y-matched block $idx (${parsed.lineCount} lines) -> '${matchingTag.first}'")
                annotationMap[matchingTag.first] = parsed.annotationText
                usedCanvasTags.add(matchingTag.first)
            }
        }

        return annotationMap
    }

    private fun clusterIntoBlocks(sorted: List<DetectionResult>): List<List<DetectionResult>> {
        if (sorted.size <= 1) return listOf(sorted)

        val avgHeight = sorted.map { it.boundingBox.height() }.average().toFloat()
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
        var tagFoundAtIndex = -1
        val annotationLines = mutableListOf<String>()

        for ((index, detection) in block.withIndex()) {
            val text = detection.text.trim()
            if (tag == null && index <= 1) {
                val tagExtraction = extractTag(text)
                if (tagExtraction != null) {
                    tag = tagExtraction.first
                    tagFoundAtIndex = index
                    tagExtraction.second?.let { annotationLines.add(it) }
                    continue
                }
            }
            annotationLines.add(text)
        }

        if (tag != null && tagFoundAtIndex == 1 && annotationLines.isNotEmpty()) {
            val firstLine = annotationLines.first()
            val tagPrefix = tag.substringBefore('-')
            if (firstLine.length <= 2 && firstLine.uppercase().startsWith(tagPrefix)) {
                annotationLines.removeAt(0)
            }
        }

        if (tag == null) return null
        return tag to annotationLines.joinToString(" ").trim()
    }
}