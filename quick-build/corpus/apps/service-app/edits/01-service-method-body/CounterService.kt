package org.appdevforall.cotg.corpus.serviceapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder

/**
 * Started + foreground service: every onStartCommand promotes itself to foreground with
 * a dataSync-typed notification (matching the manifest's foregroundServiceType).
 */
class CounterService : BaseTrackedService() {
	private val channelId = "qb_counter"
	private var startCount = 0

	override fun trackedTag(): String = "counter"

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		startCount++
		startForeground(1, buildNotification("QB_SVC_BODY_MARKER_V2 start #$startId"))
		return START_STICKY
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun buildNotification(text: String): Notification {
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(channelId, "Counter", NotificationManager.IMPORTANCE_LOW),
		)
		return Notification.Builder(this, channelId)
			.setContentTitle("Quick Build corpus counter (edited)")
			.setContentText(CounterFormatter.describe(startCount) + " " + text)
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.build()
	}
}
