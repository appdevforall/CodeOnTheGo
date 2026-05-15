package org.appdevforall.codeonthego.computervision.data.repository

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

/**
 * Abstract contract for computer vision data sources.
 * Handles raw interactions with machine learning models (YOLO, MLKit)
 * without leaking domain logic.
 */
interface VisionRepository {
    suspend fun initModel(): Result<Unit>
    suspend fun detectWidgets(bitmap: Bitmap): Result<List<DetectionResult>>
    suspend fun recognizeText(bitmap: Bitmap): Result<List<Text.TextBlock>>
    fun isInitialized(): Boolean
    fun release()
}
