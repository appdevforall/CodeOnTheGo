package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A reusable feedback manager that provides consistent UI for sending feedback
 * from any screen in the CoGo application.
 */
object FeedbackManager {
	private const val EMAIL_SUPPORT = "feedback@appdevforall.org"
	private val logger = LoggerFactory.getLogger(FeedbackManager::class.java)

	/**
	 * Shows the feedback dialog and handles sending feedback email.
	 *
	 * @param context The context from which feedback is being sent
	 * @param currentScreen The name of the current screen (optional, will be detected if null)
	 * @param shareActivityResultLauncher The activity result launcher for sharing (optional)
	 * @param customSubject Custom subject line (optional, uses default if null)
	 * @param throwable Optional throwable to include stack trace from (default: null)
	 * @param appVersion The app version to include in feedback (optional, uses "Unknown" if null)
	 */
	fun showFeedbackDialog(
		context: Context,
		currentScreen: String? = null,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>? = null,
		customSubject: String? = null,
		throwable: Throwable? = null,
		appVersion: String? = null,
	) {
		val builder = DialogUtils.newMaterialDialogBuilder(context)

		builder
			.setTitle(R.string.title_alert)
			.setMessage(
				HtmlCompat.fromHtml(
					context.getString(R.string.email_feedback_warning_prompt),
					HtmlCompat.FROM_HTML_MODE_COMPACT,
				),
			).setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
			.setPositiveButton(android.R.string.ok) { dialog, _ ->
				dialog.dismiss()
				sendFeedback(
					context,
					currentScreen,
					shareActivityResultLauncher,
					customSubject,
					throwable,
					appVersion,
				)
			}.show()
	}

	/**
	 * Directly sends feedback without showing confirmation dialog.
	 *
	 * @param context The context from which feedback is being sent
	 * @param currentScreen The name of the current screen (optional, will be detected if null)
	 * @param shareActivityResultLauncher The activity result launcher for sharing (optional)
	 * @param customSubject Custom subject line (optional, uses default if null)
	 * @param throwable Optional throwable to include stack trace from (default: null)
	 * @param appVersion The app version to include in feedback (optional, uses "Unknown" if null)
	 */
	fun sendFeedback(
		context: Context,
		currentScreen: String? = null,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>? = null,
		customSubject: String? = null,
		throwable: Throwable? = null,
		appVersion: String? = null,
	) {
		val screenName = currentScreen ?: getCurrentScreenName(context)
		val stackTrace = throwable?.stackTraceToString() ?: ""

		val feedbackMessage =
			context.getString(
				R.string.feedback_message,
				appVersion ?: "Unknown",
				stackTrace,
			)

		val subject = customSubject ?: context.getString(R.string.feedback_subject, screenName)

		runCatching {
			val feedbackIntent =
				Intent(Intent.ACTION_SENDTO).apply {
					data = "mailto:".toUri()
					putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
					putExtra(Intent.EXTRA_SUBJECT, subject)
					putExtra(Intent.EXTRA_TEXT, feedbackMessage)
				}

			launchIntentChooser(feedbackIntent, "Send Feedback", context, shareActivityResultLauncher)
		}.recoverCatching {
			// Fallback to general send intent
			val fallbackIntent =
				Intent(Intent.ACTION_SEND).apply {
					type = "message/rfc822"
					putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
					putExtra(Intent.EXTRA_SUBJECT, subject)
					putExtra(Intent.EXTRA_TEXT, feedbackMessage)
				}

			launchIntentChooser(
				fallbackIntent,
				context.getString(R.string.send_feedback),
				context,
				shareActivityResultLauncher
			)
		}.recoverCatching {
			// If all else fails, show simple contact dialog
			showContactDialog(context)
		}.onFailure { error ->
			logger.error("FATAL: Failed to send feedback", error)
		}
	}

