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
        const val AUTHORITY_SUFFIX = "providers.fileprovider"
        const val SCREENSHOTS_DIR = "feedback_screenshots"
        const val LOGS_DIR = "feedback_logs"
		private val log = LoggerFactory.getLogger(FeedbackEmailHandler::class.java)
	}

	suspend fun captureAndPrepareScreenshotUri(
		activity: Activity,
	): Uri? {
		val rootView = activity.window?.decorView?.rootView ?: return null
		if (rootView.width <= 0 || rootView.height <= 0 || !rootView.isShown) return null

		val screenshotBitmap = createBitmap(rootView.width, rootView.height)

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

					saveBitmapToFileWithProvider(context, bitmapResult)
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
	): Uri? =
		try {
			val screenshotsDir = File(context.filesDir, SCREENSHOTS_DIR).apply { mkdirs() }
			val timestamp =
				SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
			val filename = "Screenshot ${timestamp}.jpg"
			val screenshotFile = File(screenshotsDir, filename)

			FileOutputStream(screenshotFile).use { out ->
				bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
			}

			val authority =
				"${context.packageName}.providers.fileprovider"
			val uri = FileProvider.getUriForFile(context, authority, screenshotFile)
			uri
		} catch (e: Exception) {
			log.error(context.getString(R.string.failed_to_save_bitmap_to_file), e)
			null
		}

    fun getLogUri(
        context: Context,
        logContent: String?,
    ): Uri? =
        if (!logContent.isNullOrEmpty()) {
            try {
                val logsDir = File(context.filesDir, LOGS_DIR).apply { mkdirs() }
                val timestamp =
                    SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val filename = "Feedback Log ${timestamp}.txt"
                val logFile = File(logsDir, filename)
                logFile.writeText(logContent)

                val authority = "${context.packageName}.$AUTHORITY_SUFFIX"
                val uri = FileProvider.getUriForFile(context, authority, logFile)
                uri
            } catch (e: Exception) {
                log.error(context.getString(R.string.msg_file_creation_failed), e)
                null
            }
        } else {
            null
        }

    fun prepareEmailIntent(
        screenshotUri: Uri?,
        logContentUri: Uri?,
        emailRecipient: String,
        subject: String,
        body: String,
    ): Intent {
        val attachmentUris = mutableListOf<Uri>()
        screenshotUri?.let { attachmentUris.add(it) }
        logContentUri?.let { attachmentUris.add(it) }

        val hasMultipleAttachments = logContentUri != null
        val intent = if (hasMultipleAttachments) {
            getIntentForMultipleAttachments(
                emailRecipient = emailRecipient,
                subject = subject,
                body = body,
                attachmentUris = attachmentUris
            )
        } else {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(emailRecipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)

                if (screenshotUri != null) {
                    this.type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, screenshotUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    this.type = "text/plain"
                }
            }
        }
        return intent
    }

    fun getIntentForMultipleAttachments(
        emailRecipient: String,
        subject: String,
        body: String,
        attachmentUris: MutableList<Uri>
    ): Intent {

        val emailIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailRecipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachmentUris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return emailIntent
    }
}
