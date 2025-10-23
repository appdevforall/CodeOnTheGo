package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.databinding.FragmentWadbConnectionBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DeviceUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isAtLeastS
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.utils.viewLifecycleScopeOrNull
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import com.itsaky.androidide.viewmodel.WADBViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuStarter
import moe.shizuku.manager.ShizukuState
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

/**
 * Fragment to request wireless ADB permissions.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WADBPermissionFragment :
	FragmentWithBinding<FragmentWadbConnectionBinding>(FragmentWadbConnectionBinding::inflate) {
	companion object {
		const val VIEW_PAIRING = 0
		const val VIEW_CONNECTING = 1

		const val CONNECTION_RETRY_COUNT = 3
		const val CONNECTION_RETRY_DELAY_MS = 3 * 1000L

		private val logger = LoggerFactory.getLogger(WADBPermissionFragment::class.java)

		fun newInstance() = WADBPermissionFragment()
	}

	// must be activity bound since it's also used in DebuggerFragment
	private val bottomSheetViewModel by activityViewModels<BottomSheetViewModel>()
	private val debuggerViewModel by activityViewModels<DebuggerViewModel>()
	private val wadbViewModel by activityViewModels<WADBViewModel>()

	private val pairingBroadcastReceiver =
		object : BroadcastReceiver() {
			override fun onReceive(
				context: Context?,
				intent: Intent?,
			) {
				when (intent?.action) {
					AdbPairingService.ACTION_PAIR_SUCCEEDED,
					AdbPairingService.ACTION_PAIR_FAILED,
						-> onPairResult(intent)
				}
			}
		}

	private val adbConnectListener = { port: Int ->
		onFindAdbConnectionPort(port)
		Unit
	}

	private val adbMdnsConnector: AdbMdns by lazy {
		AdbMdns(
			context = requireContext(),
			serviceType = AdbMdns.TLS_CONNECT,
			observer = adbConnectListener,
		)
	}

	@SuppressLint("WrongConstant")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val filter =
			IntentFilter().apply {
				addAction(AdbPairingService.ACTION_PAIR_SUCCEEDED)
				addAction(AdbPairingService.ACTION_PAIR_FAILED)
			}

		ContextCompat.registerReceiver(
			/* context = */ requireContext(),
			/* receiver = */ pairingBroadcastReceiver,
			/* filter = */ filter,
			/* broadcastPermission = */ AdbPairingService.PERMISSION_RECEIVE_WADB_PAIR_RESULT,
			/* scheduler = */ null,
			/* flags = */ ContextCompat.RECEIVER_NOT_EXPORTED,
		)
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		viewLifecycleScope.launch {
			adbMdnsConnector.start()
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					wadbViewModel.connectionStatus.collectLatest { status ->
						withContext(Dispatchers.Main.immediate) {
							binding.statusText.text = status
						}
					}
				}
			}
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun onPairResult(intent: Intent) =
		when (intent.action) {
			AdbPairingService.ACTION_PAIR_SUCCEEDED -> {
				viewLifecycleScopeOrNull?.launch(Dispatchers.Main) {
					// show debugger UI after pairing is successful
					wadbViewModel.setPairingState(WADBViewModel.PairingState.Paired)
					bottomSheetViewModel.setSheetState(
						sheetState = BottomSheetBehavior.STATE_EXPANDED,
						currentTab = BottomSheetViewModel.TAB_DEBUGGER,
					)
					debuggerViewModel.currentView = DebuggerFragment.VIEW_WADB_PAIRING
				}

				// pairing was successful, look for ADB connection port
				// use GlobalScope so that we can complete the connection even
				// when the fragment is destroyed
				GlobalScope.launch(context = Dispatchers.IO) {
					beginShizukuConnection()
				}
			}

			AdbPairingService.ACTION_PAIR_FAILED -> {
				wadbViewModel.setPairingState(WADBViewModel.PairingState.NotPaired)
				flashError(getString(R.string.notification_adb_pairing_failed_title))
			}

			else -> {
				Unit
			}
		}

	/**
	 * Called in the global scope. Be extra careful when access fragment's
	 * instance properties or states.
	 */
	@DelicateAdbApi
	private suspend fun beginShizukuConnection() {
		var retryCount = 0
		while (retryCount < CONNECTION_RETRY_COUNT) {
			logger.debug("Finding ADB connection port (try {})", retryCount)
			viewLifecycleScopeOrNull?.launch {
				wadbViewModel.setPairingState(WADBViewModel.PairingState.Connecting)
				wadbViewModel.setConnectionStatus(
					getString(
						R.string.adb_connection_finding,
						retryCount + 1,
						CONNECTION_RETRY_COUNT,
					),
				)
			}

			// restart ADB connection finder
			adbMdnsConnector.restart()

			// once started, wait for it to connect
			delay(CONNECTION_RETRY_DELAY_MS)

			if (Shizuku.pingBinder()) {
				// connected successfully
				break
			}

			// connection failed, retry if needed
			retryCount++
		}

		if (!Shizuku.pingBinder()) {
			logger.error("Failed to connect to ADB server")
			viewLifecycleScopeOrNull?.launch {
				wadbViewModel.setPairingState(WADBViewModel.PairingState.NotPaired)
				wadbViewModel.setConnectionStatus(getString(R.string.adb_connection_failed))
			}
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
	 * be destroyed at any time. Use [viewLifecycleScopeOrNull] to launch
	 * coroutines in the fragment's view lifecycle. [viewLifecycleScopeOrNull]
	 * returns null if the fragment's view has already been destroyed or not
	 * yet created.
	 */
	@OptIn(DelicateCoroutinesApi::class)
	@DelicateAdbApi
	private fun onFindAdbConnectionPort(port: Int) =
		GlobalScope.launch(Dispatchers.IO) {
			logger.debug(
				"onFindAdbConnectionPort: port={}, isFragmentAlive={}",
				port,
				viewLifecycleScopeOrNull != null,
			)

			if (Shizuku.pingBinder()) {
				logger.debug("Shizuku service is already running")
				runCatching { adbMdnsConnector.stop() }
				return@launch
			}

			viewLifecycleScopeOrNull?.launch {
				wadbViewModel.setConnectionStatus(
					getString(
						R.string.adb_connection_connecting,
						port,
					),
				)
			}

			val key =
				runCatching {
					AdbKey(
						adbKeyStore =
							PreferenceAdbKeyStore(
								preference = ShizukuSettings.getPreferences(),
							),
					)
				}

			if (key.isFailure) {
				logger.error("Failed to get ADB key", key.exceptionOrNull())
				viewLifecycleScopeOrNull?.launch {
					wadbViewModel.setConnectionStatus(
						getString(
							R.string.adb_connection_failed,
							key.exceptionOrNull()?.message ?: "<unknown error>",
						),
					)
				}
				return@launch
			}

			val state = WADBViewModel.ConnectionState()

			val host = "127.0.0.1"
			AdbClient(host = host, port = port, key = key.getOrThrow())
				.runCatching {
					connect()
					shellCommand(ShizukuStarter.internalCommand) { outputBytes ->
						val output = String(outputBytes)
						logger.debug("[shizuku_starter] {}", output)

						state.appendOutput(output)
						onUpdateConnectionState(state)
					}
				}.onFailure { error ->
					val isCertificateError = error.message?.let { message ->
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

					logger.error("Failed to connect to ADB server", error)
					viewLifecycleScopeOrNull?.launch {
						wadbViewModel.setConnectionStatus(
							getString(
								R.string.adb_connection_failed,
								error.message ?: "<unknown error>",
							),
						)

						state.recordConnectionFailure(error)
						onUpdateConnectionState(state)
					}

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
	@DelicateAdbApi
	private fun onUpdateConnectionState(state: WADBViewModel.ConnectionState) {
		if (Shizuku.pingBinder()) {
			// already connected
			// reset state
			debuggerViewModel.currentView = DebuggerFragment.VIEW_DEBUGGER
			return
		}

		val output = state.output.toString().trim()
		if (output.endsWith("info: shizuku_starter exit with 0")) {
			state.clearOutput()

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

						viewLifecycleScopeOrNull?.launch {
							wadbViewModel.setPairingState(WADBViewModel.PairingState.Connected)
							debuggerViewModel.currentView = DebuggerFragment.VIEW_DEBUGGER
						}
					}
				},
			)
		} else if (state.error != null) {
			logger.error("Failed to start Shizuku starter", state.error)
			var message = 0
			when (state.error) {
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

	override fun onDestroyView() {
		super.onDestroyView()
		logger.debug("onDestroy")
		runCatching { adbMdnsConnector.stop() }
		_binding = null
	}
}

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class DelicateAdbApi
