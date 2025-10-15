package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.launch
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
     * @param activity The context from which feedback is being sent
     */
    fun showFeedbackDialog(activity: AppCompatActivity) {
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
                sendFeedbackWithScreenshot(activity)
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
        shareActivityResultLauncher: ActivityResultLauncher<Intent>? = null
    ) {
		val message = buildString {
			append(metadata)
            append(
                context.getString(
                    R.string.feedback_device_info,
                    BuildInfo.VERSION_NAME_SIMPLE,
                    Build.VERSION.RELEASE,
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                )
            )
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

    private fun sendFeedbackWithScreenshot(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val handler = FeedbackEmailHandler(activity)

            val screenshotData = handler.captureAndPrepareScreenshotUri(activity)

            val feedbackRecipient = activity.getString(R.string.feedback_email)
            val feedbackSubject =
                activity.getString(R.string.feedback_subject, getCurrentScreenName(activity))
            val feedbackBody =
                activity.getString(
                    R.string.feedback_message,
                    BuildInfo.VERSION_NAME_SIMPLE,
                    Build.VERSION.RELEASE,
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                )

            val emailIntent =
                handler.prepareEmailIntent(
                    screenshotData?.first,
                    screenshotData?.second,
                    feedbackRecipient,
                    feedbackSubject,
                    feedbackBody,
                )

            if (emailIntent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(
                    Intent.createChooser(emailIntent, activity.getString(R.string.send_feedback)),
                )
            } else {
                Toast
                    .makeText(activity,
                        activity.getString(R.string.no_email_apps), Toast.LENGTH_LONG)
                    .show()
            }
        }
    }
}
