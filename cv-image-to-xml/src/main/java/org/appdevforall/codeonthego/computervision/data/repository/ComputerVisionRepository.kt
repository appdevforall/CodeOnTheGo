package org.appdevforall.codeonthego.computervision.data.repository

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import org.appdevforall.codeonthego.computervision.domain.RegionOcrProcessor
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

interface ComputerVisionRepository {

    suspend fun initializeModel(): Result<Unit>

    suspend fun runYoloInference(bitmap: Bitmap): Result<List<DetectionResult>>

    suspend fun runRegionOcr(
        bitmap: Bitmap,
        yoloDetections: List<DetectionResult>,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Result<RegionOcrProcessor.RegionOcrResult>

    suspend fun mergeDetections(
        enrichedComponents: List<DetectionResult>,
        remainingDetections: List<DetectionResult>,
        fullImageTextBlocks: List<Text.TextBlock>
    ): Result<List<DetectionResult>>

    suspend fun generateXml(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        selectedImagesByPlaceholderId: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int
    ): Result<Pair<String, String>>

    fun isModelInitialized(): Boolean

    fun releaseResources()
}
