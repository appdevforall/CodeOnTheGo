package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.appdevforall.codeonthego.computervision.data.source.OcrSource
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.utils.BitmapUtils
import org.appdevforall.codeonthego.computervision.utils.OcrTextAssembler

class RegionOcrProcessor(
    private val ocrSource: OcrSource,
    private val componentPadding: Int = 10
) {

    private val interactiveLabels = setOf(
        "button",
        "switch_on",
        "switch_off",
        "text_entry_box",
        "dropdown",
        "radio_button_checked",
        "radio_button_unchecked",
        "slider",
        "image_placeholder",
        "widget_tag"
    )

    data class RegionOcrResult(
        val enrichedDetections: List<DetectionResult>,
        val remainingDetections: List<DetectionResult>,
        val marginDetections: List<DetectionResult>,
        val fullImageTextBlocks: List<Text.TextBlock>
    )

    suspend fun process(
        originalBitmap: Bitmap,
        yoloDetections: List<DetectionResult>,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): RegionOcrResult = coroutineScope {
        val interactive = yoloDetections.filter { it.label in interactiveLabels }
        val remaining = yoloDetections.filter { it.label !in interactiveLabels }

        val widgetOcrDeferred = async { runWidgetOcr(originalBitmap, interactive) }
        val marginOcrDeferred = async { runMarginOcr(originalBitmap, leftGuidePct, rightGuidePct) }
        val fullImageOcrDeferred = async { runFullImageOcr(originalBitmap) }

        RegionOcrResult(
            enrichedDetections = widgetOcrDeferred.await(),
            remainingDetections = remaining,
            marginDetections = marginOcrDeferred.await(),
            fullImageTextBlocks = fullImageOcrDeferred.await()
        )
    }

    private suspend fun runWidgetOcr(
        bitmap: Bitmap,
        components: List<DetectionResult>
    ): List<DetectionResult> = coroutineScope {
        components.map { component ->
            async {
                var crop: Bitmap? = null
                var preprocessed: Bitmap? = null
                try {
                    crop = BitmapUtils.cropRegion(bitmap, component.boundingBox, componentPadding)
                    preprocessed = BitmapUtils.preprocessForOcr(crop)
                    val textBlocks = ocrSource.recognizeText(preprocessed).getOrNull()
                    val text = textBlocks?.let { OcrTextAssembler.extractTextWithTolerance(it) } ?: ""
                    component.copy(text = text)
                } finally {
                    preprocessed?.recycle()
                    if (crop != null && crop !== bitmap) crop.recycle()
                }
            }
        }.awaitAll()
    }

    private suspend fun runMarginOcr(
        bitmap: Bitmap,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): List<DetectionResult> {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val results = mutableListOf<DetectionResult>()

        val leftRect = RectF(0f, 0f, width * leftGuidePct, height)
        results.addAll(ocrCroppedRegion(bitmap, leftRect, 0f))

        val rightOffsetX = width * rightGuidePct
        val rightRect = RectF(rightOffsetX, 0f, width, height)
        results.addAll(ocrCroppedRegion(bitmap, rightRect, rightOffsetX))

        return results
    }

    private suspend fun ocrCroppedRegion(
        bitmap: Bitmap,
        rect: RectF,
        offsetX: Float
    ): List<DetectionResult> {
        val crop = BitmapUtils.cropRegion(bitmap, rect)
        if (crop === bitmap) return emptyList()
        return try {
            val textBlocks = ocrSource.recognizeText(crop).getOrNull() ?: emptyList()
            textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { box ->
                        DetectionResult(
                            boundingBox = RectF(
                                box.left + offsetX,
                                box.top + rect.top,
                                box.right + offsetX,
                                box.bottom + rect.top
                            ),
                            label = "text",
                            score = 0.99f,
                            text = OcrTextAssembler.joinElementsWithTolerance(line),
                            isYolo = false
                        )
                    }
                }
            }
        } finally {
            crop.recycle()
        }
    }

    private suspend fun runFullImageOcr(bitmap: Bitmap): List<Text.TextBlock> {
        return ocrSource.recognizeText(bitmap).getOrNull() ?: emptyList()
    }
}
