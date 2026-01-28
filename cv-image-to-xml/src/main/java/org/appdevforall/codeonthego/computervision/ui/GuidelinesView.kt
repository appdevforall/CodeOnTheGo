package org.appdevforall.codeonthego.computervision.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class GuidelinesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        alpha = 180
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        setShadowLayer(5.0f, 0f, 0f, Color.BLACK)
    }

    private var leftGuidelinePct = 0.2f
    private var rightGuidelinePct = 0.8f
    private var draggingLine: Int = -1 // -1: none, 0: left, 1: right
    private val minDistancePct = 0.05f

    private val viewMatrix = Matrix()
    private val imageRect = RectF()

    var onGuidelinesChanged: ((Float, Float) -> Unit)? = null

    fun updateMatrix(matrix: Matrix) {
        viewMatrix.set(matrix)
        invalidate()
    }

    fun setImageDimensions(width: Int, height: Int) {
        imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
        invalidate()
    }

    fun updateGuidelines(leftPct: Float, rightPct: Float) {
        leftGuidelinePct = leftPct
        rightGuidelinePct = rightPct
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.isEmpty) return

        // 1. Define line X coordinates in the image's coordinate system
        val leftLineImageX = imageRect.width() * leftGuidelinePct
        val rightLineImageX = imageRect.width() * rightGuidelinePct

        // We only need the X coordinates for mapping
        val linePoints = floatArrayOf(leftLineImageX, 0f, rightLineImageX, 0f)

        // 2. Map image X coordinates to screen X coordinates
        viewMatrix.mapPoints(linePoints)
        val leftLineScreenX = linePoints[0]
        val rightLineScreenX = linePoints[2]

        // 3. Draw the lines across the full height of the view (screen)
        canvas.drawLine(leftLineScreenX, 0f, leftLineScreenX, height.toFloat(), linePaint)
        canvas.drawLine(rightLineScreenX, 0f, rightLineScreenX, height.toFloat(), linePaint)

        // 4. Draw the labels at the bottom of the screen
        val labelY = height - 60f
        canvas.drawText("Left Margin", leftLineScreenX, labelY, textPaint)
        canvas.drawText("Right Margin", rightLineScreenX, labelY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false

        // Map screen touch coordinates to image coordinates for dragging
        val points = floatArrayOf(event.x, event.y)
        val invertedMatrix = Matrix()
        viewMatrix.invert(invertedMatrix)
        invertedMatrix.mapPoints(points)
        val mappedX = points[0]

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val leftLineImageX = imageRect.width() * leftGuidelinePct
                val rightLineImageX = imageRect.width() * rightGuidelinePct

                val screenPointLeft = mapImageCoordsToScreenCoords(leftLineImageX, imageRect.centerY())
                val screenPointRight = mapImageCoordsToScreenCoords(rightLineImageX, imageRect.centerY())

                if (isCloseTo(event.x, screenPointLeft[0])) {
                    draggingLine = 0
                    return true
                } else if (isCloseTo(event.x, screenPointRight[0])) {
                    draggingLine = 1
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingLine != -1) {
                    val newPct = (mappedX / imageRect.width()).coerceIn(0f, 1f)
                    if (draggingLine == 0) {
                        if (newPct < rightGuidelinePct - minDistancePct) {
                            leftGuidelinePct = newPct
                        }
                    } else { // draggingLine == 1
                        if (newPct > leftGuidelinePct + minDistancePct) {
                            rightGuidelinePct = newPct
                        }
                    }
                    onGuidelinesChanged?.invoke(leftGuidelinePct, rightGuidelinePct)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingLine = -1
            }
        }
        return false
    }

    private fun mapImageCoordsToScreenCoords(imageX: Float, imageY: Float): FloatArray {
        val point = floatArrayOf(imageX, imageY)
        viewMatrix.mapPoints(point)
        return point
    }

    private fun isCloseTo(x: Float, lineX: Float, threshold: Float = 40f): Boolean {
        return Math.abs(x - lineX) < threshold
    }
}
