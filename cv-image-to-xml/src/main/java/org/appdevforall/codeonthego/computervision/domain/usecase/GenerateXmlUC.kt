package org.appdevforall.codeonthego.computervision.domain.usecase

import org.appdevforall.codeonthego.computervision.domain.YoloToXmlConverter
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import kotlinx.coroutines.CancellationException

/**
 * Use case responsible for generating the final Android XML layout string
 * and the corresponding strings.xml resource based on the detected UI elements.
 */
class GenerateXmlUC {
    operator fun invoke(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        selectedImagesByPlaceholderId: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int
    ): Result<Pair<String, String>> = runCatching {
        YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = annotations,
            selectedImagesByPlaceholderId = selectedImagesByPlaceholderId,
            sourceImageWidth = sourceImageWidth,
            sourceImageHeight = sourceImageHeight,
            targetDpWidth = targetDpWidth,
            targetDpHeight = targetDpHeight,
            wrapInScroll = true
        )
    }.onFailure {
        if (it is CancellationException) throw it
    }
}
