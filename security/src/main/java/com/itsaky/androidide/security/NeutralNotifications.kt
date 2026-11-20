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

package com.itsaky.androidide.security

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.itsaky.androidide.resources.R as ResR

/**
 * What the IDE shows in the notification shade while the disguise is engaged (ADFA-40). An ordinary
 * notification is removed. A foreground service's cannot be while the service runs, so it is
 * replaced, under the same id, by [build]'s, which names the disguise and nothing that is running.
 */
object NeutralNotifications {
	/** Removes or neutralises every notification the app is showing. */
	fun mask(context: Context) {
		val manager = context.getSystemService(NotificationManager::class.java) ?: return
		for (shown in manager.activeNotifications) {
			if (shown.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
				manager.notify(shown.tag, shown.id, build(context, shown.notification.channelId))
			} else {
				manager.cancel(shown.tag, shown.id)
			}
		}
	}

	/**
	 * The notification a foreground service shows while hidden. Services that update their own call
	 * this instead of building one, so an update does not bring the IDE's text back.
	 */
	fun build(
		context: Context,
		channelId: String,
	): Notification =
		Notification
			.Builder(context, channelId)
			.setSmallIcon(ResR.drawable.ic_stealth_notification)
			.setContentTitle(context.getString(ResR.string.stealth_app_name))
			.setShowWhen(false)
			.setOngoing(true)
			.setContentIntent(
				PendingIntent.getActivity(
					context,
					0,
					InstantHide.disguiseIntent(context),
					PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
				),
			).build()
}
