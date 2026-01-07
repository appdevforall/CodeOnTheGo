package com.itsaky.androidide.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuStarter
import moe.shizuku.manager.ShizukuState
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException
import kotlin.time.Duration.Companion.seconds

/**
 * A view model to handle the wireless ADB connection. This class must never hold
 * references to a fragment or activity.
 *
 * @author Akash Yadav
 */
@RequiresApi(Build.VERSION_CODES.R)
class WADBConnectionViewModel : ViewModel() {

	sealed interface ConnectionStatus {

		data object Unknown : ConnectionStatus

		data object Pairing : ConnectionStatus

		data object Paired : ConnectionStatus

		data object PairingFailed : ConnectionStatus

		data class SearchingConnectionPort(
			val retryCount: Int,
			val maxRetries: Int,
		) : ConnectionStatus

		data object ConnectionPortNotFound : ConnectionStatus

		data class Connecting(
			val port: Int
		) : ConnectionStatus

		data class ConnectionFailed(
			val error: Throwable?
		) : ConnectionStatus

		data object Connected : ConnectionStatus
	}

	private data class ShizukuStarterOutput(
		private val _output: StringBuilder = StringBuilder(),
		private var _error: Throwable? = null,
	) {
		val output: String
			get() = this._output.toString().trim()

		val error: Throwable?
			get() = _error

		fun append(output: CharSequence) {
			this.output.apply {
				append(output)
				append(System.lineSeparator())
			}
		}

		fun clearOutput() {
			this._output.clear()
		}

		fun recordConnectionFailure(err: Throwable? = null) {
			this._error = err
		}
	}

	companion object {
		private val logger = LoggerFactory.getLogger(WADBConnectionViewModel::class.java)

		/**
		 * Number of times to retry finding the ADB connection port.
		 */
		const val CONNECTION_MAX_RETRIES = 3

		/**
		 * Delay between each retry.
		 */
		val CONNECTION_RETRY_DELAY = 3.seconds
	}

	private var _adbMdnsConnector: AdbMdns? = null
	private val adbConnectListener: (Int) -> Unit = { port ->
		onFindAdbConnectionPort(port)
	}

