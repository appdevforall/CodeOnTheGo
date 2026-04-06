package com.itsaky.androidide.viewmodels

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.ui.models.PluginManagerUiState
import com.itsaky.androidide.ui.models.PluginOperation
import com.itsaky.androidide.utils.UriFileImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for the Plugin Manager screen
 * Manages UI state and business logic using MVVM pattern
 */
class PluginManagerViewModel(
    private val pluginRepository: PluginRepository,
    private val contentResolver: ContentResolver,
    private val filesDir: File
) : ViewModel() {

    private companion object {
        private const val TAG = "PluginManagerViewModel"
    }

    // Mutable state for internal updates
    private val _uiState = MutableStateFlow(
        PluginManagerUiState(
            isPluginManagerAvailable = pluginRepository.isPluginManagerAvailable()
        )
    )

    // Public read-only state
    val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()

    // Channel for one-time UI effects
    private val _uiEffect = Channel<PluginManagerUiEffect>()
    val uiEffect = _uiEffect.receiveAsFlow()

    // Current operation tracking
    private val _currentOperation = MutableStateFlow<PluginOperation>(PluginOperation.None)
    val currentOperation: StateFlow<PluginOperation> = _currentOperation.asStateFlow()

    init {
        loadPlugins()
    }

    /**
     * Handle UI events
     */
    fun onEvent(event: PluginManagerUiEvent) {
        when (event) {
            is PluginManagerUiEvent.LoadPlugins -> loadPlugins()
            is PluginManagerUiEvent.EnablePlugin -> enablePlugin(event.pluginId)
            is PluginManagerUiEvent.DisablePlugin -> disablePlugin(event.pluginId)
            is PluginManagerUiEvent.UninstallPlugin -> showUninstallConfirmation(event.pluginId)
            is PluginManagerUiEvent.InstallPlugin -> installPlugin(
                event.uri,
                event.deleteSourceAfterInstall
            )

            is PluginManagerUiEvent.OpenFilePicker -> openFilePicker()
            is PluginManagerUiEvent.ShowPluginDetails -> showPluginDetails(event.plugin)
        }
    }

    /**
     * Load all plugins
     */
    private fun loadPlugins() {
        if (!pluginRepository.isPluginManagerAvailable()) {
            _uiState.update { it.copy(isPluginManagerAvailable = false) }
            return
        }

        viewModelScope.launch {
            _currentOperation.value = PluginOperation.Loading
            _uiState.update { it.copy(isLoading = true) }

            pluginRepository.getAllPlugins()
                .onSuccess { plugins ->
                    Log.d(TAG, "Loaded ${plugins.size} plugins")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            plugins = plugins,
                            isPluginManagerAvailable = true
                        )
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Failed to load plugins", exception)
                    _uiState.update {
                        it.copy(isLoading = false)
                    }
                    _uiEffect.trySend(
                        PluginManagerUiEffect.ShowError(
                            R.string.msg_plugin_load_failed,
                            listOf(exception.message ?: "")
                        )
                    )
                }

            _currentOperation.value = PluginOperation.None
        }
    }

    /**
     * Enable a plugin
     */
    private fun enablePlugin(pluginId: String) {
        viewModelScope.launch {
            _currentOperation.value = PluginOperation.Enabling(pluginId)

            pluginRepository.enablePlugin(pluginId)
                .onSuccess { success ->
                    if (success) {
                        Log.d(TAG, "Plugin enabled successfully: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_enabled))
                        loadPlugins()
                    } else {
                        Log.w(TAG, "Failed to enable plugin: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError(R.string.msg_plugin_enable_failed))
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Error enabling plugin: $pluginId", exception)
                    _uiEffect.trySend(
                        PluginManagerUiEffect.ShowError(
                            R.string.msg_plugin_enable_error,
                            listOf(exception.message ?: "")
                        )
                    )
                }

            _currentOperation.value = PluginOperation.None
        }
    }

    /**
     * Disable a plugin
     */
    private fun disablePlugin(pluginId: String) {
        viewModelScope.launch {
            _currentOperation.value = PluginOperation.Disabling(pluginId)

            pluginRepository.disablePlugin(pluginId)
                .onSuccess { success ->
                    if (success) {
                        Log.d(TAG, "Plugin disabled successfully: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_disabled))
                        loadPlugins()
                    } else {
                        Log.w(TAG, "Failed to disable plugin: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError(R.string.msg_plugin_disable_failed))
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Error disabling plugin: $pluginId", exception)
                    _uiEffect.trySend(
                        PluginManagerUiEffect.ShowError(
                            R.string.msg_plugin_disable_error,
                            listOf(exception.message ?: "")
                        )
                    )
                }

            _currentOperation.value = PluginOperation.None
        }
    }

    /**
     * Show uninstall confirmation dialog
     */
    private fun showUninstallConfirmation(pluginId: String) {
        val plugin = _uiState.value.plugins.find { it.metadata.id == pluginId }
        if (plugin != null) {
            viewModelScope.launch {
                _uiEffect.trySend(PluginManagerUiEffect.ShowUninstallConfirmation(plugin))
            }
        }
    }

    /**
     * Uninstall a plugin (called after confirmation)
     */
    fun confirmUninstallPlugin(pluginId: String) {
        viewModelScope.launch {
            _currentOperation.value = PluginOperation.Uninstalling(pluginId)

            pluginRepository.uninstallPlugin(pluginId)
                .onSuccess { success ->
                    if (success) {
                        Log.d(TAG, "Plugin uninstalled successfully: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_uninstalled))
                        loadPlugins()
                        _uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)
                    } else {
                        Log.w(TAG, "Failed to uninstall plugin: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError(R.string.msg_plugin_uninstall_failed))
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Error uninstalling plugin: $pluginId", exception)
                    _uiEffect.trySend(
                        PluginManagerUiEffect.ShowError(
                            R.string.msg_plugin_uninstall_error,
                            listOf(exception.message ?: "")
                        )
                    )
                }

            _currentOperation.value = PluginOperation.None
        }
    }

    /**
     * Install a plugin from URI
     */
    private fun installPlugin(uri: Uri, deleteSourceAfterInstall: Boolean) {
        viewModelScope.launch {
            _currentOperation.value = PluginOperation.Installing
            _uiState.update { it.copy(isInstalling = true) }

            var tempFile: File? = null

            try {
                tempFile = withContext(Dispatchers.IO) {
                    val fileName = UriFileImporter.getDisplayName(contentResolver, uri)
                    val extension = if (fileName?.endsWith(
                            ".cgp",
                            ignoreCase = true
                        ) == true
                    ) ".cgp" else ".apk"
                    val tempFileName = "temp_plugin_${System.currentTimeMillis()}$extension"
                    val tempDir = File(filesDir, "temp").apply { mkdirs() }
                    val tempFile = File(tempDir, tempFileName)

                    UriFileImporter.copyUriToFile(contentResolver, uri, tempFile) {
                        Exception("Cannot open file")
                    }
                    tempFile
                }

                pluginRepository.installPluginFromFile(tempFile)
                    .onSuccess {
                        Log.d(TAG, "Plugin installed successfully")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_installed))
                        loadPlugins()
                        _uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)

                        if (deleteSourceAfterInstall) {
                            deleteSourceDocument(uri)
                        }
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "Failed to install plugin", exception)
                        _uiEffect.trySend(
                            PluginManagerUiEffect.ShowError(
                                R.string.msg_plugin_install_failed,
                                listOf(exception.message ?: "")
                            )
                        )
                    }
            } catch (exception: Exception) {
                Log.e(TAG, "Error installing plugin from URI", exception)
                _uiEffect.trySend(
                    PluginManagerUiEffect.ShowError(
                        R.string.msg_plugin_install_failed,
                        listOf(exception.message ?: "")
                    )
                )
            } finally {
                tempFile?.let { file ->
                    withContext(Dispatchers.IO) {
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }
            }

            _uiState.update { it.copy(isInstalling = false) }
            _currentOperation.value = PluginOperation.None
        }
    }

    private suspend fun deleteSourceDocument(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val deleted = DocumentsContract.deleteDocument(contentResolver, uri)
                if (!deleted) {
                    _uiEffect.trySend(
                        PluginManagerUiEffect.ShowError(R.string.msg_source_delete_failed)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete source document", e)
                _uiEffect.trySend(
                    PluginManagerUiEffect.ShowError(R.string.msg_source_delete_failed)
                )
            }
        }
    }

    /**
     * Open file picker
     */
    private fun openFilePicker() {
        viewModelScope.launch {
            _uiEffect.trySend(PluginManagerUiEffect.OpenFilePicker)
        }
    }

    /**
     * Show plugin details
     */
    private fun showPluginDetails(plugin: PluginInfo) {
        viewModelScope.launch {
            _uiEffect.trySend(PluginManagerUiEffect.ShowPluginDetails(plugin))
        }
    }

    /**
     * Check if a specific plugin operation is in progress
     */
    fun isPluginOperationInProgress(pluginId: String): Boolean {
        return when (val operation = _currentOperation.value) {
            is PluginOperation.Enabling -> operation.pluginId == pluginId
            is PluginOperation.Disabling -> operation.pluginId == pluginId
            is PluginOperation.Uninstalling -> operation.pluginId == pluginId
            else -> false
        }
    }

}
