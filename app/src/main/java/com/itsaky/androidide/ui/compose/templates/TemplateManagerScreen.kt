package com.itsaky.androidide.ui.compose.templates

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.ui.models.TemplateManagerUiEffect
import com.itsaky.androidide.ui.models.TemplateManagerUiEvent
import com.itsaky.androidide.utils.DURATION_INDEFINITE
import com.itsaky.androidide.utils.errorIcon
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.flashbarBuilder
import com.itsaky.androidide.utils.showOnUiThread
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel
import kotlinx.parcelize.Parcelize

/**
 * Keyed on the backing file's absolute path rather than holding a [CgtFileItem] directly: the
 * item carries a plain `java.io.File`, which isn't Parcelable, so a path is what makes this
 * `rememberSaveable`-able across rotation/tab-switch (`HorizontalPager` disposes the off-screen
 * page's state) without teaching the whole CgtFileItem/TemplateMetadata chain to be Parcelable.
 * Resolved back to the live [CgtFileItem] from [TemplateManagerUiState.items][com.itsaky.androidide.ui.models.TemplateManagerUiState]
 * at the point of use; a path with no match (e.g. process death mid-scan, or the file was since
 * removed) is treated as "nothing to show" rather than rendered with stale data.
 */
private sealed interface TemplateManagerDialogState : Parcelable {
	@Parcelize
	data object None : TemplateManagerDialogState

	@Parcelize
	data class DeleteConfirm(
		val path: String,
	) : TemplateManagerDialogState

	@Parcelize
	data class FileDetails(
		val path: String,
	) : TemplateManagerDialogState

	@Parcelize
	data class TemplateList(
		val path: String,
	) : TemplateManagerDialogState
}

/** See [TemplateManagerDialogState]; same reasoning for the nested template-details dialog. */
@Parcelize
private data class SelectedTemplateKey(
	val ownerPath: String,
	val index: Int,
) : Parcelable

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
	var dialogState by rememberSaveable { mutableStateOf<TemplateManagerDialogState>(TemplateManagerDialogState.None) }
	var selectedTemplateKey by rememberSaveable { mutableStateOf<SelectedTemplateKey?>(null) }
	val selectedTemplateDetails =
		selectedTemplateKey?.let { key ->
			uiState.items
				.firstOrNull { it.file.absolutePath == key.ownerPath }
				?.templates
				?.getOrNull(key.index)
		}
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
						activity.flashSuccess(
							activity.getString(effect.messageResId, *effect.formatArgs.toTypedArray()),
						)
					}

					is TemplateManagerUiEffect.ShowDeleteConfirmation -> {
						dialogState = TemplateManagerDialogState.DeleteConfirm(effect.item.file.absolutePath)
					}

					is TemplateManagerUiEffect.ShowTemplateDetails -> {
						dialogState = TemplateManagerDialogState.FileDetails(effect.item.file.absolutePath)
					}

					is TemplateManagerUiEffect.ShowTemplateList -> {
						dialogState = TemplateManagerDialogState.TemplateList(effect.item.file.absolutePath)
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

		// Covers both the initial scan and install/uninstall/delete, which reload the whole
		// provider afterwards - see TemplateManagerViewModel. Top-aligned so it doesn't hide the
		// list underneath while an operation that already has visible content is in flight.
		if (uiState.isLoading) {
			LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
		}
	}

	when (val dialog = dialogState) {
		is TemplateManagerDialogState.None -> {}

		is TemplateManagerDialogState.DeleteConfirm -> {
			val item = uiState.items.firstOrNull { it.file.absolutePath == dialog.path }
			if (item != null) {
				DeleteTemplateConfirmationDialog(
					item = item,
					onConfirm = {
						viewModel.confirmDeleteDownloadFile(item)
						dialogState = TemplateManagerDialogState.None
					},
					onDismiss = { dialogState = TemplateManagerDialogState.None },
				)
			}
		}

		is TemplateManagerDialogState.FileDetails -> {
			val item = uiState.items.firstOrNull { it.file.absolutePath == dialog.path }
			if (item != null) {
				TemplateFileDetailsDialog(
					item = item,
					onDismiss = { dialogState = TemplateManagerDialogState.None },
				)
			}
		}

		is TemplateManagerDialogState.TemplateList -> {
			val item = uiState.items.firstOrNull { it.file.absolutePath == dialog.path }
			if (item != null) {
				TemplateListDialog(
					item = item,
					onSelectTemplate = { template ->
						selectedTemplateKey = SelectedTemplateKey(item.file.absolutePath, item.templates.indexOf(template))
					},
					onDismiss = { dialogState = TemplateManagerDialogState.None },
				)
			}
		}
	}

	selectedTemplateDetails?.let { template ->
		TemplateDetailsDialog(template = template, onDismiss = { selectedTemplateKey = null })
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
