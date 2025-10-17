/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import com.github.appintro.SlidePolicy
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.onboarding.OnboardingPermissionsAdapter
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.utils.PermissionsHelper.getRequiredPermissions
import com.itsaky.androidide.utils.PermissionsHelper.isPermissionGranted
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isAtLeastR
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class PermissionsFragment :
	OnboardingMultiActionFragment(),
	SlidePolicy {
	var adapter: OnboardingPermissionsAdapter? = null

	private val storagePermissionRequestLauncher =
		registerForActivityResult(
			ActivityResultContracts.RequestMultiplePermissions(),
		) {
			onPermissionsUpdated()
		}

	private val settingsTogglePermissionRequestLauncher =
		registerForActivityResult(
			ActivityResultContracts.StartActivityForResult(),
		) {
			onPermissionsUpdated()
		}

	private val permissions by lazy {
		getRequiredPermissions(requireContext())
	}

	companion object {
		private val logger = LoggerFactory.getLogger(PermissionsFragment::class.java)

		@JvmStatic
		fun newInstance(context: Context): PermissionsFragment =
			PermissionsFragment().apply {
				arguments =
					Bundle().apply {
						putCharSequence(
							KEY_ONBOARDING_TITLE,
							context.getString(R.string.onboarding_title_permissions),
						)
						putCharSequence(
							KEY_ONBOARDING_SUBTITLE,
							context.getString(R.string.onboarding_subtitle_permissions),
						)
					}
			}
	}

	override fun createAdapter(): RecyclerView.Adapter<*> =
		OnboardingPermissionsAdapter(
			permissions,
			this::requestPermission,
		).also { this.adapter = it }

	private fun onPermissionsUpdated() {
		permissions.forEach { it.isGranted = isPermissionGranted(requireContext(), it.permission) }
		recyclerView?.adapter = createAdapter()
	}

	private fun requestPermission(permission: String) {
		when (permission) {
			Manifest.permission_group.STORAGE -> requestStoragePermission()
			Manifest.permission.REQUEST_INSTALL_PACKAGES ->
				requestSettingsTogglePermission(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
				)

			Manifest.permission.SYSTEM_ALERT_WINDOW -> requestSettingsTogglePermission(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
			Manifest.permission.BIND_ACCESSIBILITY_SERVICE ->
				requestSettingsTogglePermission(
					Settings.ACTION_ACCESSIBILITY_SETTINGS,
					false,
				)

			Manifest.permission.POST_NOTIFICATIONS ->
				requestSettingsTogglePermission(
					Settings.ACTION_APP_NOTIFICATION_SETTINGS,
					setData = false,
				)
		}
	}

	private fun requestStoragePermission() {
		if (isAtLeastR()) {
			requestSettingsTogglePermission(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
			return
		}

		storagePermissionRequestLauncher.launch(
			arrayOf(
				Manifest.permission.READ_EXTERNAL_STORAGE,
				Manifest.permission.WRITE_EXTERNAL_STORAGE,
			),
		)
	}

	private fun requestSettingsTogglePermission(
		action: String,
		setData: Boolean = true,
	) {
		val intent = Intent(action)
		intent.putExtra(Settings.EXTRA_APP_PACKAGE, BuildInfo.PACKAGE_NAME)
		if (setData) {
			intent.setData(Uri.fromParts("package", BuildInfo.PACKAGE_NAME, null))
		}
		try {
			settingsTogglePermissionRequestLauncher.launch(intent)
		} catch (err: Throwable) {
			logger.error("Failed to launch settings with intent {}", intent, err)
			flashError(getString(R.string.err_no_activity_to_handle_action, action))
		}
	}

	override val isPolicyRespected: Boolean
		get() = permissions.all { it.isOptional || it.isGranted }

	override fun onUserIllegallyRequestedNextPage() {
		activity?.flashError(R.string.msg_grant_permissions)
	}
}
