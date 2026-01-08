package com.itsaky.androidide.fragments.debug

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.RequiresApi
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentWadbConnectionBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.WADBConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Fragment to request wireless ADB permissions.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WADBPermissionFragment :
	FragmentWithBinding<FragmentWadbConnectionBinding>(FragmentWadbConnectionBinding::inflate) {
	companion object {

		private val logger = LoggerFactory.getLogger(WADBPermissionFragment::class.java)

		fun newInstance() = WADBPermissionFragment()
	}

	// must be activity bound since it's also used in DebuggerFragment
	private val wadbConnection by activityViewModels<WADBConnectionViewModel>()

	private suspend fun setConnectionStatus(status: String) =
		withContext(Dispatchers.Main.immediate) {
			binding.statusText.text = status
		}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		viewLifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					wadbConnection.status.collectLatest { status ->
						val status = when (status) {
							is WADBConnectionViewModel.ConnectionStatus.SearchingConnectionPort -> {
								getString(
									R.string.adb_connection_finding,
									status.retryCount + 1,
									status.maxRetries,
								)
							}

							WADBConnectionViewModel.ConnectionStatus.ConnectionPortNotFound -> {
								getString(R.string.adb_connection_failed)
							}

							is WADBConnectionViewModel.ConnectionStatus.Connecting -> {
								getString(
									R.string.adb_connection_connecting,
									status.port,
								)
							}

							is WADBConnectionViewModel.ConnectionStatus.ConnectionFailed -> {
								getString(
									R.string.adb_connection_failed,
									status.error?.message ?: "<unknown error>",
								)
							}

							else -> null
						}

						status?.also { s -> setConnectionStatus(s) }
					}
				}
			}
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		logger.debug("onDestroy")
		_binding = null
	}
}
