package org.appdevforall.codeonthego.computervision.data.repository

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.computervision.data.source.OcrSource
import org.appdevforall.codeonthego.computervision.data.source.YoloModelSource
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

class VisionRepositoryImpl(
    private val assetManager: AssetManager,
    private val yoloModelSource: YoloModelSource,
    private val ocrSource: OcrSource
) : VisionRepository {

    override suspend fun initModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            yoloModelSource.initialize(assetManager)
        }
    }

    override suspend fun detectWidgets(bitmap: Bitmap): Result<List<DetectionResult>> =
        withContext(Dispatchers.Default) {
            runCatching { yoloModelSource.runInference(bitmap) }
        }

    override suspend fun recognizeText(bitmap: Bitmap): Result<List<Text.TextBlock>> =
        withContext(Dispatchers.Default) {
            ocrSource.recognizeText(bitmap)
        }

    override fun isInitialized(): Boolean = yoloModelSource.isInitialized()

    override fun release() {
        yoloModelSource.release()
    }
}
