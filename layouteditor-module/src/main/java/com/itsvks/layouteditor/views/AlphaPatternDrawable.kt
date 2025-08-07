package com.itsvks.layouteditor.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * This drawable will draw a simple white and gray chessboard pattern. It's the pattern you will
 * often see as a background behind a partly transparent image in many applications.
 */
class AlphaPatternDrawable(private val rectangleSize: Int) : Drawable() {
  private val paint = Paint()
  private val paintWhite = Paint()
  private val paintGray = Paint()

  private var numRectanglesHorizontal = 0
  private var numRectanglesVertical = 0

  /**
   * Bitmap in which the pattern will be cached. This is so the pattern will not have to be
   * recreated each time draw() gets called. Because recreating the pattern i rather expensive. I
   * will only be recreated if the size changes.
   */
  private var bitmap: Bitmap? = null

  init {
    paintWhite.color = -0xbdbdbe
    paintGray.color = -0xdededf
  }

  override fun draw(canvas: Canvas) {
    if (bitmap != null && !bitmap!!.isRecycled) {
      canvas.drawBitmap(bitmap!!, null, bounds, paint)
    }
  }

  override fun setAlpha(alpha: Int) {
    throw UnsupportedOperationException("Alpha is not supported by this drawable.")
  }

  override fun setColorFilter(cf: ColorFilter?) {
    throw UnsupportedOperationException("ColorFilter is not supported by this drawable.")
  }

  override fun getOpacity(): Int {
    return PixelFormat.UNKNOWN
  }

  override fun onBoundsChange(bounds: Rect) {
    super.onBoundsChange(bounds)
    val height = bounds.height()
    val width = bounds.width()
    numRectanglesHorizontal = (width / rectangleSize).toDouble().toInt()
    numRectanglesVertical = (height / rectangleSize).toDouble().toInt()
    generatePatternBitmap()
  }

  /**
   * This will generate a bitmap with the pattern as big as the rectangle we were allow to draw on.
   * We do this to chache the bitmap so we don't need to recreate it each time draw() is called
   * since it takes a few milliseconds
   */
  private fun generatePatternBitmap() {
    if (bounds.width() <= 0 || bounds.height() <= 0) {
      return
    }

    bitmap =
      Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap!!)

    val r = Rect()
    var verticalStartWhite = true
    for (i in 0..numRectanglesVertical) {
      var isWhite = verticalStartWhite
      for (j in 0..numRectanglesHorizontal) {
        r.top = i * rectangleSize
        r.left = j * rectangleSize
        r.bottom = r.top + rectangleSize
        r.right = r.left + rectangleSize
        canvas.drawRect(r, if (isWhite) paintWhite else paintGray)
        isWhite = !isWhite
      }
      verticalStartWhite = !verticalStartWhite
    }
  }
}
