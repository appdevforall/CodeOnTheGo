package org.appdevforall.codeonthego.computervision.data.repository
import android.graphics.Bitmap
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import com.google.mlkit.vision.text.Text

interface ComputerVisionRepository {

    suspend fun initializeModel(): Result<Unit>

    suspend fun runYoloInference(bitmap: Bitmap): Result<List<DetectionResult>>

    suspend fun runOcrRecognition(bitmap: Bitmap): Result<List<Text.TextBlock>>

    suspend fun mergeDetections(
        yoloDetections: List<DetectionResult>,
        textBlocks: List<Text.TextBlock>
    ): Result<List<DetectionResult>>

    suspend fun generateXml(
        detections: List<DetectionResult>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int = 360,
        targetDpHeight: Int = 640
    ): Result<String>

    fun preprocessBitmapForOcr(bitmap: Bitmap): Bitmap

    fun isModelInitialized(): Boolean

    fun releaseResources()
}