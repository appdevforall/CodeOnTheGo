package com.itsaky.androidide.ui.compose.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.itsaky.androidide.R
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.PluginMetadata

@Composable
fun InstallConfirmationDialog(
	onConfirm: (deleteSourceAfterInstall: Boolean) -> Unit,
	onDismiss: () -> Unit,
) {
	var deleteSource by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_install_plugin)) },
		text = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
				Text(stringResource(R.string.checkbox_delete_source_after_install))
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(deleteSource) }) { Text(stringResource(R.string.btn_install)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
fun OverwriteConfirmationDialog(
	existing: PluginInfo,
	incomingMetadata: PluginMetadata,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_plugin_already_installed)) },
		text = {
			Text(
				stringResource(
					R.string.msg_plugin_overwrite_confirm,
					existing.metadata.name,
					existing.metadata.version,
					incomingMetadata.version,
				),
			)
		},
		confirmButton = {
			TextButton(onClick = onConfirm) { Text(stringResource(R.string.replace)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
fun UninstallConfirmationDialog(
	plugin: PluginInfo,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_uninstall_plugin)) },
		text = { Text(stringResource(R.string.msg_uninstall_plugin_confirm, plugin.metadata.name)) },
		confirmButton = {
			TextButton(onClick = onConfirm) { Text(stringResource(R.string.uninstall_plugin)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
fun PluginDetailsDialog(
	plugin: PluginInfo,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(plugin.metadata.name) },
		text = {
			SelectionContainer {
				Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
					DetailRow(stringResource(R.string.label_plugin_name), plugin.metadata.name)
					DetailRow(stringResource(R.string.label_plugin_id), plugin.metadata.id)
					DetailRow(stringResource(R.string.label_plugin_version), plugin.metadata.version)
					DetailRow(stringResource(R.string.label_plugin_author), plugin.metadata.author)
					DetailRow(stringResource(R.string.label_plugin_description), plugin.metadata.description)
					DetailRow(stringResource(R.string.label_plugin_min_ide_version), plugin.metadata.minIdeVersion)
					// Absent on a .cgp built before ADFA-5256; showing an empty row would read as
					// "built from nothing" rather than "this build predates provenance".
					plugin.metadata.vcsRevision?.let {
						DetailRow(stringResource(R.string.label_plugin_built_from), it)
					}
					plugin.metadata.buildTimestamp?.let {
						DetailRow(stringResource(R.string.label_plugin_built_at), it)
					}
					DetailRow(stringResource(R.string.plugin_permissions), plugin.metadata.permissions.joinToString(", "))
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.msg_ok)) }
		},
	)
}

@Composable
private fun DetailRow(
	label: String,
	value: String,
) {
	Text(stringResource(R.string.label_value, label, value))
}
