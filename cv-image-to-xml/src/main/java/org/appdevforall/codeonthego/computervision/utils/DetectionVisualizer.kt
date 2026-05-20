package org.appdevforall.codeonthego.computervision.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.toColorInt
import org.appdevforall.codeonthego.computervision.R
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult


/**
 * Utility class responsible for visualizing computer vision detection results.
 * It handles drawing bounding boxes, text labels, and interactive visual hints
 * directly onto image bitmaps.
 *
 * @property context The context used to retrieve resources such as drawables.
 */
class DetectionVisualizer(private val context: Context) {

    private val boundingBoxPaint by lazy {
        Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 5.0f
            alpha = 200
        }
    }

    private val imagePlaceholderPaint by lazy {
        Paint().apply {
            color = "#FF8A00".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 7.0f
            alpha = 230
        }
    }

    private val imagePlaceholderFillPaint by lazy {
        Paint().apply {
            color = "#FF8A00".toColorInt()
            style = Paint.Style.FILL
            alpha = 40
        }
    }

    private val textRecognitionBoxPaint by lazy {
        Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 3.0f
            alpha = 200
        }
    }

    private val textPaint by lazy {
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 40.0f
            setShadowLayer(5.0f, 0f, 0f, Color.BLACK)
        }
    }

    private val imagePlaceholderUploadDrawable: Drawable? by lazy {
        AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_upload)?.mutate()?.apply {
            setTint(Color.WHITE)
        }
    }

    private val imagePlaceholderDeleteDrawable: Drawable? by lazy {
        AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_delete)?.mutate()?.apply {
            setTint(Color.WHITE)
        }
    }

    private val imagePlaceholderBadgePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#CC111111".toColorInt()
            style = Paint.Style.FILL
        }
    }

    private val deleteBadgeBackgroundPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#CCB3261E".toColorInt()
            style = Paint.Style.FILL
        }
    }

    private val deleteIconClickableAreas = mutableMapOf<String, RectF>()

    /**
     * Draws detection bounding boxes, labels, and interactive placeholder hints on a given bitmap.
     *
     * @param bitmap The original image on which detections were performed.
     * @param detections A list of [DetectionResult] objects containing the bounding boxes and labels.
     * @param selectedPlaceholderIds A set of IDs representing image placeholders that have been selected/filled.
     * @return A new [Bitmap] instance with the visualized detections drawn over it.
     */
    fun visualize(
        bitmap: Bitmap,
        detections: List<DetectionResult>,
        selectedPlaceholderIds: Set<String>
    ): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        deleteIconClickableAreas.clear()

        val placeholderIdsByDetection = mapPlaceholderDetections(detections)

        for (result in detections) {
            if (result.label == IMAGE_PLACEHOLDER_LABEL) {
                val placeholderId = placeholderIdsByDetection[result] ?: continue
                val hasSelectedImage = selectedPlaceholderIds.contains(placeholderId)

                drawImagePlaceholderHint(canvas, result.boundingBox, hasSelectedImage, placeholderId)
            } else {
                drawStandardDetection(canvas, result)
            }
        }

        return mutableBitmap
    }

    /**
     * Checks if the given X, Y coordinates intersect with any drawn delete icon.
     * @return The placeholderId if a delete icon was tapped, null otherwise.
     */
    fun getTappedDeleteIconId(x: Float, y: Float): String? {
        return deleteIconClickableAreas.entries.firstOrNull { it.value.contains(x, y) }?.key
    }

    /**
     * Filters and maps image placeholder detections to their corresponding auto-generated IDs.
     * Placeholders are sorted from top to bottom, left to right to ensure consistent ID assignment.
     *
     * @param detections The full list of detections.
     * @return A map linking each placeholder [DetectionResult] to its generated string ID (e.g., "ph_0").
     */
    private fun mapPlaceholderDetections(detections: List<DetectionResult>): Map<DetectionResult, String> {
        return detections
            .filter { it.label == IMAGE_PLACEHOLDER_LABEL }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
            .mapIndexed { index, detection ->
                detection to "ph_$index"
            }.toMap()
    }

    /**
     * Draws a standard detection bounding box and its corresponding text label.
     * It uses different colors depending on whether the detection is from YOLO or Text Recognition.
     *
     * @param canvas The canvas to draw the detection on.
     * @param result The [DetectionResult] containing the coordinates, label, and detection type.
     */
    private fun drawStandardDetection(canvas: Canvas, result: DetectionResult) {
        val paint = if (result.isYolo) boundingBoxPaint else textRecognitionBoxPaint
        canvas.drawRect(result.boundingBox, paint)

        val label = result.label.take(15)
        val text = if (result.text.isNotEmpty()) "$label: ${result.text}" else label
        canvas.drawText(text, result.boundingBox.left, result.boundingBox.top - 5, textPaint)
    }

    /**
     * Draws a highlighted region and a central badge for an image placeholder detection.
     *
     * @param canvas The canvas to draw the hint on.
     * @param boundingBox The rectangular bounds of the detected image placeholder.
     * @param hasSelectedImage A flag indicating whether the user has already selected an image for this placeholder.
     */
    private fun drawImagePlaceholderHint(
        canvas: Canvas,
        boundingBox: RectF,
        hasSelectedImage: Boolean,
        placeholderId: String
    ) {
        canvas.drawRect(boundingBox, imagePlaceholderFillPaint)
        canvas.drawRect(boundingBox, imagePlaceholderPaint)

        val badgeHeight = (boundingBox.height() * 0.24f).coerceIn(44f, 72f)
        val badgeTop = (boundingBox.centerY() - badgeHeight / 2f).coerceAtLeast(boundingBox.top + 8f)
        val badgeBottom = (badgeTop + badgeHeight).coerceAtMost(boundingBox.bottom - 8f)

        val badgeRect = RectF(
            boundingBox.left + 12f,
            badgeTop,
            boundingBox.right - 12f,
            badgeBottom
        )

        val bgPaint = if (hasSelectedImage) deleteBadgeBackgroundPaint else imagePlaceholderBadgePaint
        canvas.drawRoundRect(badgeRect, 16f, 16f, bgPaint)

        drawPlaceholderIcon(canvas, badgeRect, hasSelectedImage)

        if (hasSelectedImage) {
            deleteIconClickableAreas[placeholderId] = badgeRect
        }
    }

    private fun drawPlaceholderIcon(canvas: Canvas, badgeRect: RectF, hasSelectedImage: Boolean) {
        val iconDrawable = if (hasSelectedImage) imagePlaceholderDeleteDrawable else imagePlaceholderUploadDrawable
        if (iconDrawable == null) return

        val iconSize = minOf(badgeRect.width(), badgeRect.height()) * 0.80f

        val left = (badgeRect.centerX() - iconSize / 2f).toInt()
        val top = (badgeRect.centerY() - iconSize / 2f).toInt()
        val right = (badgeRect.centerX() + iconSize / 2f).toInt()
        val bottom = (badgeRect.centerY() + iconSize / 2f).toInt()

        iconDrawable.alpha = 255
        iconDrawable.setBounds(left, top, right, bottom)
        iconDrawable.draw(canvas)
    }

    fun clearCache() {
        deleteIconClickableAreas.clear()
    }
}
