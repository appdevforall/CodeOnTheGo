package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.databinding.FragmentWabPermissionBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DeviceUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isAtLeastS
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import com.itsaky.androidide.viewmodel.WADBViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuStarter
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

/**
 * Fragment to request wireless ADB permissions.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WADBPermissionFragment : FragmentWithBinding<FragmentWabPermissionBinding>(FragmentWabPermissionBinding::inflate) {
	companion object {
		const val VIEW_PAIRING = 0
		const val VIEW_CONNECTING = 1

		private val logger = LoggerFactory.getLogger(WADBPermissionFragment::class.java)

		fun newInstance() = WADBPermissionFragment()
	}

	// must be activity bound since it's also used in DebuggerFragment
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
			// context =
			requireContext(),
			// receiver =
			pairingBroadcastReceiver,
			// filter =
			filter,
			// flags =
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		val isMiui = DeviceUtils.isMiui()
		val isNotificationEnabled = isNotificationEnabled()

		val activity = requireActivity()
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				wadbViewModel.connectionState.collectLatest { state ->
					onUpdateConnectionState(state)
				}
			}
		}

		viewLifecycleScope.launch {
			adbMdnsConnector.start()
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				wadbViewModel.currentView.collectLatest { currentView ->
					withContext(Dispatchers.Main.immediate) {
						binding.root.displayedChild = currentView
					}
				}

				wadbViewModel.connectionStatus.collectLatest { status ->
					withContext(Dispatchers.Main.immediate) {
						binding.connection.statusText.text = status
					}
				}
			}
		}

		binding.pairing.apply {
			miuiCard.isVisible = isNotificationEnabled && isMiui
			miuiAndWadbStepsCardSpacing.updateLayoutParams {
				height = height * (if (isNotificationEnabled && isMiui) 1 else 0)
			}

			onReloadNotificationSettings()

			actionOpenNotificationSettings.setOnClickListener {
				val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
				intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
				try {
					startActivity(intent)
				} catch (e: ActivityNotFoundException) {
					logger.error("Failed to open notification settings", e)
				}
			}

			actionOpenDeveloperOptions.setOnClickListener {
				val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
				intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
				intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
				try {
					startActivity(intent)
					startPairingService()
				} catch (e: ActivityNotFoundException) {
					logger.error("Failed to open developer options", e)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()
		onReloadNotificationSettings()
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun onPairResult(intent: Intent) =
		when (intent.action) {
			AdbPairingService.ACTION_PAIR_SUCCEEDED -> {
				// pairing was successful, look for ADB connection port
				// use GlobalScope so that we can complete the connection even
				// when the fragment is destroyed
				GlobalScope.launch(context = Dispatchers.IO) {
					// restart ADB connection finder in case it was already running
					adbMdnsConnector.restart()
				}

				viewLifecycleScope.launch(Dispatchers.Main) {
					wadbViewModel.setConnectionStatus(getString(R.string.adb_connection_finding))
					wadbViewModel.setCurrentView(VIEW_CONNECTING)
				}
			}

			AdbPairingService.ACTION_PAIR_FAILED ->
				flashError(getString(R.string.notification_adb_pairing_failed_title))

			else -> Unit
		}

	@OptIn(DelicateCoroutinesApi::class)
	private fun onFindAdbConnectionPort(port: Int) =
		GlobalScope.launch(Dispatchers.IO) {
			logger.debug("onFindAdbConnectionPort: {}", port)

			if (Shizuku.pingBinder()) {
				logger.debug("Shizuku service is already running")
				runCatching { adbMdnsConnector.stop() }
				return@launch
			}

			wadbViewModel.setConnectionStatus(getString(R.string.adb_connection_connecting, port))

			val key =
				runCatching {
					AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()))
				}

			if (key.isFailure) {
				logger.error("Failed to get ADB key", key.exceptionOrNull())
				wadbViewModel.setConnectionStatus(
					getString(
						R.string.adb_connection_failed,
						key.exceptionOrNull()?.message ?: "<unknown error>",
					),
				)
				return@launch
			}

			val host = "127.0.0.1"
			AdbClient(host, port, key.getOrThrow())
				.runCatching {
					connect()
					shellCommand(ShizukuStarter.internalCommand) { outputBytes ->
						val output = String(outputBytes)
						wadbViewModel.appendOutput(output)
						logger.debug("[shizuku_starter] {}", output)
					}
				}.onFailure { error ->
					logger.error("Failed to connect to ADB server", error)
					wadbViewModel.recordConnectionFailure(error)
					wadbViewModel.setConnectionStatus(
						getString(
							R.string.adb_connection_failed,
							error.message ?: "<unknown error>",
						),
					)

					// connection failed, no point in trying to find the port
					if (port != -1) {
						runCatching { adbMdnsConnector.stop() }
					}
				}
		}

	private fun isNotificationEnabled(): Boolean {
		val context = requireContext()
		val nm = context.getSystemService(NotificationManager::class.java)
		val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
		return nm.areNotificationsEnabled() &&
			(channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
	}

	private fun onReloadNotificationSettings() {
		val isNotificationEnabled = isNotificationEnabled()
		binding.pairing.apply {
			actionOpenNotificationSettings.isVisible = !isNotificationEnabled
			networkCard.isVisible = isNotificationEnabled
			wadbStepsCard.isVisible = isNotificationEnabled
		}
	}

	private fun startPairingService() {
		val context = requireContext()
		val intent = AdbPairingService.startIntent(context)
		try {
			startForegroundService(context, intent)
		} catch (e: Throwable) {
			logger.error("Failed to start pairing service", e)

			if (isAtLeastS() && e is ForegroundServiceStartNotAllowedException) {
				val mode =
					context
						.getSystemService(AppOpsManager::class.java)
						.noteOpNoThrow(
							"android:start_foreground",
							android.os.Process.myUid(),
							context.packageName,
							null,
							null,
						)
				if (mode == AppOpsManager.MODE_ERRORED) {
					flashError(getString(R.string.err_foreground_service_denial))
				}

				context.startService(intent)
			}
		}
	}

	private fun onUpdateConnectionState(state: WADBViewModel.ConnectionState) {
		if (Shizuku.pingBinder()) {
			// already connected
			// reset state
			wadbViewModel.setCurrentView(VIEW_PAIRING)
			debuggerViewModel.currentView = DebuggerFragment.VIEW_DEBUGGER
			return
		}

		if (isDetached) {
			return
		}

		val output = state.output.toString()
		logger.debug("shizuku_starter output: {}", output)
		if (output.endsWith("info: shizuku_starter exit with 0")) {
			logger.debug("Shizuku starter exited successfully")
			// starter was successful in starting the Shizuku service
			// get the binder to communicate with it
			Shizuku.addBinderReceivedListener(
				object : Shizuku.OnBinderReceivedListener {
					override fun onBinderReceived() {
						Shizuku.removeBinderReceivedListener(this)
						logger.debug("Shizuku service connected")

						// reset state
						wadbViewModel.setCurrentView(VIEW_PAIRING)
						debuggerViewModel.currentView = DebuggerFragment.VIEW_DEBUGGER
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
