package org.appdevforall.codeonthego.computervision.utils

import android.graphics.Bitmap
import org.appdevforall.codeonthego.computervision.utils.BitmapUtils.calculateVerticalProjection

object SmartBoundaryDetector {

    private const val DEFAULT_EDGE_IGNORE_PERCENT = 0.05f
    private const val LEFT_ZONE_END_PERCENT = 0.4f
    private const val RIGHT_ZONE_START_PERCENT = 0.6f
    private const val MIN_GAP_WIDTH_PERCENT = 0.02
    private const val PRIMARY_ACTIVITY_THRESHOLD = 0.05f
    private const val FALLBACK_ACTIVITY_THRESHOLD = 0.01f
    private const val LEFT_FALLBACK_BOUND_PERCENT = 0.15f
    private const val RIGHT_FALLBACK_BOUND_PERCENT = 0.85f

    fun detectSmartBoundaries(
        bitmap: Bitmap,
        edgeIgnorePercent: Float = DEFAULT_EDGE_IGNORE_PERCENT
    ): Pair<Int, Int> {
        val width = bitmap.width
        val projection = calculateVerticalProjection(bitmap)
        val minimumGapWidth = (width * MIN_GAP_WIDTH_PERCENT).toInt()

        val ignoredEdgePixels = (width * edgeIgnorePercent).toInt()
        val leftZoneEnd = (width * LEFT_ZONE_END_PERCENT).toInt()
        val rightZoneStart = (width * RIGHT_ZONE_START_PERCENT).toInt()
        val rightZoneEnd = width - ignoredEdgePixels

        if (ignoredEdgePixels >= leftZoneEnd || rightZoneStart >= rightZoneEnd) {
            return Pair(
                (width * LEFT_FALLBACK_BOUND_PERCENT).toInt(),
                (width * RIGHT_FALLBACK_BOUND_PERCENT).toInt()
            )
        }

        val leftSignal = projection.copyOfRange(ignoredEdgePixels, leftZoneEnd)
        var (leftBound, leftGapLength) = findBestGapMidpoint(leftSignal, offset = ignoredEdgePixels)
        if (leftBound == null || leftGapLength < minimumGapWidth) {
            leftBound = findBestGapMidpoint(leftSignal, offset = ignoredEdgePixels, normalizeSignal = true).first
        }

        val rightSignal = projection.copyOfRange(rightZoneStart, rightZoneEnd)
        var (rightBound, rightGapLength) = findBestGapMidpoint(rightSignal, offset = rightZoneStart)
        if (rightBound == null || rightGapLength < minimumGapWidth) {
            rightBound = findBestGapMidpoint(rightSignal, offset = rightZoneStart, normalizeSignal = true).first
        }

        val finalLeftBound = leftBound ?: (width * LEFT_FALLBACK_BOUND_PERCENT).toInt()
        val finalRightBound = rightBound ?: (width * RIGHT_FALLBACK_BOUND_PERCENT).toInt()
        return Pair(finalLeftBound, finalRightBound)
    }

    private fun findBestGapMidpoint(
        signalSegment: FloatArray,
        offset: Int = 0,
        normalizeSignal: Boolean = false
    ): Pair<Int?, Int> {
        if (signalSegment.isEmpty()) {
            return Pair(null, 0)
        }

        val signal = if (normalizeSignal) {
            val minValue = signalSegment.minOrNull() ?: 0f
            FloatArray(signalSegment.size) { index -> signalSegment[index] - minValue }
        } else {
            signalSegment
        }

        val activityThresholdMultiplier = if (normalizeSignal) {
            FALLBACK_ACTIVITY_THRESHOLD
        } else {
            PRIMARY_ACTIVITY_THRESHOLD
        }
        val threshold = (signal.maxOrNull() ?: 0f) * activityThresholdMultiplier

        var maxGapLength = 0
        var maxGapMidpoint: Int? = null
        var currentGapStart = -1
        var previousIsActive = false

        signal.forEachIndexed { index, value ->
            val isActive = value > threshold
            if (previousIsActive && !isActive) {
                currentGapStart = index
            }

            val isGapClosing = currentGapStart != -1 && (index + 1 == signal.size || (!isActive && signal[index + 1] > threshold))
            if (isGapClosing) {
                val gapLength = index - currentGapStart + 1
                if (gapLength > maxGapLength) {
                    maxGapLength = gapLength
                    maxGapMidpoint = currentGapStart + (gapLength / 2)
                }
                currentGapStart = -1
            }

            previousIsActive = isActive
        }

        return Pair(maxGapMidpoint?.plus(offset), maxGapLength)
    }
}
