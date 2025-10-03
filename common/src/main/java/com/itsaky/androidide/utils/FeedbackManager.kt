package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.launch

/**
 * A reusable feedback manager that provides consistent UI for sending feedback
 * from any screen in the CoGo application.
 */
object FeedbackManager {
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
					"",
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
                        context.getString(R.string.no_email_apps), Toast.LENGTH_LONG)
					.show()
			}
		}
	}
}
