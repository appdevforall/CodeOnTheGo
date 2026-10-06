package org.appdevforall.codeonthego.ui.compose.plugins

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.idetooltips.TooltipManager
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.plugins.PluginMetadata
import org.appdevforall.codeonthego.ui.models.PluginInstallSource
import org.appdevforall.codeonthego.ui.models.PluginManagerUiEffect
import org.appdevforall.codeonthego.ui.models.PluginManagerUiEvent
import org.appdevforall.codeonthego.utils.DURATION_INDEFINITE
import org.appdevforall.codeonthego.utils.DialogUtils
import org.appdevforall.codeonthego.utils.errorIcon
import org.appdevforall.codeonthego.utils.flashError
import org.appdevforall.codeonthego.utils.flashSuccess
import org.appdevforall.codeonthego.utils.flashbarBuilder
import org.appdevforall.codeonthego.utils.showOnUiThread
import org.appdevforall.codeonthego.viewmodels.PluginManagerViewModel
import kotlinx.parcelize.Parcelize
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("PluginManagerContent")

/**
 * Keyed on the plugin's id rather than holding a [org.appdevforall.codeonthego.plugins.PluginInfo]
 * directly: `PluginInfo` isn't Parcelable, so an id is what makes this `rememberSaveable`-able
 * across rotation/tab-switch (`HorizontalPager` disposes the off-screen page's state) without
 * changing `plugin-api`'s public API surface. Resolved back to the live `PluginInfo` from
 * [org.appdevforall.codeonthego.ui.models.PluginManagerUiState.plugins] at the point of use; an id with
 * no match (e.g. the plugin was uninstalled elsewhere) is treated as "nothing to show" rather than
 * rendered with stale data. [PluginMetadata] (already Parcelable) is kept inline for
 * [OverwriteConfirm.incomingMetadata], which describes a plugin not yet installed and so has no id
 * to look up.
 */
private sealed interface PluginManagerDialogState : Parcelable {
	@Parcelize
	data object None : PluginManagerDialogState

	@Parcelize
	data class InstallConfirm(
		val source: PluginInstallSource,
	) : PluginManagerDialogState

	@Parcelize
	data class OverwriteConfirm(
		val existingId: String,
		val incomingMetadata: PluginMetadata,
		val source: PluginInstallSource,
		val deleteSourceAfterInstall: Boolean,
	) : PluginManagerDialogState

	@Parcelize
	data class UninstallConfirm(
		val pluginId: String,
	) : PluginManagerDialogState

	@Parcelize
	data class Details(
		val pluginId: String,
	) : PluginManagerDialogState
}

/**
 * Plugins tab content (ADR 0009). Preserves every capability of the original
 * `PluginManagerActivity`/`activity_plugin_manager.xml` screen: install (via SAF picker;
 * the launcher lives here, the FAB that triggers it lives in
 * [org.appdevforall.codeonthego.ui.compose.ManagerScreen], which owns the shared Scaffold)/
 * enable/disable/uninstall, overwrite/signature-mismatch conflict handling, restart prompt.
 *
 * Content-only (no Scaffold/TopAppBar/FAB): composed as one tab's body inside the shared manager
 * screen alongside the Templates tab.
 *
 * The original wired the same long-press tooltip (`TooltipTag.PLUGIN_MANAGER`) to six separate
 * views. Since they all show identical content, this collapses to two anchor points here: each
 * list item (already handles its own tap-for-details gesture) and the screen's background/empty
 * state area - long-pressing anywhere else on the screen shows the same tooltip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginManagerContent(
	activity: ComponentActivity,
	viewModel: PluginManagerViewModel,
	modifier: Modifier = Modifier,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	var dialogState by rememberSaveable { mutableStateOf<PluginManagerDialogState>(PluginManagerDialogState.None) }
	val rootView = LocalView.current
	val lifecycleOwner = LocalLifecycleOwner.current

	fun showTooltip() {
		TooltipManager.showIdeCategoryTooltip(activity, rootView, TooltipTag.PLUGIN_MANAGER)
	}

	LaunchedEffect(viewModel, lifecycleOwner) {
		lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
						dialogState = PluginManagerDialogState.Details(effect.plugin.metadata.id)
					}

					is PluginManagerUiEffect.ShowInstallConfirmation -> {
						dialogState = PluginManagerDialogState.InstallConfirm(effect.source)
					}

					is PluginManagerUiEffect.ShowUninstallConfirmation -> {
						dialogState = PluginManagerDialogState.UninstallConfirm(effect.plugin.metadata.id)
					}

					is PluginManagerUiEffect.ShowRestartPrompt -> {
						DialogUtils.showRestartPrompt(activity)
					}

					is PluginManagerUiEffect.ShowOverwriteConfirmation -> {
						dialogState =
							PluginManagerDialogState.OverwriteConfirm(
								existingId = effect.existing.metadata.id,
								incomingMetadata = effect.incomingMetadata,
								source = effect.source,
								deleteSourceAfterInstall = effect.deleteSourceAfterInstall,
							)
					}
				}
			}
		}
	}

	Box(
		modifier =
			modifier
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

	when (val dialog = dialogState) {
		is PluginManagerDialogState.None -> {}

		is PluginManagerDialogState.InstallConfirm -> {
			InstallConfirmationDialog(
				onConfirm = { deleteSource ->
					viewModel.onEvent(PluginManagerUiEvent.InstallPlugin(dialog.source, deleteSource))
					dialogState = PluginManagerDialogState.None
				},
				onDismiss = {
					// Declining a forwarded install must dispose of the temp copy
					// ExternalFileInstallActivity made for us; a user-picked ContentUri is left
					// untouched. CancelPendingInstall encapsulates that distinction.
					viewModel.onEvent(PluginManagerUiEvent.CancelPendingInstall(dialog.source))
					dialogState = PluginManagerDialogState.None
				},
			)
		}

		is PluginManagerDialogState.OverwriteConfirm -> {
			val existing = uiState.plugins.firstOrNull { it.metadata.id == dialog.existingId }
			if (existing != null) {
				OverwriteConfirmationDialog(
					existing = existing,
					incomingMetadata = dialog.incomingMetadata,
					onConfirm = {
						viewModel.onEvent(
							PluginManagerUiEvent.ConfirmOverwrite(dialog.source, dialog.deleteSourceAfterInstall),
						)
						dialogState = PluginManagerDialogState.None
					},
					onDismiss = { dialogState = PluginManagerDialogState.None },
				)
			}
		}

		is PluginManagerDialogState.UninstallConfirm -> {
			val plugin = uiState.plugins.firstOrNull { it.metadata.id == dialog.pluginId }
			if (plugin != null) {
				UninstallConfirmationDialog(
					plugin = plugin,
					onConfirm = {
						viewModel.confirmUninstallPlugin(plugin.metadata.id)
						dialogState = PluginManagerDialogState.None
					},
					onDismiss = { dialogState = PluginManagerDialogState.None },
				)
			}
		}

		is PluginManagerDialogState.Details -> {
			val plugin = uiState.plugins.firstOrNull { it.metadata.id == dialog.pluginId }
			if (plugin != null) {
				PluginDetailsDialog(
					plugin = plugin,
					onDismiss = { dialogState = PluginManagerDialogState.None },
				)
			}
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
