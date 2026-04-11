package org.appdevforall.codeonthego.computervision.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

object BitmapUtils {

    private const val EDGE_DETECTION_THRESHOLD = 30

    fun preprocessForOcr(bitmap: Bitmap, blockSize: Int = 31, c: Int = 15): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = toGrayscale(pixels)
        normalize(gray)
        val blurred = gaussianBlur(gray, width, height, blockSize)
        val binary = adaptiveThreshold(gray, blurred, width, height, c)
        medianFilter(binary, width, height)

        val outputPixels = IntArray(width * height)
        for (i in binary.indices) {
            outputPixels[i] = if (binary[i] > 0) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun cropRegion(bitmap: Bitmap, rect: RectF, padding: Int = 0): Bitmap {
        val left = maxOf(0, (rect.left - padding).toInt())
        val top = maxOf(0, (rect.top - padding).toInt())
        val right = minOf(bitmap.width, (rect.right + padding).toInt())
        val bottom = minOf(bitmap.height, (rect.bottom + padding).toInt())
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    fun calculateVerticalProjection(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val projection = FloatArray(width)
        if (width < 3 || height == 0) {
            return projection
        }

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val leftPixel = pixels[rowOffset + x - 1]
                val rightPixel = pixels[rowOffset + x + 1]

                val rLeft = (leftPixel shr 16) and 0xFF
                val rRight = (rightPixel shr 16) and 0xFF

                val diff = abs(rLeft - rRight)
                if (diff > EDGE_DETECTION_THRESHOLD) {
                    projection[x] += 1f
                }
            }
        }
        return projection
    }

    private fun toGrayscale(pixels: IntArray): IntArray {
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
        return gray
    }

    private fun normalize(gray: IntArray) {
        var min = 255
        var max = 0
        for (v in gray) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = max - min
        if (range == 0) return
        for (i in gray.indices) {
            gray[i] = ((gray[i] - min) * 255) / range
        }
    }

    private fun gaussianBlur(gray: IntArray, width: Int, height: Int, blockSize: Int): IntArray {
        val sigma = 0.3 * ((blockSize - 1) * 0.5 - 1) + 0.8
        val halfKernel = blockSize / 2
        val kernel = FloatArray(blockSize)
        var kernelSum = 0.0f
        for (i in 0 until blockSize) {
            val x = i - halfKernel
            kernel[i] = exp(-(x * x) / (2.0 * sigma * sigma)).toFloat()
            kernelSum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= kernelSum

        val temp = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0f
                for (k in 0 until blockSize) {
                    val sx = (x + k - halfKernel).coerceIn(0, width - 1)
                    sum += gray[y * width + sx] * kernel[k]
                }
                temp[y * width + x] = sum.roundToInt()
            }
        }

        val blurred = IntArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0.0f
                for (k in 0 until blockSize) {
                    val sy = (y + k - halfKernel).coerceIn(0, height - 1)
                    sum += temp[sy * width + x] * kernel[k]
                }
                blurred[y * width + x] = sum.roundToInt()
            }
        }
        return blurred
    }

    private fun adaptiveThreshold(
        gray: IntArray,
        blurred: IntArray,
        width: Int,
        height: Int,
        c: Int
    ): IntArray {
        val binary = IntArray(width * height)
        for (i in gray.indices) {
            binary[i] = if (gray[i] > blurred[i] - c) 255 else 0
        }
        return binary
    }

    private fun medianFilter(pixels: IntArray, width: Int, height: Int) {
        val copy = pixels.copyOf()
        val window = IntArray(9)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var idx = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        window[idx++] = copy[(y + dy) * width + (x + dx)]
                    }
                }
                window.sort()
                pixels[y * width + x] = window[4]
            }
        }
    }
}