	private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)

	/**
	 * Connection status.
	 */
	val status = _status.asStateFlow()

	private val pairingBroadcastReceiver =
		object : BroadcastReceiver() {
			override fun onReceive(
				context: Context?,
				intent: Intent?,
			) {
				when (intent?.action) {
					AdbPairingService.ACTION_PAIR_STARTED,
					AdbPairingService.ACTION_PAIR_SUCCEEDED,
					AdbPairingService.ACTION_PAIR_FAILED,
						-> onPairResult(intent)
				}
			}
		}

	val adbMdnsConnector: AdbMdns
		get() = checkNotNull(_adbMdnsConnector) {
			"AdbMdnsConnector is not initialized."
		}

	suspend fun start(context: Context): Unit = supervisorScope {
		if (_adbMdnsConnector == null) {
			_adbMdnsConnector = AdbMdns(
				context = context,
				serviceType = AdbMdns.TLS_CONNECT,
				observer = adbConnectListener,
			)
		}

		// start listening for pairing results
		launch(Dispatchers.Main) { doStart(context) }

		// also try to connect at the same time
		// helpful in cases where the user has already completed pairing
		// and has wireless debugging turned on
		adbMdnsConnector.start()
	}

	private fun doStart(context: Context) {
		val filter =
			IntentFilter().apply {
				addAction(AdbPairingService.ACTION_PAIR_SUCCEEDED)
				addAction(AdbPairingService.ACTION_PAIR_FAILED)
			}

		ContextCompat.registerReceiver(
			context,
			pairingBroadcastReceiver,
			filter,
			AdbPairingService.PERMISSION_RECEIVE_WADB_PAIR_RESULT,
			null,
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun onPairResult(intent: Intent) =
		when (intent.action) {
			AdbPairingService.ACTION_PAIR_STARTED -> {
				_status.update { ConnectionStatus.Pairing }
			}
			AdbPairingService.ACTION_PAIR_SUCCEEDED -> {
				_status.update { ConnectionStatus.Paired }

				// pairing was successful, look for ADB connection port
				// use GlobalScope so that we can complete the connection even
				// when the owner is destroyed
				GlobalScope.launch(context = Dispatchers.IO) {
					beginShizukuConnection()
				}
			}

			AdbPairingService.ACTION_PAIR_FAILED -> {
				_status.update { ConnectionStatus.PairingFailed }
			}

			else -> {
				Unit
			}
		}

	/**
	 * Called in the global scope. Be extra careful when access fragment's
	 * instance properties or states.
	 */
	private suspend fun beginShizukuConnection() {
		var retryCount = 0
		while (retryCount < CONNECTION_MAX_RETRIES) {
			logger.debug("Finding ADB connection port (try {})", retryCount)
			_status.update {
				ConnectionStatus.SearchingConnectionPort(
					retryCount,
					CONNECTION_MAX_RETRIES
				)
			}
			// restart ADB connection finder
			adbMdnsConnector.restart()

			// once started, wait for it to connect
			delay(CONNECTION_RETRY_DELAY)

			if (Shizuku.pingBinder()) {
				// connected successfully
				break
			}

			// connection failed, retry if needed
			retryCount++
		}

		if (!Shizuku.pingBinder()) {
			logger.error("Failed to connect to ADB server")
			_status.update { ConnectionStatus.ConnectionPortNotFound }
		}
	}

	/**
	 * This method always runs in [GlobalScope]. This is because we want to
	 * complete the connection even when the fragment is destroyed. If the user
	 * already completed the pairing process, but the fragment was somehow
	 * destroyed, we don't want the user to go through the process again because
	 * we're already paired. This will work as long as the user has already
	 * completed pairing and has wireless debugging turned on. If wireless
	 * debugging is turned off, there's no other way and the user will have
	 * to go to Developer Options to turn on wireless debugging.
	 *
	 * Care must be taken when accessing fragment's resources, since it may
	 * be destroyed at any time. Use [com.itsaky.androidide.utils.viewLifecycleScopeOrNull] to launch
	 * coroutines in the fragment's view lifecycle. [com.itsaky.androidide.utils.viewLifecycleScopeOrNull]
	 * returns null if the fragment's view has already been destroyed or not
	 * yet created.
	 */
	@OptIn(DelicateCoroutinesApi::class)
	private fun onFindAdbConnectionPort(port: Int) =
		GlobalScope.launch(Dispatchers.IO) {
			logger.debug("onFindAdbConnectionPort: port={}", port)

			if (Shizuku.pingBinder()) {
				logger.debug("Shizuku service is already running")
				runCatching { adbMdnsConnector.stop() }
				return@launch
			}

			_status.update { ConnectionStatus.Connecting(port) }
			val key =
				runCatching {
					AdbKey(
						adbKeyStore =
							PreferenceAdbKeyStore(
								preference = ShizukuSettings.getSharedPreferences(),
							),
					)
				}

			if (key.isFailure) {
				logger.error(
					"Failed to get ADB key",
					key.exceptionOrNull()
				)
				_status.update { ConnectionStatus.ConnectionFailed(key.exceptionOrNull()) }
				return@launch
			}

			val starterOutput = ShizukuStarterOutput()

			val host = "127.0.0.1"
			AdbClient(host = host, port = port, key = key.getOrThrow())
				.runCatching {
					connect()
					shellCommand(ShizukuStarter.internalCommand) { outputBytes ->
						val output = String(outputBytes)
						logger.debug(
							"[shizuku_starter] {}",
							output
						)

						starterOutput.append(output)
						onUpdateConnectionState(starterOutput)
					}
				}.onFailure { error ->
					val isCertificateError =
						error.message?.let { message ->
							message.contains("error:10000416") || message.contains("SSLV3_ALERT_CERTIFICATE_UNKNOWN")
						} ?: false
					if (error is SSLProtocolException && isCertificateError) {
						// Suppress error caused because of the OS not recognizing our certificate,
						// which happens when all of the following conditions are met :
						// 1. Wireless Debugging is turned on in Developer Options
						// 2. Shizuku is not already running
						// 3. User has not completed the WADB pairing process (which registers our public key certificate to the OS)
						return@onFailure
					}

					logger.error(
						"Failed to connect to ADB server",
						error
					)

					_status.update { ConnectionStatus.ConnectionFailed(error) }
					starterOutput.recordConnectionFailure(error)
					onUpdateConnectionState(starterOutput)

					// connection failed, no point in trying to find the port
					if (port != -1) {
						runCatching { adbMdnsConnector.stop() }
					}
				}
		}

	/**
	 * Called in the [GlobalScope], take care when accessing fragment's
	 * resources here.
	 */
	private fun onUpdateConnectionState(starterOutput: ShizukuStarterOutput) {
		if (Shizuku.pingBinder()) {
			// already connected
			// reset state
			_status.update { ConnectionStatus.Connected }
			return
		}

		val output = starterOutput.output
		if (output.endsWith("info: shizuku_starter exit with 0")) {
			starterOutput.clearOutput()

			logger.debug("Shizuku starter exited successfully")

			// starter was successful in starting the Shizuku service
			// get the binder to communicate with it
			Shizuku.addBinderReceivedListener(
				object : Shizuku.OnBinderReceivedListener {
					override fun onBinderReceived() {
						logger.debug("Shizuku service connected")
						Shizuku.removeBinderReceivedListener(this)

						// onBinderReceived may be called even after the fragment, or even the
						// activity is destroyed, so we need to ensure that we dispatch UI state
						// updates within the lifecycle of the fragment (if visible), or the activity

						// ShizukuState is a global singleton, not a ViewModel.
						// Making it a viewModel requires us to ensure that we can safely access
						// the view model here. But since this callback can be received even after
						// the fragment is destroyed, it becomes difficult to do so.
						// See ADFA-1572 for more details on why it's implemented this way.
						ShizukuState.reload()

						_status.update { ConnectionStatus.Connected }
					}
				},
			)
		} else if (starterOutput.error != null) {
			logger.error(
				"Failed to start Shizuku starter",
				starterOutput.error
			)

			var message = 0
			when (starterOutput.error) {
				is AdbKeyException -> {
					message = R.string.adb_error_key_store
				}

				is ConnectException -> {
					message = R.string.cannot_connect_port
				}

				is SSLProtocolException -> {
					message = R.string.adb_pair_required
				}
			}

			if (message != 0) {
				flashError(message)
			}
		}
	}

	fun stop(context: Context) {
		runCatching { _adbMdnsConnector?.stop() }
			.onFailure { err ->
				logger.error("Failed to stop AdbMdnsConnector", err)
			}

		runCatching { context.unregisterReceiver(pairingBroadcastReceiver) }
			.onFailure { err ->
				logger.error("Failed to unregister pairing result receiver", err)
			}
	}
}