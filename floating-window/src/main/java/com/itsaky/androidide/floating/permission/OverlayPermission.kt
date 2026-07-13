

package com.itsaky.androidide.floating.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Helpers for the "display over other apps" ([Settings.ACTION_MANAGE_OVERLAY_PERMISSION]) grant. */
object OverlayPermission {
	fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

	fun requestIntent(context: Context): Intent =
		Intent(
			Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
			Uri.parse("package:${context.packageName}"),
		)
}
