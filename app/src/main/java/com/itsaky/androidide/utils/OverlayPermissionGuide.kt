package com.itsaky.androidide.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.SpannableStringBuilder
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory

object OverlayPermissionGuide {

	private val log = LoggerFactory.getLogger(OverlayPermissionGuide::class.java)

	fun showRestrictedSettingsDialog(context: Context) {
		val message = SpannableStringBuilder()
		message.append(context.getString(R.string.permission_overlay_restricted_dialog_message))
		message.append("\n\n")
		message.appendOrderedList(*context.resources.getStringArray(R.array.overlay_restricted_settings_steps))

		DialogUtils
			.newMaterialDialogBuilder(context)
			.setTitle(R.string.permission_overlay_restricted_dialog_title)
			.setMessage(message)
			.setPositiveButton(R.string.permission_overlay_open_app_info) { dialog, _ ->
				dialog.dismiss()
				openAppInfo(context)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun openAppInfo(context: Context) {
		val intent = Intent(
			Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
			Uri.fromParts("package", context.packageName, null),
		)
		if (context !is Activity) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		try {
			context.startActivity(intent)
		} catch (e: ActivityNotFoundException) {
			log.error("Failed to open App info settings", e)
			flashError(
				context.getString(
					R.string.err_no_activity_to_handle_action,
					Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				),
			)
		}
	}
}
