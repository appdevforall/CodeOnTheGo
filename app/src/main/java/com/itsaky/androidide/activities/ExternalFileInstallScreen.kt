package com.itsaky.androidide.activities

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsaky.androidide.R
import com.itsaky.androidide.floating.ui.FloatingTheme
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.ui.compose.longPressTooltip
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEffect
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEvent
import com.itsaky.androidide.utils.flashErrorAwaitShown
import com.itsaky.androidide.utils.flashSuccessAwaitShown
import com.itsaky.androidide.viewmodels.ExternalFileInstallViewModel
import java.io.File

private sealed interface DialogUiState {
	object None : DialogUiState

	data class InstallConfirm(
		val info: TemplateCollectionRepository.CollectionInfo,
		val tempFile: File,
		val suggestedBaseName: String,
	) : DialogUiState

	data class NameConflict(
		val existingName: String,
		val info: TemplateCollectionRepository.CollectionInfo,
		val tempFile: File,
	) : DialogUiState

	data class Rename(
		val existingName: String,
		val tempFile: File,
	) : DialogUiState
}

@Composable
fun ExternalFileInstallScreen(viewModel: ExternalFileInstallViewModel) {
	val context = LocalContext.current
	var dialogState by remember { mutableStateOf<DialogUiState>(DialogUiState.None) }
	val isInstalling by viewModel.isInstalling.collectAsStateWithLifecycle()

	LaunchedEffect(viewModel) {
		viewModel.uiEffect.collect { effect ->
			when (effect) {
				is ExternalFileInstallUiEffect.ForwardToPluginManager -> {
					context.startActivity(
						Intent(context, PluginManagerActivity::class.java)
							// A Plugin Manager instance may already be running/backgrounded (e.g.
							// the user had it open, then opened a .cgp attachment) - these flags
							// reuse that instance via onNewIntent() instead of stacking a second
							// one on top of it.
							.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
							.putExtra(PluginManagerActivity.EXTRA_PENDING_INSTALL_FILE_PATH, effect.filePath),
					)
					(context as? Activity)?.finish()
				}

				is ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation -> {
					dialogState =
						DialogUiState.InstallConfirm(effect.info, effect.tempFile, effect.suggestedBaseName)
				}

				is ExternalFileInstallUiEffect.ShowTemplateNameConflict -> {
					dialogState = DialogUiState.NameConflict(effect.existingName, effect.info, effect.tempFile)
				}

				is ExternalFileInstallUiEffect.ShowError -> {
					// Deliberately doesn't touch dialogState: on an install failure the ViewModel
					// sends ShowError without a following Finish, so whichever dialog is open
					// (install-confirm / name-conflict / rename) stays open for the user to retry.
					// Awaits the bar's entrance animation instead of returning immediately: this
					// suspends the collect{} loop above, so a Finish effect buffered right after
					// this one (see sendErrorAndFinish()) isn't processed - and doesn't tear the
					// window down - until the message has actually finished appearing.
					flashErrorAwaitShown(context.getString(effect.messageResId, *effect.formatArgs.toTypedArray()))
				}

				is ExternalFileInstallUiEffect.ShowSuccess -> {
					flashSuccessAwaitShown(context.getString(effect.messageResId, *effect.formatArgs.toTypedArray()))
				}

				is ExternalFileInstallUiEffect.Finish -> {
					(context as? Activity)?.finish()
				}
			}
		}
	}

	FloatingTheme {
		when (val state = dialogState) {
			is DialogUiState.InstallConfirm -> {
				InstallConfirmationDialog(
					state = state,
					installEnabled = !isInstalling,
					onInstall = {
						viewModel.onEvent(
							ExternalFileInstallUiEvent.ConfirmTemplateInstall(
								tempFile = state.tempFile,
								targetBaseName = state.suggestedBaseName,
								overwrite = false,
							),
						)
					},
					onDismiss = {
						viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(state.tempFile))
					},
				)
			}

			is DialogUiState.NameConflict -> {
				NameConflictDialog(
					state = state,
					installEnabled = !isInstalling,
					onOverwrite = {
						viewModel.onEvent(
							ExternalFileInstallUiEvent.ConfirmTemplateInstall(
								tempFile = state.tempFile,
								targetBaseName = state.existingName,
								overwrite = true,
							),
						)
					},
					onRename = { dialogState = DialogUiState.Rename(state.existingName, state.tempFile) },
					onDismiss = {
						viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(state.tempFile))
					},
				)
			}

			is DialogUiState.Rename -> {
				RenameDialog(
					state = state,
					installEnabled = !isInstalling,
					suggestName = viewModel::suggestUniqueBaseName,
					onConfirm = { newName ->
						viewModel.onEvent(
							ExternalFileInstallUiEvent.ConfirmTemplateInstall(
								tempFile = state.tempFile,
								targetBaseName = viewModel.sanitizeBaseName(newName),
								overwrite = false,
							),
						)
					},
					onDismiss = {
						viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(state.tempFile))
					},
				)
			}

			DialogUiState.None -> {
				Unit
			}
		}
	}
}

