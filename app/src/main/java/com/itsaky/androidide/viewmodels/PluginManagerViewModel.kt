package com.itsaky.androidide.viewmodels

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.ui.models.PluginManagerUiState
import com.itsaky.androidide.ui.models.PluginOperation
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
import java.io.FileOutputStream

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
            is PluginManagerUiEvent.InstallPlugin -> installPlugin(event.uri)
            is PluginManagerUiEvent.OpenFilePicker -> openFilePicker()
            is PluginManagerUiEvent.ShowPluginDetails -> showPluginDetails(event.plugin)
            is PluginManagerUiEvent.ClearMessages -> clearMessages()
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
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            pluginRepository.getAllPlugins()
                .onSuccess { plugins ->
                    Log.d(TAG, "Loaded ${plugins.size} plugins")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            plugins = plugins,
                            isPluginManagerAvailable = true,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Failed to load plugins", exception)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load plugins: ${exception.message}"
                        )
                    }
                    _uiEffect.trySend(PluginManagerUiEffect.ShowError("Failed to load plugins: ${exception.message}"))
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
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess("Plugin enabled"))
                        loadPlugins() // Refresh the list
                        // Show restart prompt to apply changes
                        _uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)
                    } else {
                        Log.w(TAG, "Failed to enable plugin: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError("Failed to enable plugin"))
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Error enabling plugin: $pluginId", exception)
                    _uiEffect.trySend(PluginManagerUiEffect.ShowError("Error enabling plugin: ${exception.message}"))
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
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess("Plugin disabled"))
                        loadPlugins() // Refresh the list
                        // Show restart prompt to apply changes
                        _uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)
                    } else {
                        Log.w(TAG, "Failed to disable plugin: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError("Failed to disable plugin"))
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Error disabling plugin: $pluginId", exception)
                    _uiEffect.trySend(PluginManagerUiEffect.ShowError("Error disabling plugin: ${exception.message}"))
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
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess("Plugin uninstalled successfully"))
                        loadPlugins() // Refresh the list
                        // Show restart prompt to apply changes
                        _uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)
                    } else {
                        Log.w(TAG, "Failed to uninstall plugin: $pluginId")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError("Failed to uninstall plugin"))
                    }
                }
                .onFailure { exception ->
                    Log.e(TAG, "Error uninstalling plugin: $pluginId", exception)
                    _uiEffect.trySend(PluginManagerUiEffect.ShowError("Error uninstalling plugin: ${exception.message}"))
                }

            _currentOperation.value = PluginOperation.None
        }
    }

    /**
     * Install a plugin from URI
     */
    private fun installPlugin(uri: Uri) {
        viewModelScope.launch {
            _currentOperation.value = PluginOperation.Installing
            _uiState.update { it.copy(isInstalling = true) }

            var tempFile: File? = null

            try {
                // Move all file I/O to IO dispatcher
                tempFile = withContext(Dispatchers.IO) {
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open file")

                    // Create temporary file in cache directory (not plugins directory)
                    // Get the actual filename from the URI
                    val fileName = getFileNameFromUri(uri)
                    val extension = if (fileName?.endsWith(".cgp", ignoreCase = true) == true) ".cgp" else ".apk"
                    val tempFileName = "temp_plugin_${System.currentTimeMillis()}$extension"
                    val tempDir = File(filesDir, "temp").apply { mkdirs() }
                    val tempFile = File(tempDir, tempFileName)

                    // Copy file content
                    FileOutputStream(tempFile).use { output ->
                        inputStream.use { input ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                }

                // Install using repository
                pluginRepository.installPluginFromFile(tempFile)
                    .onSuccess {
                        Log.d(TAG, "Plugin installed successfully")
                        _uiEffect.trySend(PluginManagerUiEffect.ShowSuccess("Plugin installed successfully"))
                        loadPlugins() // Refresh the list
                        // Show restart prompt to apply changes
                        _uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "Failed to install plugin", exception)
                        _uiEffect.trySend(PluginManagerUiEffect.ShowError("Failed to install plugin: ${exception.message}"))
                    }

            } catch (exception: Exception) {
                Log.e(TAG, "Error installing plugin from URI", exception)
                _uiEffect.trySend(PluginManagerUiEffect.ShowError("Failed to install plugin: ${exception.message}"))
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
     * Clear success/error messages
     */
    private fun clearMessages() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                successMessage = null
            )
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

    /**
     * Get the actual filename from a content URI
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "content" -> {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            cursor.getString(nameIndex)
                        } else {
                            null
                        }
                    }
                }
                "file" -> uri.lastPathSegment
                else -> uri.lastPathSegment
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting filename from URI", e)
            null
        }
    }
}