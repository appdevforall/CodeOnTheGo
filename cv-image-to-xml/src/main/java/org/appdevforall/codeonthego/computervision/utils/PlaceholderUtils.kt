package org.appdevforall.codeonthego.computervision.utils

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox

const val IMAGE_PLACEHOLDER_LABEL = "image_placeholder"

fun List<DetectionResult>.getSortedPlaceholders(): List<DetectionResult> {
    return this.filter { it.label == IMAGE_PLACEHOLDER_LABEL }
        .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
}

fun List<ScaledBox>.getSortedScaledPlaceholders(): List<ScaledBox> {
    return this.filter { it.label == IMAGE_PLACEHOLDER_LABEL }
        .sortedWith(compareBy({ it.y }, { it.x }))
}