/** Comma-joined display list of a collection's template names, shared by both confirm dialogs. */
private fun TemplateCollectionRepository.CollectionInfo.displayTemplateNames(): String = templateNames.joinToString(", ")

@Composable
private fun InstallConfirmationDialog(
	state: DialogUiState.InstallConfirm,
	installEnabled: Boolean,
	onInstall: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		// Gated on installEnabled (== !isInstalling): once Install is tapped, the ViewModel
		// starts copying/replacing tempFile on viewModelScope - dismissing here would race
		// IgnoreTemplateInstall's own delete of that same file against the in-progress install.
		onDismissRequest = { if (installEnabled) onDismiss() },
		title = {
			Text(
				stringResource(R.string.title_install_template_collection),
				modifier = Modifier.longPressTooltip(TooltipTag.EXTERNAL_FILE_INSTALL),
			)
		},
		text = {
			Text(
				stringResource(
					R.string.msg_template_install_confirm,
					state.suggestedBaseName,
					state.info.displayTemplateNames(),
				),
			)
		},
		confirmButton = {
			TextButton(onClick = onInstall, enabled = installEnabled) { Text(stringResource(R.string.btn_install)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = installEnabled) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun NameConflictDialog(
	state: DialogUiState.NameConflict,
	installEnabled: Boolean,
	onOverwrite: () -> Unit,
	onRename: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = { if (installEnabled) onDismiss() },
		title = {
			Text(
				stringResource(R.string.title_template_already_installed),
				modifier = Modifier.longPressTooltip(TooltipTag.EXTERNAL_FILE_INSTALL),
			)
		},
		text = {
			Text(
				stringResource(
					R.string.msg_template_name_conflict,
					state.existingName,
					state.info.displayTemplateNames(),
				),
			)
		},
		confirmButton = {
			TextButton(onClick = onOverwrite, enabled = installEnabled) { Text(stringResource(R.string.btn_overwrite)) }
		},
		dismissButton = {
			Row {
				TextButton(onClick = onDismiss, enabled = installEnabled) { Text(stringResource(android.R.string.cancel)) }
				TextButton(onClick = onRename, enabled = installEnabled) { Text(stringResource(R.string.btn_rename_and_install)) }
			}
		},
	)
}

@Composable
private fun RenameDialog(
	state: DialogUiState.Rename,
	installEnabled: Boolean,
	suggestName: suspend (String) -> String,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var name by remember { mutableStateOf(TextFieldValue(state.existingName)) }
	var userEdited by remember { mutableStateOf(false) }
	var suggestionReady by remember { mutableStateOf(false) }
	val currentSuggestName by rememberUpdatedState(suggestName)

	LaunchedEffect(state.existingName) {
		val suggested = currentSuggestName(state.existingName)
		// Only apply the suggestion if the user hasn't already started typing their own name -
		// this resolves asynchronously and must not clobber in-progress input.
		if (!userEdited) {
			name = TextFieldValue(suggested, selection = TextRange(suggested.length))
		}
		suggestionReady = true
	}

	AlertDialog(
		onDismissRequest = { if (installEnabled) onDismiss() },
		title = {
			Text(
				stringResource(R.string.btn_rename_and_install),
				modifier = Modifier.longPressTooltip(TooltipTag.EXTERNAL_FILE_INSTALL),
			)
		},
		text = {
			OutlinedTextField(
				value = name,
				onValueChange = {
					name = it
					userEdited = true
				},
				label = { Text(stringResource(R.string.hint_new_template_collection_name)) },
				singleLine = true,
			)
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(name.text) },
				enabled = installEnabled && suggestionReady && name.text.isNotBlank(),
			) {
				Text(stringResource(R.string.btn_install))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = installEnabled) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
