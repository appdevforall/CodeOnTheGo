package com.itsaky.androidide.ui.compose.templates

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.remember
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
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.ui.models.TemplateManagerUiEffect
import com.itsaky.androidide.ui.models.TemplateManagerUiEvent
import com.itsaky.androidide.utils.DURATION_INDEFINITE
import com.itsaky.androidide.utils.errorIcon
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.flashbarBuilder
import com.itsaky.androidide.utils.showOnUiThread
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel

private sealed interface TemplateManagerDialogState {
	data object None : TemplateManagerDialogState

	data class DeleteConfirm(
		val item: CgtFileItem,
	) : TemplateManagerDialogState

	data class FileDetails(
		val item: CgtFileItem,
	) : TemplateManagerDialogState

	data class TemplateList(
		val item: CgtFileItem,
	) : TemplateManagerDialogState
}

/**
 * Templates tab content (ADR 0009). Passively scans `Environment.TEMPLATES_DIR` + the Downloads
 * folder for `.cgt` files - unlike the Plugins tab, there's no FAB/file-picker install flow here,
 * matching the reference `TemplateManagerPlugin`'s design.
 *
 * Content-only (no Scaffold/TopAppBar): meant to be composed as one tab's body inside the shared
 * manager screen alongside the Plugins tab.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TemplateManagerScreen(
	activity: ComponentActivity,
	viewModel: TemplateManagerViewModel,
	modifier: Modifier = Modifier,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	var dialogState by remember { mutableStateOf<TemplateManagerDialogState>(TemplateManagerDialogState.None) }
	var selectedTemplateDetails by remember { mutableStateOf<TemplateMetadata?>(null) }
	val rootView = LocalView.current
	val lifecycleOwner = LocalLifecycleOwner.current

	fun showTooltip() {
		TooltipManager.showIdeCategoryTooltip(activity, rootView, TooltipTag.TEMPLATE_MANAGER)
	}

	LaunchedEffect(viewModel, lifecycleOwner) {
		lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			viewModel.uiEffect.collect { effect ->
				when (effect) {
					is TemplateManagerUiEffect.ShowError -> {
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
											ClipData.newPlainText(activity.getString(R.string.msg_template_error_clip_label), message),
										)
									bar.dismiss()
								}
						}
						builder.showOnUiThread()
					}

					is TemplateManagerUiEffect.ShowSuccess -> {
						activity.flashSuccess(activity.getString(effect.messageResId))
					}

					is TemplateManagerUiEffect.ShowDeleteConfirmation -> {
						dialogState = TemplateManagerDialogState.DeleteConfirm(effect.item)
					}

					is TemplateManagerUiEffect.ShowTemplateDetails -> {
						dialogState = TemplateManagerDialogState.FileDetails(effect.item)
					}

					is TemplateManagerUiEffect.ShowTemplateList -> {
						dialogState = TemplateManagerDialogState.TemplateList(effect.item)
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
		if (uiState.isEmpty) {
			TemplateManagerEmptyState(modifier = Modifier.fillMaxSize())
		} else {
			LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
				items(uiState.items, key = { it.file.absolutePath }) { item ->
					TemplateListItem(
						item = item,
						onInstall = { viewModel.onEvent(TemplateManagerUiEvent.InstallTemplate(item)) },
						onUninstall = { viewModel.onEvent(TemplateManagerUiEvent.UninstallTemplate(item)) },
						onDetails = { viewModel.onEvent(TemplateManagerUiEvent.ShowTemplateDetails(item)) },
						onDelete = { viewModel.onEvent(TemplateManagerUiEvent.DeleteDownloadFile(item)) },
						onViewTemplates = { viewModel.onEvent(TemplateManagerUiEvent.ShowTemplateList(item)) },
						onLongPressTooltip = { showTooltip() },
						modifier = Modifier.padding(bottom = 8.dp),
					)
				}
			}
		}
	}

	when (val dialog = dialogState) {
		is TemplateManagerDialogState.None -> {}

		is TemplateManagerDialogState.DeleteConfirm -> {
			DeleteTemplateConfirmationDialog(
				item = dialog.item,
				onConfirm = {
					viewModel.confirmDeleteDownloadFile(dialog.item)
					dialogState = TemplateManagerDialogState.None
				},
				onDismiss = { dialogState = TemplateManagerDialogState.None },
			)
		}

		is TemplateManagerDialogState.FileDetails -> {
			TemplateFileDetailsDialog(
				item = dialog.item,
				onDismiss = { dialogState = TemplateManagerDialogState.None },
			)
		}

		is TemplateManagerDialogState.TemplateList -> {
			TemplateListDialog(
				item = dialog.item,
				onSelectTemplate = { template -> selectedTemplateDetails = template },
				onDismiss = { dialogState = TemplateManagerDialogState.None },
			)
		}
	}

	selectedTemplateDetails?.let { template ->
		TemplateDetailsDialog(template = template, onDismiss = { selectedTemplateDetails = null })
	}
}

@Composable
private fun TemplateManagerEmptyState(modifier: Modifier = Modifier) {
	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Icon(
				painter = painterResource(R.drawable.ic_docs),
				contentDescription = null,
				modifier =
					Modifier
						.size(64.dp)
						.padding(bottom = 16.dp),
			)
			Text(stringResource(R.string.no_templates_found), style = MaterialTheme.typography.headlineSmall)
			Text(
				stringResource(R.string.no_templates_found_hint),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
