package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.ShizukuStarter
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.utils.UserHandleCompat
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BootCompleteReceiver : BroadcastReceiver() {
	companion object {
		private val logger = LoggerFactory.getLogger(BootCompleteReceiver::class.java)
	}

	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action &&
			Intent.ACTION_BOOT_COMPLETED != intent.action
		) {
			logger.warn("Boot receiver called for {}. Ignoring.", intent.action)
			return
		}

		if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) {
			logger.warn("User != 0 or Shizuku is already running. Not starting")
			return
		}

		val lastLaunchMode = ShizukuSettings.getLastLaunchMode()
		logger.info("Boot completed. Last launch mode: {}", lastLaunchMode)

		if (lastLaunchMode == LaunchMethod.ROOT) {
			rootStart(context)
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			// https://r.android.com/2128832
			context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
			lastLaunchMode == LaunchMethod.ADB
		) {
			adbStart(context)
		} else {
			logger.warn("Shizuku server cannot be started on boot: not supported")
		}
	}

	private fun rootStart(context: Context) {
		if (!Shell.getShell().isRoot) {
			logger.warn("Root shell not accessible. Not starting")
			// NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
			Shell.getCachedShell()?.close()
			return
		}

		logger.info(
			"Starting Shizuku server on boot using root with cmd: {}",
			ShizukuStarter.internalCommand,
		)
		Shell.cmd(ShizukuStarter.internalCommand).exec()
	}

	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	private fun adbStart(context: Context) {
		logger.info("Starting Shizuku server on boot using ADB")
		val cr = context.contentResolver
		Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
		Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
		Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

		val pending = goAsync()
		CoroutineScope(Dispatchers.IO).launch {
			val latch = CountDownLatch(1)

			val adbMdns =
				AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
					logger.info("Found WADB connection port: {}", port)
					if (port <= 0) return@AdbMdns

					try {
						logger.info("Connecting to WADB on port {}...", port)
						val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
						val key = AdbKey(keystore)
						val client = AdbClient("127.0.0.1", port, key)
						client.connect()

						logger.info(
							"Connected to WADB. Starting Shizuku server with cmd: {}",
							ShizukuStarter.internalCommand,
						)
						client.shellCommand(ShizukuStarter.internalCommand, null)
						client.close()
					} catch (e: Exception) {
						logger.error("Failed to connect to WADB", e)
					}

					latch.countDown()
				}

			if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
				logger.info("WADB is ON. Searching WADB connection port...")
				adbMdns.start()
				latch.await(3, TimeUnit.SECONDS)
				adbMdns.stop()
			} else {
				logger.warn("WADB is OFF. Cannot set adb_wifi_enabled to 1. Not starting Shizuku server.")
			}

			pending.finish()
		}
	}
}
