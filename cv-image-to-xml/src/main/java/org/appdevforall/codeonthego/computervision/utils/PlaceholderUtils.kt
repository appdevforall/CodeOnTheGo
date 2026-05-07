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

/**
 * Associates ordered image placeholders with their selected drawable references.
 * Useful for mapping user-selected gallery images to the physical canvas bounding boxes.
 */
fun List<ScaledBox>.buildPlaceholderOverrides(selectedImagesByPlaceholderId: Map<String, String>): Map<ScaledBox, String> {
    val placeholders = this.getSortedScaledPlaceholders()

    return placeholders.mapIndexedNotNull { index, box ->
        val drawableReference = selectedImagesByPlaceholderId["ph_$index"]
            ?: return@mapIndexedNotNull null
        box to drawableReference
    }.toMap()
}
