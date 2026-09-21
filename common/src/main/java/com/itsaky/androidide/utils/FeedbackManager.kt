package com.itsaky.androidide.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.TransactionTooLargeException
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.eventbus.events.editor.ReportCaughtExceptionEvent
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A reusable feedback manager that provides consistent UI for sending feedback
 * from any screen in the Code On the Go application.
 */
object FeedbackManager {
	private const val EMAIL_SUPPORT = "feedback@appdevforall.org"
	private val logger = LoggerFactory.getLogger(FeedbackManager::class.java)

	/**
	 * Shows the feedback dialog and handles sending feedback email.
	 *
	 * @param activity The context from which feedback is being sent
	 */
	fun showFeedbackDialog(
		activity: AppCompatActivity,
		logContent: String?,
		metricsAttachment: (suspend () -> File?)? = null,
	) {
		val builder = DialogUtils.newMaterialDialogBuilder(activity)

		builder
			.setTitle(R.string.title_alert)
			.setMessage(
				HtmlCompat.fromHtml(
					activity.getString(R.string.email_feedback_warning_prompt),
					HtmlCompat.FROM_HTML_MODE_COMPACT,
				),
			).setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
			.setPositiveButton(android.R.string.ok) { dialog, _ ->
				dialog.dismiss()
				sendFeedbackWithAttachments(activity, logContent, metricsAttachment)
			}.show()
	}

	/**
	 * Shows a simple contact dialog as fallback when email intents fail.
	 * Uses the same title, message, and button text as the existing contact dialog.
	 */
	fun showContactDialog(context: Context) {
		val builder = DialogUtils.newMaterialDialogBuilder(context)

		builder
			.setTitle(R.string.msg_contact_app_dev_title)
			.setMessage(R.string.msg_contact_app_dev_description)
			.setNegativeButton(android.R.string.cancel) { dialog, _ ->
				dialog.dismiss()
			}.setPositiveButton(R.string.send_email) { dialog, _ ->
				val intent =
					Intent(Intent.ACTION_SENDTO).apply {
						data =
							"mailto:$EMAIL_SUPPORT?subject=${context.getString(R.string.feedback_email_subject)}".toUri()
					}
				context.startActivity(intent)
				dialog.dismiss()
			}.create()
			.show()
	}

	fun sendTooltipFeedbackWithScreenshot(
		context: Context,
		customSubject: String,
		metadata: String,
		includeScreenshot: Boolean = true,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>? = null,
	) {
		val message =
			buildString {
				append(metadata)
				append(
					context.getString(
						R.string.feedback_device_info,
						BasicBuildInfo.formatVersion(),
						Build.VERSION.RELEASE,
						"${Build.MANUFACTURER} ${Build.MODEL}",
					),
				)
			}

		if (includeScreenshot) {
			captureScreenshot(context) { screenshotFile ->
				sendFeedbackWithAttachment(
					context,
					customSubject,
					message,
					screenshotFile,
					shareActivityResultLauncher,
				)
			}
		} else {
			sendFeedbackWithAttachment(
				context,
				customSubject,
				message,
				null,
				shareActivityResultLauncher,
			)
		}
	}

	private fun sendFeedbackWithAttachment(
		context: Context,
		subject: String,
		message: String,
		attachmentFile: File?,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>?,
	) {
		runCatching {
			val intent =
				if (attachmentFile != null) {
					Intent(Intent.ACTION_SEND).apply {
						type = "message/rfc822"
						putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
						putExtra(Intent.EXTRA_SUBJECT, subject)
						putExtra(Intent.EXTRA_TEXT, message)

						val uri = context.fileProviderUriFor(attachmentFile)
						putExtra(Intent.EXTRA_STREAM, uri)
						addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

						if (context !is Activity) {
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						}
					}
				} else {
					Intent(Intent.ACTION_SENDTO).apply {
						data = "mailto:".toUri()
						putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
						putExtra(Intent.EXTRA_SUBJECT, subject)
						putExtra(Intent.EXTRA_TEXT, message)

						if (context !is Activity) {
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						}
					}
				}

			launchIntentChooser(
				intent,
				context.getString(R.string.send_feedback),
				context,
				shareActivityResultLauncher,
			)
		}.recoverCatching {
			val fallbackIntent =
				Intent(Intent.ACTION_SENDTO).apply {
					data = "mailto:${EMAIL_SUPPORT}?subject=${Uri.encode(subject)}&body=${Uri.encode(message)}".toUri()
				}
			context.startActivity(fallbackIntent)
		}.onFailure {
			logger.error("Failed to send feedback with attachment", it)
			showContactDialog(context)
		}
	}

	fun captureScreenshot(
		context: Context,
		callback: (File?) -> Unit,
	) {
		val activity = context as? AppCompatActivity
		if (activity == null) {
			logger.warn("Cannot capture screenshot: Context is not an Activity")
			callback(null)
			return
		}

		val rootView = activity.window.decorView.rootView
		val screenshotFile =
			createScreenshotFile(context) ?: run {
				callback(null)
				return
			}
		captureWithPixelCopy(activity, rootView, screenshotFile, callback)
	}

	private fun createScreenshotFile(context: Context): File? =
		runCatching {
			val screenshotDir =
				File(context.cacheDir, "screenshots").apply {
					if (!exists()) mkdirs()
				}
			val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
			File(screenshotDir, "screenshot_$timestamp.png")
		}.onFailure {
			logger.error("Failed to create screenshot file", it)
		}.getOrNull()

