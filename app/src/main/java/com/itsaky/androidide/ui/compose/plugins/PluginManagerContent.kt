package com.itsaky.androidide.ui.compose.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.PluginMetadata
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.utils.DURATION_INDEFINITE
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.errorIcon
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.flashbarBuilder
import com.itsaky.androidide.utils.getFileName
import com.itsaky.androidide.utils.showOnUiThread
import com.itsaky.androidide.viewmodels.PluginManagerViewModel

private const val TAG = "PluginManagerScreen"
private const val PLUGIN_EXTENSION = ".cgp"

private fun Uri.isSupportedPluginFile(activity: ComponentActivity): Boolean =
	getFileName(activity).endsWith(PLUGIN_EXTENSION, ignoreCase = true)

private sealed interface PluginManagerDialogState {
	data object None : PluginManagerDialogState

	data class InstallConfirm(
		val uri: Uri,
	) : PluginManagerDialogState

	data class OverwriteConfirm(
		val existing: PluginInfo,
		val incomingMetadata: PluginMetadata,
		val uri: Uri,
		val deleteSourceAfterInstall: Boolean,
	) : PluginManagerDialogState

	data class UninstallConfirm(
		val plugin: PluginInfo,
	) : PluginManagerDialogState

	data class Details(
		val plugin: PluginInfo,
	) : PluginManagerDialogState
}

