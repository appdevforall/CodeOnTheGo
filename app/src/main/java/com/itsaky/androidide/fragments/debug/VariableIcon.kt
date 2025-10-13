package com.itsaky.androidide.fragments.debug

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.withTranslation

/**
 * @author Akash Yadav
 */
class CircleCharDrawable(
    private val char: Char,
    private val circle: Boolean
) :
    Drawable() {
    private val mPaint = Paint().apply {
        isAntiAlias = true
        color = Color.TRANSPARENT
    }

    private val mTextPaint = Paint().apply {
        color = -0x1
        isAntiAlias = true
        textSize = Resources.getSystem()
            .displayMetrics.density * 14
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.right.toFloat()
        val height = bounds.bottom.toFloat()
        if (circle) {
            canvas.drawCircle(width / 2, height / 2, width / 2, mPaint)
        } else {
            canvas.drawRect(0f, 0f, width, height, mPaint)
        }
        canvas.withTranslation(width / 2f, height / 2f) {
            val textCenter = -(mTextPaint.descent() + mTextPaint.ascent()) / 2f
            drawText(char.toString(), 0f, textCenter, mTextPaint)
        }
    }

    override fun setAlpha(p1: Int) {
        mPaint.alpha = p1
        mTextPaint.alpha = p1
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        mTextPaint.colorFilter = colorFilter
    }

    @Deprecated(
        "Deprecated in Java",
        ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat")
    )
    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }
}