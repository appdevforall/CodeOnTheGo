
package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.itsaky.androidide.resources.R

/**
 * A reusable feedback manager that provides consistent UI for sending feedback
 * from any screen in the CoGo application.
 */
object FeedbackManager {

    private const val EMAIL_SUPPORT = "feedback@appdevforall.org"

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
        appVersion: String? = null
    ) {
        val builder = DialogUtils.newMaterialDialogBuilder(context)
        
        builder.setTitle("Alert!")
            .setMessage(
                HtmlCompat.fromHtml(
                    context.getString(R.string.email_feedback_warning_prompt),
                    HtmlCompat.FROM_HTML_MODE_COMPACT
                )
            )
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                sendFeedback(
                    context,
                    currentScreen,
                    shareActivityResultLauncher,
                    customSubject,
                    throwable,
                    appVersion
                )
            }
            .show()
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
        appVersion: String? = null
    ) {
        val screenName = currentScreen ?: getCurrentScreenName(context)
        val stackTrace = throwable?.let { 
            it.stackTraceToString()
        } ?: ""
        
        val feedbackMessage = context.getString(
            R.string.feedback_message,
            appVersion ?: "Unknown",
            stackTrace
        )

        val subject = customSubject ?: String.format(
            context.getString(R.string.feedback_subject),
            screenName
        )

        try {
            val feedbackIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, feedbackMessage)
            }

            if (shareActivityResultLauncher != null) {
                shareActivityResultLauncher.launch(
                    Intent.createChooser(feedbackIntent, "Send Feedback")
                )
            } else {
                context.startActivity(
                    Intent.createChooser(feedbackIntent, "Send Feedback")
                )
            }
        } catch (e: Exception) {
            // Fallback to general send intent
            try {
                val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_SUPPORT))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, feedbackMessage)
                }
                
                if (shareActivityResultLauncher != null) {
                    shareActivityResultLauncher.launch(
                        Intent.createChooser(
                            fallbackIntent,
                            context.getString(R.string.send_feedback)
                        )
                    )
                } else {
                    context.startActivity(
                        Intent.createChooser(
                            fallbackIntent,
                            context.getString(R.string.send_feedback)
                        )
                    )
                }
            } catch (e2: Exception) {
                // If all else fails, show simple contact dialog
                showContactDialog(context)
            }
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
    fun quickFeedback(context: Context, screenName: String? = null, appVersion: String? = null) {
        showFeedbackDialog(context, screenName, appVersion = appVersion)
    }

    /**
     * Shows a simple contact dialog as fallback when email intents fail.
     * Uses the same title, message, and button text as the existing contact dialog.
     */
    fun showContactDialog(context: Context) {
        val builder = DialogUtils.newMaterialDialogBuilder(context)

        builder.setTitle(R.string.msg_contact_app_dev_title)
            .setMessage(R.string.msg_contact_app_dev_description)
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.send_email) { dialog, _ ->
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:$EMAIL_SUPPORT?subject=${context.getString(R.string.feedback_email_subject)}".toUri()
                }
                context.startActivity(intent)
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /**
     * Attempts to get the current screen name from the context.
     * Falls back to "Unknown Screen" if detection fails.
     */
    private fun getCurrentScreenName(context: Context): String {
        return when (context) {
            is Activity -> context.javaClass.simpleName.replace("Activity", "")
            else -> "Unknown Screen"
        }
    }
}