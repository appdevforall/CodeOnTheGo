package org.appdevforall.codeonthego.computervision.domain.model

import android.graphics.RectF

data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val score: Float,
    var text: String = "",
    val isYolo: Boolean = true
)