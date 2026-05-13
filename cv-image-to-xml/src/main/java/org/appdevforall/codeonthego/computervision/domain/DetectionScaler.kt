package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scales the normalized YOLO coordinates (0.0 to 1.0) to the target dimensions
 * in DP (e.g., 360x640) of the Android screen.
 */
object DetectionScaler {
    private const val MIN_W_ANY = 8
    private const val MIN_H_ANY = 8

    fun scale(
        detection: DetectionResult, sourceWidth: Int, sourceHeight: Int, targetW: Int, targetH: Int
    ): ScaledBox {
        if (sourceWidth == 0 || sourceHeight == 0) {
            return ScaledBox(detection.label, detection.text, 0, 0, MIN_W_ANY, MIN_H_ANY, MIN_W_ANY / 2, MIN_H_ANY / 2, Rect(0, 0, MIN_W_ANY, MIN_H_ANY))
        }
        val rect = detection.boundingBox
        val normCx = ((rect.left + rect.right) / 2f) / sourceWidth.toFloat()
        val normCy = ((rect.top + rect.bottom) / 2f) / sourceHeight.toFloat()
        val normW = (rect.right - rect.left) / sourceWidth.toFloat()
        val normH = (rect.bottom - rect.top) / sourceHeight.toFloat()

        val x = max(0, ((normCx - normW / 2f) * targetW).roundToInt())
        val y = max(0, ((normCy - normH / 2f) * targetH).roundToInt())
        val w = max(MIN_W_ANY, (normW * targetW).roundToInt())
        val h = max(MIN_H_ANY, (normH * targetH).roundToInt())

        return ScaledBox(
            detection.label, detection.text, x, y, w, h, x + w / 2, y + h / 2, Rect(x, y, x + w, y + h)
        )
    }
}
