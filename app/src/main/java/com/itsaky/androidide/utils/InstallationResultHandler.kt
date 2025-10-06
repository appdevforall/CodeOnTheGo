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

package com.itsaky.androidide.utils

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import androidx.core.app.PendingIntentCompat
import com.itsaky.androidide.services.InstallationResultReceiver
import org.slf4j.LoggerFactory

/**
 * Handles result of APK installation.
 *
 * @author Akash Yadav
 */
object InstallationResultHandler {

	private val log = LoggerFactory.getLogger(InstallationResultHandler::class.java)

	@JvmStatic
	fun onResult(context: Activity?, intent: Intent?): String? {
		if (context == null || intent == null || intent.action != InstallationResultReceiver.ACTION_INSTALL_STATUS) {
			log.warn("Invalid broadcast received. action={}", intent?.action)
			return null
		}

		log.debug("onResult: intent={}, intent.extras={}", intent, intent.extras)

		val extras =
			intent.extras
				?: run {
					log.warn("Invalid intent received in broadcast")
					return null
				}

		val packageName = extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME)
		val status = extras.getInt(PackageInstaller.EXTRA_STATUS)
		val message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)

		return when (status) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				log.debug("PENDING_USER_ACTION: extras={}", extras)
				@Suppress("DEPRECATION")
				extras.get(Intent.EXTRA_INTENT)?.let { intent ->
					if (intent is Intent) {
						if ((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != Intent.FLAG_ACTIVITY_NEW_TASK) {
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						}
						log.debug("startingActivity: intent={}, intent.extras={}", intent, intent.extras)
						context.startActivity(intent)
					}
				}
				null
			}

			PackageInstaller.STATUS_SUCCESS -> {
				log.info("Package installed successfully!")
				packageName
			}

			PackageInstaller.STATUS_FAILURE,
			PackageInstaller.STATUS_FAILURE_ABORTED,
			PackageInstaller.STATUS_FAILURE_BLOCKED,
			PackageInstaller.STATUS_FAILURE_CONFLICT,
			PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
			PackageInstaller.STATUS_FAILURE_INVALID,
			PackageInstaller.STATUS_FAILURE_STORAGE -> {
				log.error(
					"Package installation failed with status code {} and message {}",
					status,
					message
				)
				null
			}

			else -> {
				log.warn("Invalid status code received in broadcast: {}", status)
				null
			}
		}
	}
}