	private fun captureWithPixelCopy(
		activity: AppCompatActivity,
		rootView: View,
		screenshotFile: File,
		callback: (File?) -> Unit,
	) {
		var bitmap: Bitmap? = null

		try {
			bitmap = createBitmap(rootView.width, rootView.height)
			val locationOfViewInWindow = IntArray(2)
			rootView.getLocationInWindow(locationOfViewInWindow)

			PixelCopy.request(
				activity.window,
				android.graphics.Rect(
					locationOfViewInWindow[0],
					locationOfViewInWindow[1],
					locationOfViewInWindow[0] + rootView.width,
					locationOfViewInWindow[1] + rootView.height,
				),
				bitmap,
				{ result ->
					if (result == PixelCopy.SUCCESS) {
						activity.lifecycleScope.launch {
							saveScreenshot(bitmap, screenshotFile, callback)
						}
					} else {
						logger.error("PixelCopy failed with result code: $result")
						bitmap.recycle()
						callback(null)
					}
				},
				Handler(Looper.getMainLooper()),
			)
		} catch (e: Exception) {
			logger.error("PixelCopy exception, falling back to Canvas", e)
			bitmap?.recycle()
			callback(null)
		}
	}

	private suspend fun saveScreenshot(
		bitmap: Bitmap,
		file: File,
		callback: (File?) -> Unit,
	) {
		val result =
			withContext(Dispatchers.IO) {
				runCatching {
					FileOutputStream(file).use { out ->
						bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
					}
					file
				}.onFailure {
					logger.error("Failed to save screenshot", it)
				}.getOrNull()
			}
		bitmap.recycle()
		callback(result)
	}

	private fun launchIntentChooser(
		intent: Intent,
		chooserTitle: String,
		context: Context,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>?,
	) {
		val chooser = Intent.createChooser(intent, chooserTitle)
		shareActivityResultLauncher?.launch(chooser) ?: context.startActivity(chooser)
	}

	/**
	 * Attempts to get the current screen name from the context.
	 * Falls back to "Unknown Screen" if detection fails.
	 */
	private fun getCurrentScreenName(context: Context): String =
		when (context) {
			is Activity -> context.javaClass.simpleName.replace("Activity", "")
			else -> "Unknown Screen"
		}

	/**
	 * The URI for the metrics attachment, or `null` if there is nothing to attach or it failed.
	 *
	 * Suspending, unlike the log: the caller reads the sample buffers on the main thread and writes
	 * a compressed file off it, and neither belongs in a click listener. Guarded, because feedback
	 * about a broken IDE has to send even when this part does not work.
	 *
	 * Both steps are inside the guard. [toUri] used to be chained outside it, so a FileProvider not
	 * told about the attachment's directory threw past the guard and killed the whole send --
	 * precisely what the guard is for.
	 *
	 * [CancellationException] is rethrown rather than swallowed. `runCatching` catches `Throwable`,
	 * and [provider] really does suspend, so a destroyed activity had its cancellation eaten here
	 * and the caller ran on to `startActivity()` on a dead activity.
	 *
	 * Separated from [sendFeedbackWithAttachments] so it can be tested: that one needs a live
	 * activity and its lifecycle scope, and this is the part with the failure modes.
	 */
	@VisibleForTesting
	internal suspend fun metricsAttachmentUri(
		provider: (suspend () -> File?)?,
		toUri: (File) -> Uri,
	): Uri? =
		runCatching { provider?.invoke()?.let(toUri) }
			.onFailure { error ->
				if (error is CancellationException) {
					throw error
				}
				logger.error("Could not attach the metrics file", error)
			}.getOrNull()

	private fun sendFeedbackWithAttachments(
		activity: AppCompatActivity,
		logContent: String?,
		metricsAttachment: (suspend () -> File?)? = null,
	) {
		activity.lifecycleScope.launch {
			val handler = FeedbackEmailHandler(activity)

			val screenshotUri = handler.captureAndPrepareScreenshotUri(activity)
			val logContentUri = handler.getLogUri(activity, logContent)
			val metricsUri = metricsAttachmentUri(metricsAttachment, activity::fileProviderUriFor)

			val feedbackRecipient = activity.getString(R.string.feedback_email)
			val feedbackSubject =
				activity.getString(R.string.feedback_subject, getCurrentScreenName(activity))
			val stackTraceSection =
				logContent?.trim().takeIf { it?.isNotEmpty() == true }
					?: activity.getString(R.string.feedback_stack_trace_unavailable)
			val feedbackBody =
				buildString {
					append(
						activity.getString(
							R.string.feedback_device_info,
							BasicBuildInfo.formatVersion(),
							Build.VERSION.RELEASE,
							"${Build.MANUFACTURER} ${Build.MODEL}",
						),
					)
					append(
						activity.getString(
							R.string.feedback_message,
							stackTraceSection,
						),
					)
				}

			val emailIntent =
				handler.prepareEmailIntent(
					screenshotUri,
					logContentUri,
					feedbackRecipient,
					feedbackSubject,
					feedbackBody,
					metricsUri,
				)

			runCatching {
				activity.startActivity(emailIntent)
			}.onFailure { e ->
				when {
					e is ActivityNotFoundException -> {
						Toast.makeText(activity, R.string.no_email_apps, Toast.LENGTH_LONG).show()
					}

					e is TransactionTooLargeException ||
						(e is RuntimeException && e.cause is TransactionTooLargeException) -> {
						logger.error("Intent transaction failed: Data too large", e)
						Toast.makeText(activity, R.string.msg_feedback_log_too_long, Toast.LENGTH_LONG).show()
					}

					else -> {
						logger.error("Intent transaction failed: Unknown error", e)
						EventBus.getDefault().post(
							ReportCaughtExceptionEvent(
								throwable = e,
								message = "Feedback email intent failed",
								extras = mapOf("screen" to getCurrentScreenName(activity)),
							),
						)
						Toast.makeText(activity, R.string.unknown_error, Toast.LENGTH_LONG).show()
					}
				}
			}
		}
	}
}
