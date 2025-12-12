package appdevforall.codeonthego.computervision.data.repository

import android.content.res.AssetManager
import android.graphics.Bitmap
import appdevforall.codeonthego.computervision.DetectionMerger
import appdevforall.codeonthego.computervision.YoloToXmlConverter
import appdevforall.codeonthego.computervision.data.source.OcrSource
import appdevforall.codeonthego.computervision.data.source.YoloModelSource
import appdevforall.codeonthego.computervision.domain.model.DetectionResult
import appdevforall.codeonthego.computervision.utils.BitmapUtils
import appdevforall.codeonthego.computervision.utils.TextCleaner
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ComputerVisionRepositoryImpl(
    private val assetManager: AssetManager,
    private val yoloModelSource: YoloModelSource,
    private val ocrSource: OcrSource
) : ComputerVisionRepository {

    override suspend fun initializeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            yoloModelSource.initialize(assetManager)
        }
    }

    override suspend fun runYoloInference(bitmap: Bitmap): Result<List<DetectionResult>> =
        withContext(Dispatchers.Default) {
            runCatching {
                yoloModelSource.runInference(bitmap)
            }
        }

    override suspend fun runOcrRecognition(bitmap: Bitmap): Result<List<Text.TextBlock>> =
        withContext(Dispatchers.IO) {
            ocrSource.recognizeText(bitmap)
        }

    override suspend fun mergeDetections(
        yoloDetections: List<DetectionResult>,
        textBlocks: List<Text.TextBlock>
    ): Result<List<DetectionResult>> = withContext(Dispatchers.Default) {
        runCatching {
            val merger = DetectionMerger(yoloDetections, textBlocks)
            val merged = merger.merge()

            merged.forEach { detection ->
                if (detection.text.isNotEmpty()) {
                    detection.text = TextCleaner.cleanText(detection.text)
                }
            }

            merged
        }
    }

    override suspend fun generateXml(
        detections: List<DetectionResult>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            YoloToXmlConverter.generateXmlLayout(
                detections = detections,
                sourceImageWidth = sourceImageWidth,
                sourceImageHeight = sourceImageHeight,
                targetDpWidth = targetDpWidth,
                targetDpHeight = targetDpHeight
            )
        }
    }

    override fun preprocessBitmapForOcr(bitmap: Bitmap): Bitmap {
        return BitmapUtils.preprocessForOcr(bitmap)
    }

    override fun isModelInitialized(): Boolean = yoloModelSource.isInitialized()

    override fun releaseResources() {
        yoloModelSource.release()
    }
}