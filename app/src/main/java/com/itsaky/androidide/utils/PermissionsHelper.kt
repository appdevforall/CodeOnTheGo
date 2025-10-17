package com.itsaky.androidide.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.models.OnboardingPermissionItem

/**
 * @author Akash Yadav
 */
object PermissionsHelper {

	fun getRequiredPermissions(context: Context): List<OnboardingPermissionItem> {
		val permissions = mutableListOf<OnboardingPermissionItem>()

		if (isAtLeastT()) {
			permissions.add(
				OnboardingPermissionItem(
					Manifest.permission.POST_NOTIFICATIONS,
					R.string.permission_title_notifications,
					R.string.permission_desc_notifications,
					canPostNotifications(context),
				),
			)
		}

		permissions.add(
			OnboardingPermissionItem(
				Manifest.permission_group.STORAGE,
				R.string.permission_title_storage,
				R.string.permission_desc_storage,
				isStoragePermissionGranted(context),
			),
		)

		permissions.add(
			OnboardingPermissionItem(
				Manifest.permission.REQUEST_INSTALL_PACKAGES,
				R.string.permission_title_install_packages,
				R.string.permission_desc_install_packages,
				canRequestPackageInstalls(context),
			),
		)

		permissions.add(
			OnboardingPermissionItem(
				Manifest.permission.SYSTEM_ALERT_WINDOW,
				R.string.permission_title_overlay_window,
				R.string.permission_desc_overlay_window,
				canDrawOverlays(context),
			),
		)

		return permissions
	}


	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	fun canPostNotifications(context: Context) =
		isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)


	fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)


	fun areAllPermissionsGranted(context: Context): Boolean =
		getRequiredPermissions(context).all { it.isOptional || it.isGranted }


	fun isStoragePermissionGranted(context: Context): Boolean {
		if (isAtLeastR()) {
			return Environment.isExternalStorageManager()
		}

		return checkSelfPermission(
			context,
			Manifest.permission.READ_EXTERNAL_STORAGE,
		) && checkSelfPermission(
			context,
			Manifest.permission.WRITE_EXTERNAL_STORAGE,
		)
	}


	fun canRequestPackageInstalls(context: Context): Boolean =
		context.packageManager.canRequestPackageInstalls()


	fun isPermissionGranted(
		context: Context,
		permission: String,
	): Boolean = when (permission) {
		Manifest.permission_group.STORAGE -> isStoragePermissionGranted(context)
		Manifest.permission.REQUEST_INSTALL_PACKAGES -> context.packageManager.canRequestPackageInstalls()
		Manifest.permission.SYSTEM_ALERT_WINDOW -> canDrawOverlays(context)
		else -> checkSelfPermission(context, permission)
	}


	fun checkSelfPermission(
		context: Context,
		permission: String,
	): Boolean = ActivityCompat.checkSelfPermission(
		context,
		permission,
	) == PackageManager.PERMISSION_GRANTED
}