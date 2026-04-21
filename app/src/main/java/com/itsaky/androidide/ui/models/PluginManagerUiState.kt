package com.itsaky.androidide.ui.models

import android.net.Uri
import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.PluginMetadata

data class PluginManagerUiState(
    val isLoading: Boolean = false,
    val plugins: List<PluginInfo> = emptyList(),
    val isPluginManagerAvailable: Boolean = false,
    val isInstalling: Boolean = false
) {
    val isEmpty: Boolean
        get() = plugins.isEmpty() && !isLoading

    val showEmptyState: Boolean
        get() = isEmpty && isPluginManagerAvailable
}

sealed class PluginManagerUiEvent {
    object LoadPlugins : PluginManagerUiEvent()
    data class EnablePlugin(val pluginId: String) : PluginManagerUiEvent()
    data class DisablePlugin(val pluginId: String) : PluginManagerUiEvent()
    data class UninstallPlugin(val pluginId: String) : PluginManagerUiEvent()
    data class InstallPlugin(val uri: Uri, val deleteSourceAfterInstall: Boolean) : PluginManagerUiEvent()
    data class ConfirmOverwrite(val uri: Uri, val deleteSourceAfterInstall: Boolean) : PluginManagerUiEvent()
    object OpenFilePicker : PluginManagerUiEvent()
    data class ShowPluginDetails(val plugin: PluginInfo) : PluginManagerUiEvent()
}

sealed class PluginManagerUiEffect {
    data class ShowError(@StringRes val messageResId: Int, val formatArgs: List<Any> = emptyList()) : PluginManagerUiEffect()
    data class ShowSuccess(@StringRes val messageResId: Int) : PluginManagerUiEffect()
    data class ShowPluginDetails(val plugin: PluginInfo) : PluginManagerUiEffect()
    object OpenFilePicker : PluginManagerUiEffect()
    data class ShowUninstallConfirmation(val plugin: PluginInfo) : PluginManagerUiEffect()
    object ShowRestartPrompt : PluginManagerUiEffect()
    data class ShowOverwriteConfirmation(
        val existing: PluginInfo,
        val incomingMetadata: PluginMetadata,
        val uri: Uri,
        val deleteSourceAfterInstall: Boolean
    ) : PluginManagerUiEffect()
}

sealed class PluginOperation {
    object None : PluginOperation()
    object Loading : PluginOperation()
    object Installing : PluginOperation()
    data class Enabling(val pluginId: String) : PluginOperation()
    data class Disabling(val pluginId: String) : PluginOperation()
    data class Uninstalling(val pluginId: String) : PluginOperation()
}