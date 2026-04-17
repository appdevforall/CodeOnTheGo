package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlin.math.hypot

class GenericBoxResolver {

    fun resolve(detections: List<DetectionResult>): List<DetectionResult> {
        val dropdownSymbols = detections.filter { it.label == "dropdown_symbol" }

        return detections.mapNotNull { det ->
            when (det.label) {
                "dropdown_symbol" -> null
                "generic_box" -> {
                    val hasSymbolNearby = dropdownSymbols.any { symbol ->
                        isNearby(det.boundingBox, symbol.boundingBox, 0.8f)
                    }
                    det.copy(label = if (hasSymbolNearby) "dropdown" else "text_entry_box")
                }
                else -> det
            }
        }
    }

    private fun isNearby(box1: RectF, box2: RectF, thresholdFactor: Float = 1.5f): Boolean {
        val avgDim1 = (box1.width() + box1.height()) / 2f
        val distanceThreshold = thresholdFactor * avgDim1

        val distance = hypot(
            (box1.centerX() - box2.centerX()).toDouble(),
            (box1.centerY() - box2.centerY()).toDouble()
        ).toFloat()

        return distance < distanceThreshold
    }
}
