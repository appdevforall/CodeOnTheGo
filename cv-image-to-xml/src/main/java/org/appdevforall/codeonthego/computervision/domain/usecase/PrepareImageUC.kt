package org.appdevforall.codeonthego.computervision.domain.usecase

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.computervision.utils.SmartBoundaryDetector
import java.io.IOException

/**
 * Use case responsible for decoding an image URI, correcting its EXIF rotation,
 * and estimating the initial left and right canvas boundaries.
 */
class PrepareImageUC(private val contentResolver: ContentResolver) {
    data class PreparedImage(val bitmap: Bitmap, val leftPct: Float, val rightPct: Float)

    suspend operator fun invoke(uri: Uri): Result<PreparedImage> = withContext(Dispatchers.Default) {
        runCatching {
            val bitmap = uriToBitmap(uri) ?: throw IllegalStateException("Failed to decode image from URI")
            val rotatedBitmap = handleImageRotation(uri, bitmap)
            val (leftBoundPx, rightBoundPx) = SmartBoundaryDetector.detectSmartBoundaries(rotatedBitmap)

            val widthFloat = rotatedBitmap.width.toFloat()
            PreparedImage(
                bitmap = rotatedBitmap,
                leftPct = leftBoundPx / widthFloat,
                rightPct = rightBoundPx / widthFloat
            )
        }.onFailure {
            if (it is CancellationException) throw it
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return contentResolver.openFileDescriptor(uri, "r")?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
        }
    }

    private fun handleImageRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                else -> return bitmap
            }
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }
}
