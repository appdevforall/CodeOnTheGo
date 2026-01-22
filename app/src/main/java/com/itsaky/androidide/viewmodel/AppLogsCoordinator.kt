package com.itsaky.androidide.viewmodel

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.itsaky.androidide.services.log.ConnectionObserverParams
import com.itsaky.androidide.services.log.LogReceiverImpl
import com.itsaky.androidide.services.log.LogReceiverService
import com.itsaky.androidide.services.log.LogReceiverServiceConnection
import com.itsaky.androidide.services.log.lookupLogService
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author Akash Yadav
 */
class AppLogsCoordinator(
	private val viewModel: AppLogsViewModel,
) : DefaultLifecycleObserver {
	companion object {
		private val logger = LoggerFactory.getLogger(AppLogsCoordinator::class.java)
	}

	private var context: Context? = null
	private val isBoundToLogReceiver = AtomicBoolean(false)
	private var logReceiverImpl: LogReceiverImpl? = null
	private val logBroadcastReceiver =
		LogBroadcastReceiver(
			bind = ::bindToLogReceiver,
			unbind = ::unbindFromLogReceiver,
		)

	private val logServiceConnection =
		LogReceiverServiceConnection(::onConnected)

	override fun onCreate(owner: LifecycleOwner) {
		require(owner is Context)

		context = owner
		registerLogConnectionObserver()
	}

	override fun onDestroy(owner: LifecycleOwner) {
		require(owner is Activity)

		unregisterLogConnectionObserver()
		unbindFromLogReceiver()

		// must release after all other operations
		context = null
	}

	private fun onConnected(receiverImpl: LogReceiverImpl?) {
		logReceiverImpl = receiverImpl

		val receiverService = lookupLogService() ?: return
		receiverService.setConsumer { logLine ->
			viewModel.submit(
				line = logLine,
				simpleFormattingEnabled = false,
			)
		}
	}

	private fun bindToLogReceiver() {
		if (isBoundToLogReceiver.getAndSet(true)) return

		try {
			if (!DevOpsPreferences.logsenderEnabled) {
				logger.info("LogSender is disabled. LogReceiver service won't be started...")

				// release the connection listener
				logServiceConnection.onConnected = null
				isBoundToLogReceiver.set(false)
				return
			}

			logServiceConnection.onConnected = ::onConnected
			val context = context ?: run {
				isBoundToLogReceiver.set(false)
				return
			}

			val intent =
				Intent(context, LogReceiverService::class.java).setAction(
					LogReceiverService.ACTION_CONNECT_LOG_CONSUMER,
				)

			// do not auto create the service with BIND_AUTO_CREATE
			check(context.bindService(intent, logServiceConnection, Context.BIND_IMPORTANT)) {
				"Failed to bind to LogReceiver service"
			}

			logger.info("LogReceiver service is being started")
		} catch (err: Throwable) {
			logger.error("Failed to start LogReceiver service", err)
			isBoundToLogReceiver.set(false)
		}
	}

	private fun unbindFromLogReceiver() {
		try {
			if (!isBoundToLogReceiver.get()) {
				return
			}

			lookupLogService()?.setConsumer(null)
			logReceiverImpl?.disconnectAll()

			val context = context ?: return
			context.unbindService(logServiceConnection)

			logger.info("Unbound from LogReceiver service")
		} catch (e: Exception) {
			logger.error("Failed to unbind from LogReceiver service", e)
		} finally {
			isBoundToLogReceiver.set(false)
			logServiceConnection.onConnected = null
			logReceiverImpl = null
		}
	}

	private fun registerLogConnectionObserver() =
		runCatching {
			val context =
				context ?: run {
					logger.warn(
						"attempt to register connection observer for LogReceiverService," +
							" but no context is available",
					)
					return@runCatching
				}

			val intentFilter = IntentFilter(LogReceiverService.ACTION_CONNECTION_UPDATE)
			LocalBroadcastManager
				.getInstance(context)
				.registerReceiver(logBroadcastReceiver, intentFilter)
		}.onFailure { err ->
			logger.warn("Failed to register connection observer for LogReceiverService", err)
		}

	private fun unregisterLogConnectionObserver() =
		runCatching {
			val context =
				context ?: run {
					logger.warn(
						"attempt to unregister connection observer for LogReceiverService," +
							" but no context is available",
					)
					return@runCatching
				}

			LocalBroadcastManager
				.getInstance(context)
				.unregisterReceiver(logBroadcastReceiver)
		}.onFailure { err ->
			logger.warn("Failed to unregister connection observer for LogReceiverService", err)
		}

	class LogBroadcastReceiver(
		private val bind: () -> Unit,
		private val unbind: () -> Unit,
	) : BroadcastReceiver() {
		private val logger = LoggerFactory.getLogger(LogBroadcastReceiver::class.java)

		override fun onReceive(
			context: Context?,
			intent: Intent?,
		) {
			if (intent?.action != LogReceiverService.ACTION_CONNECTION_UPDATE) {
				logger.warn(
					"Received invalid broadcast. Action '{}' is expected.",
					LogReceiverService.ACTION_CONNECTION_UPDATE,
				)
				return
			}

			val params =
				ConnectionObserverParams.from(intent) ?: run {
					logger.warn(
						"Received {} broadcast, but invalid extras were provided: {}",
						LogReceiverService.ACTION_CONNECTION_UPDATE,
						intent,
					)
					return
				}

			if (params.totalConnections > 0) {
				// log receiver has been connected to one or more log senders
				// bind to the receiver and notify senders to start reading logs
				bind()
				return
			}

			if (params.totalConnections == 0) {
				// all log senders have been disconnected from the log receiver
				// unbind from the log receiver
				unbind()
				return
			}
		}
	}
}
