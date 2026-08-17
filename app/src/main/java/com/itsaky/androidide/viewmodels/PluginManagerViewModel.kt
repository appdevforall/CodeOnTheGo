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
import com.itsaky.androidide.ui.models.PluginInstallSource
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.ui.models.PluginManagerUiState
import com.itsaky.androidide.ui.models.PluginOperation
import com.itsaky.androidide.utils.EditorDecorationBridge
import com.itsaky.androidide.utils.InstallTempFiles
import com.itsaky.androidide.utils.LastValueGate
import com.itsaky.androidide.utils.UriFileImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import java.io.File

/**
 * ViewModel for the Plugin Manager screen
 * Manages UI state and business logic using MVVM pattern
 */
class PluginManagerViewModel(
	private val pluginRepository: PluginRepository,
	private val contentResolver: ContentResolver,
	private val filesDir: File,
) : ViewModel() {
	private companion object {
		private const val TAG = "PluginManagerViewModel"
	}

	// Tracks the last forwarded-install file path (from ExternalFileInstallActivity) this
	// instance has already shown a dialog for. Survives rotation (same ViewModel instance, via
	// the ViewModelStore) so the dialog isn't re-popped on every rotation, but resets on process
	// death (a fresh instance is created), so a process-death-recreated PluginManagerActivity
	// still shows the dialog instead of silently dropping the forwarded install.
	private val pendingInstallGate = LastValueGate<String>()

	// Completed once the first loadPlugins() call (from init{}) has concluded, successfully or
	// not. resolveInstallConflict() awaits this before consulting _uiState.value.plugins, so an
	// install confirmed immediately after a cold start can't race the async plugin-list load and
	// skip the same-ID signature check by seeing an still-empty list.
	private val initialLoadCompleted = CompletableDeferred<Unit>()

	/** See [pendingInstallGate] for why this, rather than an Activity `savedInstanceState`
	 * check, is what correctly distinguishes "already shown after a rotation" from "never shown
	 * because the process died". */
	fun markPendingInstallHandled(filePath: String): Boolean = pendingInstallGate.consume(filePath)

	// Mutable state for internal updates
	private val _uiState =
		MutableStateFlow(
			PluginManagerUiState(
				isPluginManagerAvailable = pluginRepository.isPluginManagerAvailable(),
			),
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
			is PluginManagerUiEvent.LoadPlugins -> {
				loadPlugins()
			}

			is PluginManagerUiEvent.EnablePlugin -> {
				enablePlugin(event.pluginId)
			}

			is PluginManagerUiEvent.DisablePlugin -> {
				disablePlugin(event.pluginId)
			}

			is PluginManagerUiEvent.UninstallPlugin -> {
				showUninstallConfirmation(event.pluginId)
			}

			is PluginManagerUiEvent.InstallPlugin -> {
				installPlugin(
					event.source,
					event.deleteSourceAfterInstall,
				)
			}

			is PluginManagerUiEvent.ConfirmOverwrite -> {
				installPlugin(
					event.source,
					event.deleteSourceAfterInstall,
					checkConflict = false,
				)
			}

			is PluginManagerUiEvent.CancelPendingInstall -> {
				// Only a forwarded LocalFile (our own disposable temp copy) is cleaned up here -
				// nothing was installed, so a user-picked ContentUri source is never touched on
				// decline, regardless of deleteSourceAfterInstall (that flag only ever governs
				// deletion after a *successful* install, matching its "delete after install"
				// label).
				if (event.source is PluginInstallSource.LocalFile) {
					viewModelScope.launch { deleteInstallSource(event.source) }
				}
			}

			is PluginManagerUiEvent.OpenFilePicker -> {
				openFilePicker()
			}

			is PluginManagerUiEvent.ShowPluginDetails -> {
				showPluginDetails(event.plugin)
			}
		}
	}

	/**
	 * Load all plugins
	 */
	private fun loadPlugins() {
		if (!pluginRepository.isPluginManagerAvailable()) {
			_uiState.update { it.copy(isPluginManagerAvailable = false) }
			initialLoadCompleted.complete(Unit)
			return
		}

		viewModelScope.launch {
			_currentOperation.value = PluginOperation.Loading
			_uiState.update { it.copy(isLoading = true) }

			pluginRepository
				.getAllPlugins()
				.onSuccess { plugins ->
					Log.d(TAG, "Loaded ${plugins.size} plugins")
					_uiState.update {
						it.copy(
							isLoading = false,
							plugins = plugins,
							isPluginManagerAvailable = true,
						)
					}
				}.onFailure { exception ->
					Log.e(TAG, "Failed to load plugins", exception)
					_uiState.update {
						it.copy(isLoading = false)
					}
					_uiEffect.trySend(
						PluginManagerUiEffect.ShowError(
							R.string.msg_plugin_load_failed,
							listOf(exception.message ?: ""),
						),
					)
				}

			// Keep the editor decoration providers in sync with the enabled plugin set.
			EditorDecorationBridge.refresh()

			_currentOperation.value = PluginOperation.None
			// A no-op if already completed by an earlier loadPlugins() call - only the first
			// call's outcome matters for initialLoadCompleted's purpose.
			initialLoadCompleted.complete(Unit)
		}
	}

	/**
	 * Enable a plugin
	 */
	private fun enablePlugin(pluginId: String) {
		viewModelScope.launch {
			_currentOperation.value = PluginOperation.Enabling(pluginId)

			pluginRepository
				.enablePlugin(pluginId)
				.onSuccess { success ->
					if (success) {
						Log.d(TAG, "Plugin enabled successfully: $pluginId")
						_uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_enabled))
						loadPlugins()
					} else {
						Log.w(TAG, "Failed to enable plugin: $pluginId")
						_uiEffect.trySend(PluginManagerUiEffect.ShowError(R.string.msg_plugin_enable_failed))
					}
				}.onFailure { exception ->
					Log.e(TAG, "Error enabling plugin: $pluginId", exception)
					_uiEffect.trySend(
						PluginManagerUiEffect.ShowError(
							R.string.msg_plugin_enable_error,
							listOf(exception.message ?: ""),
						),
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

			pluginRepository
				.disablePlugin(pluginId)
				.onSuccess { success ->
					if (success) {
						Log.d(TAG, "Plugin disabled successfully: $pluginId")
						_uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_disabled))
						loadPlugins()
					} else {
						Log.w(TAG, "Failed to disable plugin: $pluginId")
						_uiEffect.trySend(PluginManagerUiEffect.ShowError(R.string.msg_plugin_disable_failed))
					}
				}.onFailure { exception ->
					Log.e(TAG, "Error disabling plugin: $pluginId", exception)
					_uiEffect.trySend(
						PluginManagerUiEffect.ShowError(
							R.string.msg_plugin_disable_error,
							listOf(exception.message ?: ""),
						),
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

			pluginRepository
				.uninstallPlugin(pluginId)
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
				}.onFailure { exception ->
					Log.e(TAG, "Error uninstalling plugin: $pluginId", exception)
					_uiEffect.trySend(
						PluginManagerUiEffect.ShowError(
							R.string.msg_plugin_uninstall_error,
							listOf(exception.message ?: ""),
						),
					)
				}

			_currentOperation.value = PluginOperation.None
		}
	}

	private fun installPlugin(
		source: PluginInstallSource,
		deleteSourceAfterInstall: Boolean,
		checkConflict: Boolean = true,
	) {
		viewModelScope.launch {
			_currentOperation.value = PluginOperation.Installing
			_uiState.update { it.copy(isInstalling = true) }

			// ownedTempFile (the ContentUri case's own temp copy) is what the `finally` block
			// below cleans up unconditionally. Note pluginRepository.installPluginFromFile()
			// itself unconditionally deletes whatever `pluginFile` it's given once that's copied
			// into the plugins directory - that's pre-existing behavior this function doesn't
			// control (it also affects InstallFileAction.kt's direct callers). What
			// deleteSourceAfterInstall/deleteInstallSource governs below is the *original*
			// source's lifecycle instead: a user-picked ContentUri is only ever deleted after a
			// successful install (see the onSuccess/onFailure split below), while a forwarded
			// LocalFile temp copy is always cleaned up regardless of outcome.
			var ownedTempFile: File? = null
			var pluginFile: File? = null

			try {
				if (checkConflict) {
					// See initialLoadCompleted's kdoc: guarantees _uiState.value.plugins reflects
					// the real installed set before resolveInstallConflict() checks it below.
					initialLoadCompleted.await()
				}

				pluginFile =
					when (source) {
						is PluginInstallSource.LocalFile -> {
							source.file
						}

						is PluginInstallSource.ContentUri -> {
							withContext(Dispatchers.IO) {
								val fileName = UriFileImporter.getDisplayName(contentResolver, source.uri)
								val extension =
									if (fileName?.endsWith(".$PLUGIN_ARCHIVE_EXTENSION", ignoreCase = true) == true) {
										PLUGIN_ARCHIVE_EXTENSION
									} else {
										"apk"
									}
								val tempFile = InstallTempFiles.newTempFile(filesDir, "temp_plugin", extension)

								UriFileImporter.copyUriToFile(contentResolver, source.uri, tempFile) {
									Exception("Cannot open file")
								}
								tempFile
							}.also { ownedTempFile = it }
						}
					}

				if (checkConflict && resolveInstallConflict(pluginFile, source, deleteSourceAfterInstall)) {
					return@launch
				}

				pluginRepository
					.installPluginFromFile(pluginFile)
					.onSuccess {
						Log.d(TAG, "Plugin installed successfully")
						_uiEffect.trySend(PluginManagerUiEffect.ShowSuccess(R.string.msg_plugin_installed))
						loadPlugins()
						_uiEffect.trySend(PluginManagerUiEffect.ShowRestartPrompt)

						if (deleteSourceAfterInstall) {
							deleteInstallSource(source)
						}
					}.onFailure { exception ->
						Log.e(TAG, "Failed to install plugin", exception)
						_uiEffect.trySend(
							PluginManagerUiEffect.ShowError(
								R.string.msg_plugin_install_failed,
								listOf(exception.message ?: ""),
							),
						)
						// A failed install deletes nothing but our own disposable temp copy - a
						// user-picked ContentUri is preserved so they can retry, matching
						// deleteSourceAfterInstall's "delete after install [succeeds]" meaning.
						if (source is PluginInstallSource.LocalFile) {
							deleteInstallSource(source)
						}
					}
			} catch (e: CancellationException) {
				throw e
			} catch (exception: Exception) {
				Log.e(TAG, "Error installing plugin from URI", exception)
				_uiEffect.trySend(
					PluginManagerUiEffect.ShowError(
						R.string.msg_plugin_install_failed,
						listOf(exception.message ?: ""),
					),
				)
				if (source is PluginInstallSource.LocalFile) {
					deleteInstallSource(source)
				}
			} finally {
				ownedTempFile?.let { file ->
					withContext(NonCancellable + Dispatchers.IO) {
						if (file.exists()) {
							file.delete()
						}
					}
				}
				_uiState.update { it.copy(isInstalling = false) }
				_currentOperation.value = PluginOperation.None
			}
		}
	}

	private suspend fun resolveInstallConflict(
		pluginFile: File,
		source: PluginInstallSource,
		deleteSourceAfterInstall: Boolean,
	): Boolean {
		val incoming = pluginRepository.getPluginMetadataFromFile(pluginFile).getOrNull()
		if (incoming == null) {
			Log.w(TAG, "Failed to read plugin metadata from ${pluginFile.name}; aborting install")
			_uiEffect.trySend(PluginManagerUiEffect.ShowError(R.string.msg_plugin_invalid_file))
			if (source is PluginInstallSource.LocalFile) deleteInstallSource(source)
			return true
		}

		val existing =
			_uiState.value.plugins.find { it.metadata.id == incoming.id }
				?: return false

		val signaturesMatch =
			pluginRepository
				.haveMatchingSignatures(pluginFile, existing.metadata.id)
				.getOrDefault(false)

		if (!signaturesMatch) {
			_uiEffect.trySend(
				PluginManagerUiEffect.ShowError(
					R.string.msg_plugin_signature_mismatch,
					listOf(existing.metadata.name),
				),
			)
			if (source is PluginInstallSource.LocalFile) deleteInstallSource(source)
			return true
		}

		// Deliberately don't delete the source yet: the user still needs to choose Replace or
		// Cancel. ConfirmOverwrite re-runs installPlugin() to consume it on Replace;
		// CancelPendingInstall cleans it up if they back out instead.
		_uiEffect.trySend(
			PluginManagerUiEffect.ShowOverwriteConfirmation(
				existing = existing,
				incomingMetadata = incoming,
				source = source,
				deleteSourceAfterInstall = deleteSourceAfterInstall,
			),
		)
		return true
	}

	private suspend fun deleteInstallSource(source: PluginInstallSource) {
		when (source) {
			is PluginInstallSource.LocalFile -> {
				withContext(Dispatchers.IO) {
					if (source.file.exists() && !source.file.delete()) {
						Log.w(TAG, "Failed to delete forwarded install file: ${source.file.absolutePath}")
					}
				}
			}

			is PluginInstallSource.ContentUri -> {
				deleteSourceDocument(source.uri)
			}
		}
	}

	private suspend fun deleteSourceDocument(uri: Uri) {
		withContext(Dispatchers.IO) {
			try {
				if (!DocumentsContract.deleteDocument(contentResolver, uri)) {
					_uiEffect.trySend(
						PluginManagerUiEffect.ShowError(R.string.msg_source_delete_failed),
					)
				}
			} catch (e: Exception) {
				Log.w(TAG, "Failed to delete source document", e)
				_uiEffect.trySend(
					PluginManagerUiEffect.ShowError(R.string.msg_source_delete_failed),
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
	fun isPluginOperationInProgress(pluginId: String): Boolean =
		when (val operation = _currentOperation.value) {
			is PluginOperation.Enabling -> operation.pluginId == pluginId
			is PluginOperation.Disabling -> operation.pluginId == pluginId
			is PluginOperation.Uninstalling -> operation.pluginId == pluginId
			else -> false
		}
}
