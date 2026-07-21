package org.appdevforall.cotg.corpus.serviceapp

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock

/** Bound service handing out a local binder that reports an uptime-derived tick. */
class TickBinderService : BaseTrackedService() {
	private val binder = LocalBinder()

	override fun trackedTag(): String = "ticker"

	override fun onBind(intent: Intent?): IBinder = binder

	fun currentTick(): Int = (SystemClock.elapsedRealtime() / 1000L).toInt()

	inner class LocalBinder : Binder() {
		fun currentTick(): Int = this@TickBinderService.currentTick()
	}
}
