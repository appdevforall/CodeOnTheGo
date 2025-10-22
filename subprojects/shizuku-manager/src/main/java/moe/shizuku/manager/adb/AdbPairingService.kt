package moe.shizuku.manager.adb

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.util.Consumer
import com.itsaky.androidide.buildinfo.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.unsafeLazy
import org.slf4j.LoggerFactory
import java.net.ConnectException
import kotlin.getValue

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {
	companion object {
		private val logger = LoggerFactory.getLogger(AdbPairingService::class.java)

		const val NOTIFICATION_CHANNEL = "adb_pairing"

		private const val TAG = "AdbPairingService"

		private const val NOTIFICATION_ID = 1
		private const val REPLY_REQUEST_ID = 1
		private const val STOP_REQUEST_ID = 2
		private const val RETRY_REQUEST_ID = 3
		private const val START_ACTION = "start"
		private const val STOP_ACTION = "stop"
		private const val REPLY_ACTION = "reply"
		private const val REMOTE_INPUT_RESULT_KEY = "paring_code"
		private const val PORT_KEY = "paring_code"

		const val ACTION_PAIR_SUCCEEDED = BuildInfo.PACKAGE_NAME + ".shizuku.action.PAIR_SUCCEEDED"
		const val ACTION_PAIR_FAILED = BuildInfo.PACKAGE_NAME + ".shizuku.action.PAIR_FAILED"

		const val PERMISSION_RECEIVE_WADB_PAIR_RESULT = BuildInfo.PACKAGE_NAME + ".debug.RECEIVE_WADB_PAIR_RESULT"

		fun startIntent(context: Context): Intent = Intent(context, AdbPairingService::class.java).setAction(START_ACTION)

		private fun stopIntent(context: Context): Intent = Intent(context, AdbPairingService::class.java).setAction(STOP_ACTION)

		private fun replyIntent(
			context: Context,
			port: Int,
		): Intent =
			Intent(context, AdbPairingService::class.java)
				.setAction(REPLY_ACTION)
				.putExtra(PORT_KEY, port)
	}

	private var adbMdns: AdbMdns? = null

	private val observer =
		Consumer<Int> { port ->
			logger.info("Pairing service port: {}", port)
			if (port <= 0) return@Consumer

			// Since the service could be killed before user finishing input,
			// we need to put the port into Intent
			val notification = createInputNotification(port)

			getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
		}

	private var started = false

	override fun onCreate() {
		super.onCreate()

		getSystemService(NotificationManager::class.java).createNotificationChannel(
			NotificationChannel(
				NOTIFICATION_CHANNEL,
				getString(R.string.notification_channel_adb_pairing),
				NotificationManager.IMPORTANCE_HIGH,
			).apply {
				setSound(null, null)
				setShowBadge(false)
				setAllowBubbles(false)
			},
		)
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int {
		val notification =
			when (intent?.action) {
				START_ACTION -> onStart()
				REPLY_ACTION -> {
					val code =
						RemoteInput
							.getResultsFromIntent(intent)
							?.getCharSequence(REMOTE_INPUT_RESULT_KEY) ?: ""
					val port = intent.getIntExtra(PORT_KEY, -1)
					if (port != -1) {
						onInput(code.toString(), port)
					} else {
						onStart()
					}
				}

				STOP_ACTION -> {
					stopForeground(STOP_FOREGROUND_REMOVE)
					stopSelf()
					null
				}

				else -> null
			}

		if (notification == null) {
			return START_NOT_STICKY
		}

		runCatching {
			startForeground(
				NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
			)
		}.onFailure { error ->
			logger.error("startForeground failed", error)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
				error is ForegroundServiceStartNotAllowedException
			) {
				getSystemService(NotificationManager::class.java)
					.notify(NOTIFICATION_ID, notification)
			}
		}

		return START_REDELIVER_INTENT
	}

	private fun startSearch() {
		if (started) return
		started = true
		adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
	}

	private fun stopSearch() {
		if (!started) return
		started = false
		adbMdns?.stop()
	}

	override fun onDestroy() {
		super.onDestroy()
		stopSearch()
	}

	private fun onStart(): Notification {
		startSearch()
		return searchingNotification
	}

	private fun onInput(
		code: String,
		port: Int,
	): Notification {
		GlobalScope.launch(Dispatchers.IO) {
			val host = "127.0.0.1"

			val key =
				try {
					AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()))
				} catch (e: Throwable) {
					e.printStackTrace()
					return@launch
				}

			AdbPairingClient(host, port, code, key)
				.runCatching {
					start()
				}.onFailure {
					handleResult(false, it)
				}.onSuccess {
					handleResult(it, null)
				}
		}

		return workingNotification
	}

	private fun handleResult(
		success: Boolean,
		exception: Throwable?,
	) {
		stopForeground(STOP_FOREGROUND_REMOVE)

		val title: String
		val text: String?

		if (success) {
			logger.info("Pair succeed")

			title = getString(R.string.notification_adb_pairing_succeed_title)
			text = getString(R.string.notification_adb_pairing_succeed_text)

			stopSearch()
			sendBroadcast(Intent(ACTION_PAIR_SUCCEEDED), PERMISSION_RECEIVE_WADB_PAIR_RESULT)
		} else {
			title = getString(R.string.notification_adb_pairing_failed_title)

			text =
				when (exception) {
					is ConnectException -> {
						getString(R.string.cannot_connect_port)
					}

					is AdbInvalidPairingCodeException -> {
						getString(R.string.paring_code_is_wrong)
					}

					is AdbKeyException -> {
						getString(R.string.adb_error_key_store)
					}

					else -> {
						exception?.let { Log.getStackTraceString(it) }
					}
				}

			logger.warn("Pair failed", exception)
			sendBroadcast(Intent(ACTION_PAIR_FAILED), PERMISSION_RECEIVE_WADB_PAIR_RESULT)
		}

		getSystemService(NotificationManager::class.java).notify(
			NOTIFICATION_ID,
			Notification
				.Builder(this, NOTIFICATION_CHANNEL)
				.setSmallIcon(R.drawable.ic_launcher_fg_vector)
				.setContentTitle(title)
				.setContentText(text)
				.build(),
		)
		stopSelf()
	}

	private val stopNotificationAction by unsafeLazy {
		val pendingIntent =
			PendingIntent.getService(
				this,
				STOP_REQUEST_ID,
				stopIntent(this),
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					PendingIntent.FLAG_IMMUTABLE
				} else {
					0
				},
			)

		Notification.Action
			.Builder(
				null,
				getString(R.string.notification_adb_pairing_stop_searching),
				pendingIntent,
			).build()
	}

	private val retryNotificationAction by unsafeLazy {
		val pendingIntent =
			PendingIntent.getService(
				this,
				RETRY_REQUEST_ID,
				startIntent(this),
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					PendingIntent.FLAG_IMMUTABLE
				} else {
					0
				},
			)

		Notification.Action
			.Builder(
				null,
				getString(R.string.notification_adb_pairing_retry),
				pendingIntent,
			).build()
	}

	private val replyNotificationAction by unsafeLazy {
		val remoteInput =
			RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY).run {
				setLabel(getString(R.string.dialog_adb_pairing_paring_code))
				build()
			}

		val pendingIntent =
			PendingIntent.getForegroundService(
				this,
				REPLY_REQUEST_ID,
				replyIntent(this, -1),
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
				} else {
					PendingIntent.FLAG_UPDATE_CURRENT
				},
			)

		Notification.Action
			.Builder(
				null,
				getString(R.string.notification_adb_pairing_input_paring_code),
				pendingIntent,
			).addRemoteInput(remoteInput)
			.build()
	}

	private fun replyNotificationAction(port: Int): Notification.Action {
		// Ensure pending intent is created
		val action = replyNotificationAction

		PendingIntent.getForegroundService(
			this,
			REPLY_REQUEST_ID,
			replyIntent(this, port),
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
			} else {
				PendingIntent.FLAG_UPDATE_CURRENT
			},
		)

		return action
	}

	private val searchingNotification by unsafeLazy {
		Notification
			.Builder(this, NOTIFICATION_CHANNEL)
			.setSmallIcon(R.drawable.ic_launcher_fg_vector)
			.setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
			.addAction(stopNotificationAction)
			.build()
	}

	private fun createInputNotification(port: Int): Notification =
		Notification
			.Builder(this, NOTIFICATION_CHANNEL)
			.setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
			.setSmallIcon(R.drawable.ic_launcher_fg_vector)
			.addAction(replyNotificationAction(port))
			.build()

	private val workingNotification by unsafeLazy {
		Notification
			.Builder(this, NOTIFICATION_CHANNEL)
			.setContentTitle(getString(R.string.notification_adb_pairing_working_title))
			.setSmallIcon(R.drawable.ic_launcher_fg_vector)
			.build()
	}
}
