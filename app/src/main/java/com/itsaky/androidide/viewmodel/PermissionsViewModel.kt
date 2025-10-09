
package com.itsaky.androidide.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.assets.AssetsInstallationHelper
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.withStopWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.itsaky.androidide.viewmodel.PermissionsState.PermissionsGranted
import com.itsaky.androidide.viewmodel.PermissionsState.Installing
import com.itsaky.androidide.viewmodel.PermissionsState.PermissionsPending
import com.itsaky.androidide.viewmodel.PermissionsState.InstallationComplete
import com.itsaky.androidide.viewmodel.PermissionsState.InstallationError
import org.slf4j.LoggerFactory

class PermissionsViewModel : ViewModel() {

    private val log = LoggerFactory.getLogger(PermissionsViewModel::class.java)

    private val _state = MutableStateFlow<PermissionsState>(PermissionsPending)
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    private val _installationProgress = MutableStateFlow<String>("")
    val installationProgress: StateFlow<String> = _installationProgress.asStateFlow()

    fun onPermissionsUpdated(allGranted: Boolean) {
        if (allGranted && _state.value is PermissionsPending) {
            _state.value =PermissionsGranted
        } else if (!allGranted && _state.value is PermissionsGranted) {
            _state.value =PermissionsPending
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
                                // Update progress on main thread
                                viewModelScope.launch(Dispatchers.Main) {
                                    _installationProgress.value = progress.message
                                }
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
                                _state.value =InstallationError(
                                    result.cause?.message ?: "Installation failed"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("IDE setup installation failed", e)
                    _state.value =InstallationError(
                        e.message ?: "An unknown error occurred"
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