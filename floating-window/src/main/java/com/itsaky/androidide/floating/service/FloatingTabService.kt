

package com.itsaky.androidide.floating.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.itsaky.androidide.floating.model.DockingManager
import com.itsaky.androidide.floating.model.FloatingTab
import com.itsaky.androidide.floating.window.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import com.itsaky.androidide.resources.R as ResR

/**
 * Foreground service that owns the live floating overlay windows. It reconciles its set of
 * [FloatingWindow]s from [DockingManager.windows]: a tab appearing in the flow gets a window added,
 * a tab disappearing gets its window removed. When no windows remain, the service stops itself.
 *
 * Each window is an independent overlay so touches in the gaps between windows pass straight through
 * to the apps behind. It lives in the same process as the editor activity, so it shares
 * [DockingManager] directly with no IPC; the foreground status is only what keeps the windows alive
 * over other apps.
 */
class FloatingTabService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val windows = LinkedHashMap<String, FloatingWindow>()
	private val failed = HashSet<String>()
	private var collectJob: Job? = null

	override fun onCreate() {
		super.onCreate()
		startForeground(NOTIFICATION_ID, buildNotification())
		collectJob =
			scope.launch {
				DockingManager.windows.collect { tabs ->
					runCatching { reconcile(tabs) }
						.onFailure { log.error("Floating window reconcile failed", it) }
				}
			}
		log.debug("FloatingTabService created")
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int = START_NOT_STICKY

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		collectJob?.cancel()
		windows.values.forEach(FloatingWindow::dismiss)
		windows.clear()
		scope.cancel()
		log.debug("FloatingTabService destroyed")
		super.onDestroy()
	}

	private fun reconcile(tabs: List<FloatingTab>) {
		val ids = tabs.mapTo(HashSet()) { it.id }
		failed.retainAll(ids)

		windows.keys.filter { it !in ids }.forEach { id ->
			windows.remove(id)?.let { runCatching { it.dismiss() } }
		}

		tabs.forEach { tab ->
			if (tab.id in failed || windows.containsKey(tab.id)) {
				return@forEach
			}
			try {
				val window = FloatingWindow(newWindowContext(), tab)
				windows[tab.id] = window
				window.show()
			} catch (t: Throwable) {
				log.error("Failed to create floating window for {}; dropping it", tab.id, t)
				failed.add(tab.id)
				windows.remove(tab.id)?.let { runCatching { it.dismiss() } }
				DockingManager.close(tab.id)
			}
		}

		if (windows.isEmpty()) {
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
		}
	}

	private fun newWindowContext(): Context {
		val base: Context =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				val display =
					(getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
						.getDisplay(Display.DEFAULT_DISPLAY)
				createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
			} else {
				this
			}
		return ContextThemeWrapper(base, ResR.style.Theme_AndroidIDE)
	}

	private fun buildNotification(): Notification {
		createChannel()
		return NotificationCompat
			.Builder(this, CHANNEL_ID)
			.setContentTitle("Floating editor windows")
			.setContentText("Editor windows are floating over other apps")
			.setSmallIcon(android.R.drawable.ic_menu_view)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}

	private fun createChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			if (manager.getNotificationChannel(CHANNEL_ID) == null) {
				manager.createNotificationChannel(
					NotificationChannel(
						CHANNEL_ID,
						"Floating windows",
						NotificationManager.IMPORTANCE_LOW,
					),
				)
			}
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(FloatingTabService::class.java)
		private const val CHANNEL_ID = "floating_windows"
		private const val NOTIFICATION_ID = 0x0F10A7

		/** Start (or no-op if already running) the foreground service that hosts floating windows. */
		fun ensureRunning(context: Context) {
			ContextCompat.startForegroundService(
				context,
				Intent(context, FloatingTabService::class.java),
			)
		}
	}
}
