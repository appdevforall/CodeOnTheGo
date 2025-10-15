package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.itsaky.androidide.common.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FeedbackEmailHandler(
	val context: Context,
) {
	companion object {
        const val SCREENSHOTS_DIR = "feedback_screenshots"
		private val log = LoggerFactory.getLogger(FeedbackEmailHandler::class.java)
	}

	suspend fun captureAndPrepareScreenshotUri(
		activity: Activity,
	): Pair<Uri?, String?>? {
		val rootView = activity.window?.decorView?.rootView ?: return null
		if (rootView.width <= 0 || rootView.height <= 0 || !rootView.isShown) return null

		val screenshotBitmap = createBitmap(rootView.width, rootView.height)
        val screenshotName = "feedback_screenshot"

		return try {
			val saveResultUri =
				withContext(Dispatchers.IO) {
					val bitmapResult =
						suspendCoroutine { continuation ->
							PixelCopy.request(activity.window, screenshotBitmap, { copyResult ->
								if (copyResult == PixelCopy.SUCCESS) {
									continuation.resume(screenshotBitmap)
								} else {
									continuation.resume(null)
								}
							}, Handler(Looper.getMainLooper()))
						}

					if (bitmapResult == null) {
						log.error(context.getString(R.string.pixel_copy_failed))
						return@withContext null
					}

					saveBitmapToFileWithProvider(context, bitmapResult, screenshotName)
				}
			saveResultUri
		} catch (e: Exception) {
			log.error(context.getString(R.string.failed_to_capture_or_save_screenshot), e)
			null
		}
	}

	private fun saveBitmapToFileWithProvider(
		context: Context,
		bitmap: Bitmap,
		name: String,
	): Pair<Uri?, String?>? =
		try {
			val screenshotsDir = File(context.filesDir, SCREENSHOTS_DIR).apply { mkdirs() }
			val timestamp =
				SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
			val filename = "${timestamp}_$name.jpg"
			val screenshotFile = File(screenshotsDir, filename)

			FileOutputStream(screenshotFile).use { out ->
				bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
			}

			val authority =
				"${context.packageName}.providers.fileprovider"
			val uri = FileProvider.getUriForFile(context, authority, screenshotFile)
			val mimeType =
				URLConnection.guessContentTypeFromName(screenshotFile.name) ?: "image/jpeg"
			Pair(uri, mimeType)
		} catch (e: Exception) {
			log.error(context.getString(R.string.failed_to_save_bitmap_to_file), e)
			null
		}

	fun prepareEmailIntent(
		attachmentUri: Uri?,
		attachmentMimeType: String?,
		emailRecipient: String,
		subject: String,
		body: String,
	): Intent {
		val intent =
			Intent(Intent.ACTION_SEND).apply {
				putExtra(Intent.EXTRA_EMAIL, arrayOf(emailRecipient))
				putExtra(Intent.EXTRA_SUBJECT, subject)
				putExtra(Intent.EXTRA_TEXT, body)

				if (attachmentUri != null && attachmentMimeType != null) {
					this.type = attachmentMimeType
					putExtra(Intent.EXTRA_STREAM, attachmentUri)
					addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				} else {
					this.type = "text/plain"
				}
			}
		return intent
	}
}
