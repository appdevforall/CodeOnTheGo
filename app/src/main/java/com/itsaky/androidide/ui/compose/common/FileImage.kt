package com.itsaky.androidide.ui.compose.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders [file] as an image, decoded off the main thread, falling back to [placeholder] while
 * loading, if [file] is null/missing, or if decoding fails. Used for locally-stored icons/thumbnails
 * (plugin icons, template thumbnails) where the file rarely changes, so a plain decode is enough
 * and doesn't warrant an image-loading library dependency. Decoding is bounded to [maxDimension]
 * (via [BitmapFactory.Options.inSampleSize]) so a large source image doesn't allocate a full-size
 * bitmap just to be scaled down to an icon.
 */
@Composable
fun FileImage(
	file: File?,
	placeholder: Painter,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	maxDimension: Dp = 40.dp,
) {
	val maxDimensionPx = with(LocalDensity.current) { maxDimension.roundToPx() }

	val bitmap by produceState<ImageBitmap?>(initialValue = null, file, maxDimensionPx) {
		value =
			file?.let { candidate ->
				withContext(Dispatchers.IO) {
					try {
						if (!candidate.exists()) return@withContext null
						decodeBounded(candidate, maxDimensionPx)?.asImageBitmap()
					} catch (e: CancellationException) {
						throw e
					} catch (e: SecurityException) {
						null
					} catch (e: OutOfMemoryError) {
						null
					}
				}
			}
	}

	val current = bitmap
	if (current != null) {
		Image(
			bitmap = current,
			contentDescription = contentDescription,
			modifier = modifier,
			contentScale = ContentScale.Fit,
		)
	} else {
		Image(
			painter = placeholder,
			contentDescription = contentDescription,
			modifier = modifier,
			contentScale = ContentScale.Fit,
		)
	}
}

/** Decodes [file] downsampled so neither dimension exceeds [maxDimensionPx] by more than 2x. */
private fun decodeBounded(
	file: File,
	maxDimensionPx: Int,
): Bitmap? {
	val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
	BitmapFactory.decodeFile(file.absolutePath, bounds)
	if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

	var inSampleSize = 1
	while (bounds.outWidth / (inSampleSize * 2) >= maxDimensionPx &&
		bounds.outHeight / (inSampleSize * 2) >= maxDimensionPx
	) {
		inSampleSize *= 2
	}

	val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
	return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
}
