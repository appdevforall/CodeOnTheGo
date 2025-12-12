package appdevforall.codeonthego.computervision.utils

import android.graphics.Bitmap
import android.graphics.Color

object BitmapUtils {

    fun preprocessForOcr(bitmap: Bitmap, windowSize: Int = 16, threshold: Float = 0.15f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            grayPixels[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        val integralImage = LongArray(width * height)
        for (y in 0 until height) {
            var rowSum: Long = 0
            for (x in 0 until width) {
                rowSum += grayPixels[y * width + x]
                integralImage[y * width + x] = if (y == 0) rowSum else integralImage[(y - 1) * width + x] + rowSum
            }
        }

        val thresholdPixels = IntArray(width * height)
        val s = width / windowSize

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val x1 = maxOf(0, x - s / 2)
                val x2 = minOf(width - 1, x + s / 2)
                val y1 = maxOf(0, y - s / 2)
                val y2 = minOf(height - 1, y + s / 2)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integralImage[y2 * width + x2] -
                        (if (x1 > 0) integralImage[y2 * width + x1 - 1] else 0) -
                        (if (y1 > 0) integralImage[(y1 - 1) * width + x2] else 0) +
                        (if (x1 > 0 && y1 > 0) integralImage[(y1 - 1) * width + x1 - 1] else 0)

                thresholdPixels[index] = if (grayPixels[index] * count < sum * (1.0f - threshold)) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            }
        }

        return Bitmap.createBitmap(thresholdPixels, width, height, Bitmap.Config.ARGB_8888)
    }
}