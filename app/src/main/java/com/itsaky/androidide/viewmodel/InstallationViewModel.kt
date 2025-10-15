
package com.itsaky.androidide.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.assets.AssetsInstallationHelper
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.withStopWatch
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.itsaky.androidide.viewmodel.InstallationState.InstallationGranted
import com.itsaky.androidide.viewmodel.InstallationState.Installing
import com.itsaky.androidide.viewmodel.InstallationState.InstallationPending
import com.itsaky.androidide.viewmodel.InstallationState.InstallationComplete
import com.itsaky.androidide.viewmodel.InstallationState.InstallationError
import org.slf4j.LoggerFactory

class InstallationViewModel : ViewModel() {

    private val log = LoggerFactory.getLogger(InstallationViewModel::class.java)

    private val _state = MutableStateFlow<InstallationState>(InstallationPending)
    val state: StateFlow<InstallationState> = _state.asStateFlow()

    private val _installationProgress = MutableStateFlow("")
    val installationProgress: StateFlow<String> = _installationProgress.asStateFlow()
    fun onPermissionsUpdated(allGranted: Boolean) {
        if (allGranted && _state.value is InstallationPending) {
            _state.value =InstallationGranted
        } else if (!allGranted && _state.value is InstallationGranted) {
            _state.value =InstallationPending
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
                    _state.value =Installing()

                    withContext(Dispatchers.IO) {
                        val result = withStopWatch("Assets installation") {
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

                                _state.value =InstallationComplete
                            }
                            is AssetsInstallationHelper.Result.Failure -> {
                                result.cause?.let { Sentry.captureException(it) }
                                _state.value =InstallationError(
                                    R.string.title_installation_failed
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Sentry.captureException(e)
                    log.error("IDE setup installation faileid", e)
                    _state.value =InstallationError(
                        R.string.unknown_error
                    )
                }
            }
        } else {
            // Tools already installed
            _state.value =InstallationComplete
        }
    }

    fun isSetupComplete(): Boolean {
        return checkToolsIsInstalled()
    }

    private fun checkToolsIsInstalled(): Boolean {
        return IJdkDistributionProvider.getInstance().installedDistributions.isNotEmpty() &&
                Environment.ANDROID_HOME.exists()
    }
}