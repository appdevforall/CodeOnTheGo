
package com.itsaky.androidide.viewmodel

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.assets.AssetsInstallationHelper
import com.itsaky.androidide.events.InstallationEvent
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.bytesToGigabytes
import com.itsaky.androidide.utils.gigabytesToBytes
import com.itsaky.androidide.utils.withStopWatch
import com.itsaky.androidide.viewmodel.InstallationState.InstallationComplete
import com.itsaky.androidide.viewmodel.InstallationState.InstallationError
import com.itsaky.androidide.viewmodel.InstallationState.InstallationGranted
import com.itsaky.androidide.viewmodel.InstallationState.InstallationPending
import com.itsaky.androidide.viewmodel.InstallationState.Installing
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class InstallationViewModel : ViewModel() {
	companion object {
		const val LEAST_STORAGE_NEEDED_FOR_INSTALLATION = 40L
	}

	private val log = LoggerFactory.getLogger(InstallationViewModel::class.java)

	private val _state = MutableStateFlow<InstallationState>(InstallationPending)
	val state: StateFlow<InstallationState> = _state.asStateFlow()

	private val _installationProgress = MutableStateFlow("")
	val installationProgress: StateFlow<String> = _installationProgress.asStateFlow()

	private val _events = MutableSharedFlow<InstallationEvent>()
	val events = _events.asSharedFlow()

	fun onPermissionsUpdated(allGranted: Boolean) {
		if (allGranted && _state.value is InstallationPending) {
			_state.update { InstallationGranted }
		} else if (!allGranted && _state.value is InstallationGranted) {
			_state.update { InstallationPending }
		}
	}

	fun startIdeSetup(context: Context) {
		if (_state.value is Installing) {
			log.warn("IDE setup is already in progress. Ignoring new request.")
			return
		}

		if (!checkToolsIsInstalled()) {
			viewModelScope.launch {
				try {
					_state.update { Installing() }

					withContext(Dispatchers.IO) {
						val result =
							withStopWatch("Assets installation") {
								AssetsInstallationHelper.install(context) { progress ->
									log.debug("Assets installation progress: {}", progress.message)
									_installationProgress.value = progress.message
								}
							}

						log.info("Assets installation result: {}", result)

						when (result) {
							is AssetsInstallationHelper.Result.Success -> {
								// Reload JDK distributions after installation
								val distributionProvider = IJdkDistributionProvider.getInstance()
								distributionProvider.loadDistributions()

								_state.update { InstallationComplete }
							}
							is AssetsInstallationHelper.Result.Failure -> {
								result.cause?.let { Sentry.captureException(it) }
								_state.update {
									InstallationError(R.string.title_installation_failed)
								}
							}
						}
					}
				} catch (e: Exception) {
					Sentry.captureException(e)
					log.error("IDE setup installation failed", e)
					_state.update {
						InstallationError(R.string.unknown_error)
					}
				}
			}
		} else {
			// Tools already installed
			_state.update { InstallationComplete }
		}
	}

	fun isSetupComplete(): Boolean = checkToolsIsInstalled()

	private fun checkToolsIsInstalled(): Boolean =
		IJdkDistributionProvider.getInstance().installedDistributions.isNotEmpty() &&
			Environment.ANDROID_HOME.exists()

	private fun deviceHasLowStorage(context: Context): Pair<Boolean, Float> {
		val stat = StatFs(context.filesDir.path)

		val availableStorageInBytes = stat.availableBlocksLong * stat.blockSizeLong
		val requiredStorageInBytes = LEAST_STORAGE_NEEDED_FOR_INSTALLATION.gigabytesToBytes() // 4GB
		val additionalBytesNeeded = (requiredStorageInBytes - availableStorageInBytes).coerceAtLeast(0F)

		val isLowStorage = availableStorageInBytes < requiredStorageInBytes

		return Pair(isLowStorage, additionalBytesNeeded)
	}

	fun checkStorageAndNotify(context: Context): Boolean {
		val (isLowStorage, additionalBytesNeeded) = deviceHasLowStorage(context)

		if (isLowStorage) {
			viewModelScope.launch {
				val errorMessage =
					context.getString(R.string.not_enough_storage, additionalBytesNeeded.bytesToGigabytes())
				_events.emit(InstallationEvent.ShowError(errorMessage))
			}
			return false
		}
		return true
	}
}
