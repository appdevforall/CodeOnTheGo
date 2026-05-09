package org.appdevforall.codeonthego.computervision.data.repository

import android.content.res.AssetManager
import android.graphics.Bitmap
import org.appdevforall.codeonthego.computervision.domain.DetectionMerger
import org.appdevforall.codeonthego.computervision.domain.RegionOcrProcessor
import org.appdevforall.codeonthego.computervision.domain.YoloToXmlConverter
import org.appdevforall.codeonthego.computervision.data.source.YoloModelSource
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.computervision.domain.GenericBoxResolver

class ComputerVisionRepositoryImpl(
    private val assetManager: AssetManager,
    private val yoloModelSource: YoloModelSource,
    private val regionOcrProcessor: RegionOcrProcessor
) : ComputerVisionRepository {

    override suspend fun initializeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            yoloModelSource.initialize(assetManager)
        }
    }

    override suspend fun runYoloInference(bitmap: Bitmap): Result<List<DetectionResult>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val rawDetections = yoloModelSource.runInference(bitmap)
                GenericBoxResolver().resolve(rawDetections)
            }
        }

    override suspend fun runRegionOcr(
        bitmap: Bitmap,
        yoloDetections: List<DetectionResult>,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Result<RegionOcrProcessor.RegionOcrResult> = withContext(Dispatchers.Default) {
        runCatching {
            regionOcrProcessor.process(bitmap, yoloDetections, leftGuidePct, rightGuidePct)
        }
    }

    override suspend fun mergeDetections(
        enrichedComponents: List<DetectionResult>,
        remainingDetections: List<DetectionResult>,
        fullImageTextBlocks: List<Text.TextBlock>
    ): Result<List<DetectionResult>> = withContext(Dispatchers.Default) {
        runCatching {
            DetectionMerger(enrichedComponents, remainingDetections, fullImageTextBlocks).merge()
        }
    }

    override suspend fun generateXml(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        selectedImagesByPlaceholderId: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int
    ): Result<Pair<String, String>> = withContext(Dispatchers.Default) {
        runCatching {
            YoloToXmlConverter.generateXmlLayout(
                detections = detections,
                annotations = annotations,
                selectedImagesByPlaceholderId = selectedImagesByPlaceholderId,
                sourceImageWidth = sourceImageWidth,
                sourceImageHeight = sourceImageHeight,
                targetDpWidth = targetDpWidth,
                targetDpHeight = targetDpHeight
            )
        }
    }

    override fun isModelInitialized(): Boolean = yoloModelSource.isInitialized()

    override fun releaseResources() {
        yoloModelSource.release()
    }
}
