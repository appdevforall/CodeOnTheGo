package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.text.HtmlCompat
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A reusable feedback manager that provides consistent UI for sending feedback
 * from any screen in the CoGo application.
 */
object FeedbackManager {

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
