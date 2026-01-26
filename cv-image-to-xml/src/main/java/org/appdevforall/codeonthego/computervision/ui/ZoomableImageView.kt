package org.appdevforall.codeonthego.computervision.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val currentMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val scaleGestureDetector: ScaleGestureDetector
    private var last = PointF()
    private var start = PointF()
    private var minScale = 1f
    private var maxScale = 4f
    private var mode = NONE

    init {
        super.setClickable(true)
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        scaleType = ScaleType.MATRIX
        imageMatrix = currentMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        val curr = PointF(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                last.set(curr)
                start.set(last)
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> if (mode == DRAG) {
                val deltaX = curr.x - last.x
                val deltaY = curr.y - last.y
                val fixTransX = getFixDragTrans(deltaX, width.toFloat(), origWidth * scale)
                val fixTransY = getFixDragTrans(deltaY, height.toFloat(), origHeight * scale)
                currentMatrix.postTranslate(fixTransX, fixTransY)
                fixTrans()
                last.set(curr.x, curr.y)
            }
            MotionEvent.ACTION_UP -> {
                mode = NONE
                val xDiff = Math.abs(curr.x - start.x).toInt()
                val yDiff = Math.abs(curr.y - start.y).toInt()
                if (xDiff < CLICK && yDiff < CLICK) {
                    performClick()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> mode = NONE
        }
        imageMatrix = currentMatrix
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitToScreen()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToScreen()
    }

    private fun fitToScreen() {
        if (drawable == null || width == 0 || height == 0) {
            return
        }

        val drawableWidth = origWidth
        val drawableHeight = origHeight

        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val drawableRect = RectF(0f, 0f, drawableWidth, drawableHeight)

        currentMatrix.setRectToRect(drawableRect, viewRect, Matrix.ScaleToFit.CENTER)
        imageMatrix = currentMatrix

        currentMatrix.getValues(matrixValues)
        minScale = matrixValues[Matrix.MSCALE_X]
        maxScale = minScale * 4
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = ZOOM
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = scale
            var currentScale = origScale * mScaleFactor
            if (currentScale > maxScale) {
                mScaleFactor = maxScale / origScale
            } else if (currentScale < minScale) {
                mScaleFactor = minScale / origScale
            }
            currentScale = origScale * mScaleFactor
            if (origWidth * currentScale <= width || origHeight * currentScale <= height) {
                currentMatrix.postScale(mScaleFactor, mScaleFactor, width / 2f, height / 2f)
            } else {
                currentMatrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            }
            fixTrans()
            return true
        }
    }

    private fun fixTrans() {
        currentMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val fixTransX = getFixTrans(transX, width.toFloat(), origWidth * scale)
        val fixTransY = getFixTrans(transY, height.toFloat(), origHeight * scale)
        if (fixTransX != 0f || fixTransY != 0f) {
            currentMatrix.postTranslate(fixTransX, fixTransY)
        }
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float
        if (contentSize <= viewSize) {
            minTrans = 0f
            maxTrans = viewSize - contentSize
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }
        if (trans < minTrans) return -trans + minTrans
        return if (trans > maxTrans) -trans + maxTrans else 0f
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            0f
        } else delta
    }

    private val scale: Float
        get() {
            currentMatrix.getValues(matrixValues)
            return matrixValues[Matrix.MSCALE_X]
        }

    private val origWidth: Float
        get() = drawable?.intrinsicWidth?.toFloat() ?: 0f

    private val origHeight: Float
        get() = drawable?.intrinsicHeight?.toFloat() ?: 0f

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val CLICK = 3
    }
}
