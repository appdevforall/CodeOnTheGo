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

package com.itsaky.androidide.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.events.InstallationEvent.InstallationResultEvent
import com.itsaky.androidide.repositories.DebuggerEndpointRepository
import com.itsaky.androidide.utils.ApkInstaller
import org.greenrobot.eventbus.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory

/**
 * Receives status events for APK installation process.
 *
 * @author Akash Yadav
 */
class InstallationResultReceiver :
	BroadcastReceiver(),
	KoinComponent {
	companion object {
		const val ACTION_INSTALL_STATUS = "${BuildInfo.PACKAGE_NAME}.installer.INSTALL_STATUS"

		private val log = LoggerFactory.getLogger(InstallationResultReceiver::class.java)
	}

	private val debuggerEndpointRepository: DebuggerEndpointRepository by inject()

	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		ApkInstaller.onInstallStatusBroadcast(intent, context)
		pairSqliteDebugger(intent)
		EventBus.getDefault().post(InstallationResultEvent(intent))
	}

	/**
	 * Pairs the installed app with the project whose install it was, for the SQLite debugger (ADFA-7137), or drops
	 * the install's note when it failed. Done here rather than in the editor, which receives the event only while it
	 * is started: the event is not sticky, and the install prompt can stop the editor. On success it pairs only an
	 * install that ProjectHandlerActivity noted, and does not read EXTRA_SUPPRESS_POST_INSTALL_LAUNCH; Quick Build's
	 * installs, the ones that set it, are never noted, so never paired. The event, which launches the app, is posted
	 * without waiting for the write.
	 */
	private fun pairSqliteDebugger(intent: Intent) {
		try {
			val extras = intent.extras ?: return
			if (!extras.containsKey(PackageInstaller.EXTRA_SESSION_ID) || !extras.containsKey(PackageInstaller.EXTRA_STATUS)) return
			val sessionId = extras.getInt(PackageInstaller.EXTRA_SESSION_ID)
			when (extras.getInt(PackageInstaller.EXTRA_STATUS)) {
				PackageInstaller.STATUS_PENDING_USER_ACTION -> {
					// Not a result yet: the note waits for the one that follows the prompt.
				}

				PackageInstaller.STATUS_SUCCESS -> {
					val write =
						debuggerEndpointRepository.confirmInstall(sessionId, extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME))
							?: return
					// Null when the broadcast was not delivered by the framework, as in a direct call.
					val pending: PendingResult? = goAsync()
					write.invokeOnCompletion { pending?.finish() }
				}

				else -> {
					debuggerEndpointRepository.discardPendingInstall(sessionId)
				}
			}
		} catch (e: Exception) {
			// The receiver is exported, so the extras can be malformed; the launch must go on regardless.
			log.warn("Could not pair the SQLite debugger of an install", e)
		}
	}
}