/**
 * Compose port of the legacy `PluginManagerActivity`/`activity_plugin_manager.xml` screen (ADR 0009).
 * Preserves every capability of the original: install (via SAF picker)/enable/disable/uninstall,
 * overwrite/signature-mismatch conflict handling, restart prompt, and the discover-plugins action.
 *
 * The original wired the same long-press tooltip (`TooltipTag.PLUGIN_MANAGER`) to six separate
 * views. Since they all show identical content, this collapses to two anchor points here: each
 * list item (already handles its own tap-for-details gesture) and the screen's background/empty
 * state area - long-pressing anywhere else on the screen shows the same tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PluginManagerScreen(
	activity: ComponentActivity,
	viewModel: PluginManagerViewModel,
	modifier: Modifier = Modifier,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	var dialogState by remember { mutableStateOf<PluginManagerDialogState>(PluginManagerDialogState.None) }
	val rootView = LocalView.current

	fun showTooltip() {
		TooltipManager.showIdeCategoryTooltip(activity, rootView, TooltipTag.PLUGIN_MANAGER)
	}

	val filePickerLauncher =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
			uri?.let {
				try {
					activity.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
				} catch (e: SecurityException) {
					Log.w(TAG, "Could not take persistable URI permission", e)
				}

				if (!it.isSupportedPluginFile(activity)) {
					activity.flashError(activity.getString(R.string.msg_unsupported_plugin_file))
				} else {
					dialogState = PluginManagerDialogState.InstallConfirm(it)
				}
			}
		}

	LaunchedEffect(viewModel) {
		viewModel.uiEffect.collect { effect ->
			when (effect) {
				is PluginManagerUiEffect.ShowError -> {
					val message = activity.getString(effect.messageResId, *effect.formatArgs.toTypedArray())
					val builder =
						activity
							.flashbarBuilder(duration = if (effect.formatArgs.isEmpty()) 5000L else DURATION_INDEFINITE)
							.errorIcon()
							.message(message)
					if (effect.formatArgs.isNotEmpty()) {
						builder
							.positiveActionText(R.string.copy)
							.positiveActionTapListener { bar ->
								activity
									.getSystemService(ClipboardManager::class.java)
									?.setPrimaryClip(
										ClipData.newPlainText(activity.getString(R.string.msg_plugin_error_clip_label), message),
									)
								bar.dismiss()
							}
					}
					builder.showOnUiThread()
				}

				is PluginManagerUiEffect.ShowSuccess -> {
					activity.flashSuccess(activity.getString(effect.messageResId))
				}

				is PluginManagerUiEffect.ShowPluginDetails -> {
					dialogState = PluginManagerDialogState.Details(effect.plugin)
				}

				is PluginManagerUiEffect.OpenFilePicker -> {
					try {
						filePickerLauncher.launch(arrayOf("*/*"))
					} catch (_: Exception) {
						activity.flashError(activity.getString(R.string.msg_no_file_manager))
					}
				}

				is PluginManagerUiEffect.ShowUninstallConfirmation -> {
					dialogState = PluginManagerDialogState.UninstallConfirm(effect.plugin)
				}

				is PluginManagerUiEffect.ShowRestartPrompt -> {
					DialogUtils.showRestartPrompt(activity)
				}

				is PluginManagerUiEffect.ShowOverwriteConfirmation -> {
					dialogState =
						PluginManagerDialogState.OverwriteConfirm(
							existing = effect.existing,
							incomingMetadata = effect.incomingMetadata,
							uri = effect.uri,
							deleteSourceAfterInstall = effect.deleteSourceAfterInstall,
						)
				}
			}
		}
	}

	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.title_plugin_manager)) },
				navigationIcon = {
					IconButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }) {
						Icon(
							painter = painterResource(R.drawable.ic_back),
							contentDescription = stringResource(android.R.string.cancel),
						)
					}
				},
				actions = {
					IconButton(
						onClick = {
							UrlManager.openUrl(activity.getString(R.string.url_discover_plugins), null, activity)
						},
					) {
						Icon(
							painter = painterResource(R.drawable.ic_download),
							contentDescription = stringResource(R.string.action_discover_plugins),
						)
					}
				},
			)
		},
		floatingActionButton = {
			FloatingActionButton(
				onClick = { viewModel.onEvent(PluginManagerUiEvent.OpenFilePicker) },
			) {
				Icon(
					painter = painterResource(R.drawable.ic_add),
					contentDescription = stringResource(R.string.cd_add),
				)
			}
		},
	) { padding ->
		Box(
			modifier =
				Modifier
					.padding(padding)
					.fillMaxSize()
					.pointerInput(Unit) { detectTapGestures(onLongPress = { showTooltip() }) },
		) {
			if (uiState.showEmptyState) {
				PluginManagerEmptyState(modifier = Modifier.fillMaxSize())
			} else {
				LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
					items(uiState.plugins, key = { it.metadata.id }) { plugin ->
						PluginListItem(
							plugin = plugin,
							onEnable = { viewModel.onEvent(PluginManagerUiEvent.EnablePlugin(plugin.metadata.id)) },
							onDisable = { viewModel.onEvent(PluginManagerUiEvent.DisablePlugin(plugin.metadata.id)) },
							onUninstall = { viewModel.onEvent(PluginManagerUiEvent.UninstallPlugin(plugin.metadata.id)) },
							onDetails = { viewModel.onEvent(PluginManagerUiEvent.ShowPluginDetails(plugin)) },
							onLongPressTooltip = { showTooltip() },
							modifier = Modifier.padding(bottom = 8.dp),
						)
					}
				}
			}
		}
	}

	when (val dialog = dialogState) {
		is PluginManagerDialogState.None -> {}

		is PluginManagerDialogState.InstallConfirm -> {
			InstallConfirmationDialog(
				onConfirm = { deleteSource ->
					viewModel.onEvent(PluginManagerUiEvent.InstallPlugin(dialog.uri, deleteSource))
					dialogState = PluginManagerDialogState.None
				},
				onDismiss = { dialogState = PluginManagerDialogState.None },
			)
		}

		is PluginManagerDialogState.OverwriteConfirm -> {
			OverwriteConfirmationDialog(
				existing = dialog.existing,
				incomingMetadata = dialog.incomingMetadata,
				onConfirm = {
					viewModel.onEvent(PluginManagerUiEvent.ConfirmOverwrite(dialog.uri, dialog.deleteSourceAfterInstall))
					dialogState = PluginManagerDialogState.None
				},
				onDismiss = { dialogState = PluginManagerDialogState.None },
			)
		}

		is PluginManagerDialogState.UninstallConfirm -> {
			UninstallConfirmationDialog(
				plugin = dialog.plugin,
				onConfirm = {
					viewModel.confirmUninstallPlugin(dialog.plugin.metadata.id)
					dialogState = PluginManagerDialogState.None
				},
				onDismiss = { dialogState = PluginManagerDialogState.None },
			)
		}

		is PluginManagerDialogState.Details -> {
			PluginDetailsDialog(
				plugin = dialog.plugin,
				onDismiss = { dialogState = PluginManagerDialogState.None },
			)
		}
	}
}

@Composable
private fun PluginManagerEmptyState(modifier: Modifier = Modifier) {
	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Icon(
				painter = painterResource(R.drawable.ic_package),
				contentDescription = null,
				modifier =
					Modifier
						.size(64.dp)
						.padding(bottom = 16.dp),
			)
			Text(stringResource(R.string.no_plugins_installed), style = MaterialTheme.typography.headlineSmall)
			Text(
				stringResource(R.string.no_plugins_installed_hint),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