	/**
	 * Quick feedback method that can be called with minimal parameters.
	 * Uses sensible defaults and shows the confirmation dialog.
	 *
	 * @param context The context from which feedback is being sent
	 * @param screenName Optional screen name override
	 * @param appVersion Optional app version override
	 */
	fun quickFeedback(
		context: Context,
		screenName: String? = null,
		appVersion: String? = null,
	) {
		showFeedbackDialog(context, screenName, appVersion = appVersion)
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

	fun sendFeedbackWithScreenshot(
		context: Context,
		customSubject: String,
		metadata: String,
		includeScreenshot: Boolean = true,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>? = null,
		appVersion: String? = null
	) {
		val message = buildString {
			append(metadata)
			append("\n\nApp Version: ${appVersion ?: "Unknown"}")
		}

		if (includeScreenshot) {
			captureScreenshot(context) { screenshotFile ->
				sendFeedbackWithAttachment(
					context,
					customSubject,
					message,
					screenshotFile,
					shareActivityResultLauncher
				)
			}
		} else {
			sendFeedbackWithAttachment(
				context,
				customSubject,
				message,
				null,
				shareActivityResultLauncher
			)
		}
	}

	private fun sendFeedbackWithAttachment(
		context: Context,
		subject: String,
		message: String,
		attachmentFile: File?,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>?
	) {
		runCatching {
			val intent = if (attachmentFile != null) {
				Intent(Intent.ACTION_SEND).apply {
					type = "message/rfc822"
					putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
					putExtra(Intent.EXTRA_SUBJECT, subject)
					putExtra(Intent.EXTRA_TEXT, message)

					val uri = FileProvider.getUriForFile(
						context,
						"${context.packageName}.providers.fileprovider",
						attachmentFile
					)
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
				shareActivityResultLauncher
			)
		}.recoverCatching {
			val fallbackIntent = Intent(Intent.ACTION_SENDTO).apply {
				data = "mailto:${EMAIL_SUPPORT}?subject=${Uri.encode(subject)}&body=${Uri.encode(message)}".toUri()
			}
			context.startActivity(fallbackIntent)
		}.onFailure {
			logger.error("Failed to send feedback with attachment", it)
			showContactDialog(context)
		}
	}


	fun captureScreenshot(context: Context, callback: (File?) -> Unit) {
		val activity = context as? Activity
		if (activity == null) {
			logger.warn("Cannot capture screenshot: Context is not an Activity")
			callback(null)
			return
		}

		val rootView = activity.window.decorView.rootView
		val screenshotFile = createScreenshotFile(context) ?: run {
			callback(null)
			return
		}
        captureWithPixelCopy(activity, rootView, screenshotFile, callback)
    }


	private fun createScreenshotFile(context: Context): File? {
		return runCatching {
			val screenshotDir = File(context.cacheDir, "screenshots").apply {
				if (!exists()) mkdirs()
			}
			val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
			File(screenshotDir, "screenshot_$timestamp.png")
		}.onFailure {
			logger.error("Failed to create screenshot file", it)
		}.getOrNull()
	}

	private fun captureWithPixelCopy(
		activity: Activity,
		rootView: View,
		screenshotFile: File,
		callback: (File?) -> Unit
	) {

        runCatching {
			val bitmap = createBitmap(rootView.width, rootView.height)
			val locationOfViewInWindow = IntArray(2)
			rootView.getLocationInWindow(locationOfViewInWindow)

			PixelCopy.request(
				activity.window,
				android.graphics.Rect(
					locationOfViewInWindow[0],
					locationOfViewInWindow[1],
					locationOfViewInWindow[0] + rootView.width,
					locationOfViewInWindow[1] + rootView.height
				),
				bitmap,
				{ result ->
					if (result == PixelCopy.SUCCESS) {
						saveScreenshot(bitmap, screenshotFile, callback)
					}
				},
				Handler(Looper.getMainLooper())
			)
		}.onFailure {
			logger.error("PixelCopy exception, falling back to Canvas", it)
		}
	}


	private fun saveScreenshot(bitmap: Bitmap, file: File, callback: (File?) -> Unit) {
		runCatching {
			FileOutputStream(file).use { out ->
				bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
			}
			bitmap.recycle()
			callback(file)
		}.onFailure {
			logger.error("Failed to save screenshot", it)
			callback(null)
		}
	}


	private fun launchIntentChooser(
		intent: Intent,
		chooserTitle: String,
		context: Context,
		shareActivityResultLauncher: ActivityResultLauncher<Intent>?
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
}
