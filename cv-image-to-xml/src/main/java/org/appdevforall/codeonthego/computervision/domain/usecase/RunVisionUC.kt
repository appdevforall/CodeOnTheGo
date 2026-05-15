package org.appdevforall.codeonthego.computervision.domain.usecase

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import org.appdevforall.codeonthego.computervision.data.repository.VisionRepository
import org.appdevforall.codeonthego.computervision.domain.DetectionMerger
import org.appdevforall.codeonthego.computervision.domain.GenericBoxResolver
import org.appdevforall.codeonthego.computervision.domain.MarginAnnotationParser
import org.appdevforall.codeonthego.computervision.domain.RegionOcrProcessor
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.ui.CvOperation

/**
 * Main use case that orchestrates the complete computer vision pipeline:
 * 1. YOLO Detection -> 2. Region OCR -> 3. Merging -> 4. Metadata Parsing.
 */
class RunVisionUC(
    private val repository: VisionRepository,
    private val boxResolver: GenericBoxResolver,
    private val regionOcrProcessor: RegionOcrProcessor
) {
    data class VisionResult(
        val detections: List<DetectionResult>,
        val annotations: Map<String, String>
    )

    suspend operator fun invoke(
        bitmap: Bitmap,
        leftPct: Float,
        rightPct: Float,
        onProgress: (CvOperation) -> Unit
    ): Result<VisionResult> = runCatching {

        onProgress(CvOperation.RunningYolo)
        val rawDetections = repository.detectWidgets(bitmap).getOrThrow()
        val resolvedDetections = boxResolver.resolve(rawDetections)

        onProgress(CvOperation.RunningOcr)
        val ocrResult = regionOcrProcessor.process(bitmap, resolvedDetections, leftPct, rightPct)

        onProgress(CvOperation.MergingDetections)
        val mergedDetections = DetectionMerger(
            ocrResult.enrichedDetections,
            ocrResult.remainingDetections,
            ocrResult.fullImageTextBlocks
        ).merge()

        val leftBound = bitmap.width * leftPct
        val rightBound = bitmap.width * rightPct
        val canvasOnlyMerged = mergedDetections.filter { detection ->
            detection.isYolo || detection.boundingBox.centerX() in leftBound..rightBound
        }

        val allDetections = canvasOnlyMerged + ocrResult.marginDetections

        val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
            detections = allDetections,
            imageWidth = bitmap.width,
            leftGuidePct = leftPct,
            rightGuidePct = rightPct
        )

        VisionResult(canvasDetections, annotationMap)
    }.onFailure {
        if (it is CancellationException) throw it
    }
}
