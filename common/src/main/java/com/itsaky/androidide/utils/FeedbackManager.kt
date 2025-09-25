package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

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
	 */
	fun showFeedbackDialog(
		context: Context,
        activity: Activity,
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
                sendFeedbackWithScreenshot(
                    context,
                    activity
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

			if (shareActivityResultLauncher != null) {
				shareActivityResultLauncher.launch(
					Intent.createChooser(feedbackIntent, "Send Feedback"),
				)
			} else {
				context.startActivity(
					Intent.createChooser(feedbackIntent, "Send Feedback"),
				)
			}
		}.recoverCatching {
			// Fallback to general send intent
			val fallbackIntent =
				Intent(Intent.ACTION_SEND).apply {
					type = "message/rfc822"
					putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
					putExtra(Intent.EXTRA_SUBJECT, subject)
					putExtra(Intent.EXTRA_TEXT, feedbackMessage)
				}

			if (shareActivityResultLauncher != null) {
				shareActivityResultLauncher.launch(
					Intent.createChooser(
						fallbackIntent,
						context.getString(R.string.send_feedback),
					),
				)
			} else {
				context.startActivity(
					Intent.createChooser(
						fallbackIntent,
						context.getString(R.string.send_feedback),
					),
				)
			}
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
//		showFeedbackDialog(context, screenName, appVersion = appVersion)
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

	/**
	 * Attempts to get the current screen name from the context.
	 * Falls back to "Unknown Screen" if detection fails.
	 */
	private fun getCurrentScreenName(context: Context): String =
		when (context) {
			is Activity -> context.javaClass.simpleName.replace("Activity", "")
			else -> "Unknown Screen"
		}

    private fun sendFeedbackWithScreenshot(context: Context, activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            val handler = FeedbackEmailHandler(context)

            val screenshotData = handler.captureAndPrepareScreenshotUri(activity, "Feedback")

            val feedbackRecipient = context.getString(R.string.feedback_email)
            val feedbackSubject =
                context.getString(R.string.feedback_subject, getCurrentScreenName(context))
            val feedbackBody = context.getString(
                R.string.feedback_message,
                BuildInfo.VERSION_NAME_SIMPLE,
                ""
            )

            val emailIntent = handler.prepareEmailIntent(
                screenshotData?.first,
                screenshotData?.second,
                feedbackRecipient,
                feedbackSubject,
                feedbackBody
            )

            if (emailIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(
                    Intent.createChooser(emailIntent, "Send Feedback"),
                )
            } else {
                Toast.makeText(context, "No email app found to send feedback.", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }
}
