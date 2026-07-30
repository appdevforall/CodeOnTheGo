package com.itsaky.androidide.ui.compose.common

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders [file] as an image, decoded off the main thread, falling back to [placeholder] while
 * loading, if [file] is null/missing, or if decoding fails. Used for locally-stored icons/thumbnails
 * (plugin icons, template thumbnails) where the file rarely changes, so a plain decode is enough
 * and doesn't warrant an image-loading library dependency.
 */
@Composable
fun FileImage(
	file: File?,
	placeholder: Painter,
	contentDescription: String?,
	modifier: Modifier = Modifier,
) {
	val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
		value =
			file
				?.takeIf { it.exists() }
				?.let { existing ->
					withContext(Dispatchers.IO) {
						runCatching { BitmapFactory.decodeFile(existing.absolutePath)?.asImageBitmap() }.getOrNull()
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
