package com.itsaky.androidide.ui.models

import com.itsaky.androidide.plugins.PluginInfo

/**
 * Represents the UI state for the Plugin Manager screen
 */
data class PluginManagerUiState(
    val isLoading: Boolean = false,
    val plugins: List<PluginInfo> = emptyList(),
    val isPluginManagerAvailable: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isInstalling: Boolean = false
) {
    val isEmpty: Boolean
        get() = plugins.isEmpty() && !isLoading

    val showEmptyState: Boolean
        get() = isEmpty && isPluginManagerAvailable && errorMessage == null
}

/**
 * Represents different UI events that can occur
 */
sealed class PluginManagerUiEvent {
    object LoadPlugins : PluginManagerUiEvent()
    data class EnablePlugin(val pluginId: String) : PluginManagerUiEvent()
    data class DisablePlugin(val pluginId: String) : PluginManagerUiEvent()
    data class UninstallPlugin(val pluginId: String) : PluginManagerUiEvent()
    data class InstallPlugin(val uri: android.net.Uri) : PluginManagerUiEvent()
    object OpenFilePicker : PluginManagerUiEvent()
    data class ShowPluginDetails(val plugin: PluginInfo) : PluginManagerUiEvent()
    object ClearMessages : PluginManagerUiEvent()
}

/**
 * Represents one-time UI effects
 */
sealed class PluginManagerUiEffect {
    data class ShowError(val message: String) : PluginManagerUiEffect()
    data class ShowSuccess(val message: String) : PluginManagerUiEffect()
    data class ShowPluginDetails(val plugin: PluginInfo) : PluginManagerUiEffect()
    object OpenFilePicker : PluginManagerUiEffect()
    data class ShowUninstallConfirmation(val plugin: PluginInfo) : PluginManagerUiEffect()
    object ShowRestartPrompt : PluginManagerUiEffect()
}

/**
 * Represents the current operation being performed
 */
sealed class PluginOperation {
    object None : PluginOperation()
    object Loading : PluginOperation()
    object Installing : PluginOperation()
    data class Enabling(val pluginId: String) : PluginOperation()
    data class Disabling(val pluginId: String) : PluginOperation()
    data class Uninstalling(val pluginId: String) : PluginOperation()
}