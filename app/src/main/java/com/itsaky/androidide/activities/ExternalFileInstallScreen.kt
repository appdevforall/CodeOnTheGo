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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.itsaky.androidide.R
import com.itsaky.androidide.floating.ui.FloatingTheme
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEffect
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEvent
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
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

	LaunchedEffect(viewModel) {
		viewModel.uiEffect.collect { effect ->
			when (effect) {
				is ExternalFileInstallUiEffect.ForwardToPluginManager -> {
					context.startActivity(
						Intent(context, PluginManagerActivity::class.java)
							.putExtra(PluginManagerActivity.EXTRA_PENDING_INSTALL_URI, effect.uri),
					)
					(context as? Activity)?.finish()
				}

				is ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation -> {
					dialogState =
						DialogUiState.InstallConfirm(effect.info, effect.tempFile, effect.suggestedBaseName)
				}

				is ExternalFileInstallUiEffect.ShowTemplateNameConflict -> {
					dialogState = DialogUiState.NameConflict(effect.existingName, effect.tempFile)
				}

				is ExternalFileInstallUiEffect.ShowError -> {
					flashError(context.getString(effect.messageResId, *effect.formatArgs.toTypedArray()))
				}

				is ExternalFileInstallUiEffect.ShowSuccess -> {
					flashSuccess(context.getString(effect.messageResId))
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
					onInstall = {
						viewModel.onEvent(
							ExternalFileInstallUiEvent.ConfirmTemplateInstall(
								tempFile = state.tempFile,
								targetBaseName = state.suggestedBaseName,
								overwrite = false,
							),
						)
						dialogState = DialogUiState.None
					},
					onDismiss = {
						viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(state.tempFile))
						dialogState = DialogUiState.None
					},
				)
			}

			is DialogUiState.NameConflict -> {
				NameConflictDialog(
					state = state,
					onOverwrite = {
						viewModel.onEvent(
							ExternalFileInstallUiEvent.ConfirmTemplateInstall(
								tempFile = state.tempFile,
								targetBaseName = state.existingName,
								overwrite = true,
							),
						)
						dialogState = DialogUiState.None
					},
					onRename = { dialogState = DialogUiState.Rename(state.existingName, state.tempFile) },
					onDismiss = {
						viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(state.tempFile))
						dialogState = DialogUiState.None
					},
				)
			}

			is DialogUiState.Rename -> {
				RenameDialog(
					state = state,
					suggestName = viewModel::suggestUniqueBaseName,
					onConfirm = { newName ->
						viewModel.onEvent(
							ExternalFileInstallUiEvent.ConfirmTemplateInstall(
								tempFile = state.tempFile,
								targetBaseName = viewModel.sanitizeBaseName(newName),
								overwrite = false,
							),
						)
						dialogState = DialogUiState.None
					},
					onDismiss = {
						viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(state.tempFile))
						dialogState = DialogUiState.None
					},
				)
			}

			DialogUiState.None -> {
				Unit
			}
		}
	}
}

@Composable
private fun InstallConfirmationDialog(
	state: DialogUiState.InstallConfirm,
	onInstall: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_install_template_collection)) },
		text = {
			Text(
				stringResource(
					R.string.msg_template_install_confirm,
					state.suggestedBaseName,
					state.info.templateNames.joinToString(", "),
				),
			)
		},
		confirmButton = {
			TextButton(onClick = onInstall) { Text(stringResource(R.string.btn_install)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun NameConflictDialog(
	state: DialogUiState.NameConflict,
	onOverwrite: () -> Unit,
	onRename: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_template_already_installed)) },
		text = { Text(stringResource(R.string.msg_template_name_conflict, state.existingName)) },
		confirmButton = {
			TextButton(onClick = onOverwrite) { Text(stringResource(R.string.btn_overwrite)) }
		},
		dismissButton = {
			Row {
				TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
				TextButton(onClick = onRename) { Text(stringResource(R.string.btn_rename_and_install)) }
			}
		},
	)
}

@Composable
private fun RenameDialog(
	state: DialogUiState.Rename,
	suggestName: suspend (String) -> String,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var name by remember { mutableStateOf(TextFieldValue(state.existingName)) }
	var suggestionReady by remember { mutableStateOf(false) }
	val currentSuggestName by rememberUpdatedState(suggestName)

	LaunchedEffect(state.existingName) {
		val suggested = currentSuggestName(state.existingName)
		name = TextFieldValue(suggested, selection = TextRange(suggested.length))
		suggestionReady = true
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.btn_rename_and_install)) },
		text = {
			OutlinedTextField(
				value = name,
				onValueChange = { name = it },
				label = { Text(stringResource(R.string.hint_new_template_collection_name)) },
				singleLine = true,
			)
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(name.text) },
				enabled = suggestionReady && name.text.isNotBlank(),
			) {
				Text(stringResource(R.string.btn_install))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
