package com.itsvks.layouteditor.vectormaster

import android.graphics.Color
import android.graphics.Paint.Cap
import android.graphics.Paint.Join
import android.graphics.Path.FillType

object DefaultValues {
  const val PATH_FILL_COLOR: Int = Color.TRANSPARENT
  const val PATH_FILL_COLOR_BLACK: Int = Color.BLACK
  const val PATH_FILL_COLOR_WHITE: Int = Color.WHITE
  const val PATH_STROKE_COLOR: Int = Color.TRANSPARENT
  const val PATH_STROKE_WIDTH: Float = 0.0f
  const val PATH_STROKE_ALPHA: Float = 1.0f
  const val PATH_FILL_ALPHA: Float = 1.0f
  @JvmField
  val PATH_STROKE_LINE_CAP: Cap = Cap.BUTT
  @JvmField
  val PATH_STROKE_LINE_JOIN: Join = Join.ROUND
  const val PATH_STROKE_MITER_LIMIT: Float = 4.0f
  const val PATH_STROKE_RATIO: Float = 1.0f

  // WINDING fill type is equivalent to NON_ZERO
  @JvmField
  val PATH_FILL_TYPE: FillType = FillType.WINDING
  const val PATH_TRIM_PATH_START: Float = 0.0f
  const val PATH_TRIM_PATH_END: Float = 1.0f
  const val PATH_TRIM_PATH_OFFSET: Float = 0.0f
  const val VECTOR_VIEWPORT_WIDTH: Float = 50.0f
  const val VECTOR_VIEWPORT_HEIGHT: Float = 50.0f
  const val VECTOR_WIDTH: Float = 50.0f
  const val VECTOR_HEIGHT: Float = 50.0f
  const val VECTOR_ALPHA: Float = 1.0f
  const val GROUP_ROTATION: Float = 0.0f
  const val GROUP_PIVOT_X: Float = 0.0f
  const val GROUP_PIVOT_Y: Float = 0.0f
  const val GROUP_SCALE_X: Float = 1.0f
  const val GROUP_SCALE_Y: Float = 1.0f
  const val GROUP_TRANSLATE_X: Float = 0.0f
  const val GROUP_TRANSLATE_Y: Float = 0.0f
  var PATH_ATTRIBUTES: Array<String> = arrayOf(
    "name",
    "fillAlpha",
    "fillColor",
    "fillType",
    "pathData",
    "strokeAlpha",
    "strokeColor",
    "strokeLineCap",
    "strokeLineJoin",
    "strokeMiterLimit",
    "strokeWidth"
  )
}
