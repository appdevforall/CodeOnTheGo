package com.itsaky.androidide.ui.models

import android.net.Uri
import android.os.Parcelable
import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.PluginMetadata
import kotlinx.parcelize.Parcelize
import java.io.File

data class PluginManagerUiState(
	val isLoading: Boolean = false,
	val plugins: List<PluginInfo> = emptyList(),
	val isPluginManagerAvailable: Boolean = false,
	val isInstalling: Boolean = false,
) {
	val isEmpty: Boolean
		get() = plugins.isEmpty() && !isLoading

	val showEmptyState: Boolean
		get() = isEmpty && isPluginManagerAvailable
}

/**
 * Where a plugin archive to install comes from - either a `content://` [Uri] the user picked via
 * SAF (any provider, including third-party ones), or a plain [File] this process already owns
 * (the forwarded-`.cgp` case from [com.itsaky.androidide.activities.ExternalFileInstallActivity],
 * which needs no [android.content.ContentResolver] round-trip since it's already a private file).
 *
 * [Parcelable] so the Compose install-confirmation dialog can hold one in `rememberSaveable` and
 * survive rotation. [Uri] is Parcelable outright; [File] is [java.io.Serializable], which
 * `@Parcelize` writes via `writeSerializable`.
 */
sealed class PluginInstallSource : Parcelable {
	@Parcelize
	data class ContentUri(
		val uri: Uri,
	) : PluginInstallSource()

	@Parcelize
	data class LocalFile(
		val file: File,
	) : PluginInstallSource()
}

sealed class PluginManagerUiEvent {
	object LoadPlugins : PluginManagerUiEvent()

	data class EnablePlugin(
		val pluginId: String,
	) : PluginManagerUiEvent()

	data class DisablePlugin(
		val pluginId: String,
	) : PluginManagerUiEvent()

	data class UninstallPlugin(
		val pluginId: String,
	) : PluginManagerUiEvent()

	data class InstallPlugin(
		val source: PluginInstallSource,
		val deleteSourceAfterInstall: Boolean,
	) : PluginManagerUiEvent()

	data class ConfirmOverwrite(
		val source: PluginInstallSource,
		val deleteSourceAfterInstall: Boolean,
	) : PluginManagerUiEvent()

	data class CancelPendingInstall(
		val source: PluginInstallSource,
	) : PluginManagerUiEvent()

	/**
	 * The SAF picker returned a `.cgp` document. Always a `content://` [Uri] - the forwarded-file
	 * entry point goes straight to [PluginManagerUiEffect.ShowInstallConfirmation] with a
	 * [PluginInstallSource.LocalFile] instead, since it needs no picker round-trip.
	 */
	data class FileSelected(
		val uri: Uri,
	) : PluginManagerUiEvent()

	data class ShowPluginDetails(
		val plugin: PluginInfo,
	) : PluginManagerUiEvent()
}

sealed class PluginManagerUiEffect {
	data class ShowError(
		@StringRes val messageResId: Int,
		val formatArgs: List<Any> = emptyList(),
	) : PluginManagerUiEffect()

	data class ShowSuccess(
		@StringRes val messageResId: Int,
	) : PluginManagerUiEffect()

	data class ShowPluginDetails(
		val plugin: PluginInfo,
	) : PluginManagerUiEffect()

	/**
	 * Carries a [PluginInstallSource], not a bare [Uri], so both entry points share one dialog:
	 * the SAF pick (a [PluginInstallSource.ContentUri]) and a `.cgp` forwarded from
	 * [com.itsaky.androidide.activities.ExternalFileInstallActivity] (a
	 * [PluginInstallSource.LocalFile]).
	 */
	data class ShowInstallConfirmation(
		val source: PluginInstallSource,
	) : PluginManagerUiEffect()

	data class ShowUninstallConfirmation(
		val plugin: PluginInfo,
	) : PluginManagerUiEffect()

	object ShowRestartPrompt : PluginManagerUiEffect()

	data class ShowOverwriteConfirmation(
		val existing: PluginInfo,
		val incomingMetadata: PluginMetadata,
		val source: PluginInstallSource,
		val deleteSourceAfterInstall: Boolean,
	) : PluginManagerUiEffect()
}

sealed class PluginOperation {
	object None : PluginOperation()

	object Loading : PluginOperation()

	object Installing : PluginOperation()

	data class Enabling(
		val pluginId: String,
	) : PluginOperation()

	data class Disabling(
		val pluginId: String,
	) : PluginOperation()

	data class Uninstalling(
		val pluginId: String,
	) : PluginOperation()
}
