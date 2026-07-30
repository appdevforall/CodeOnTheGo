package com.itsaky.androidide.ui.compose.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.R
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.templates.manager.models.displayName
import com.itsaky.androidide.templates.manager.models.primaryTemplate
import com.itsaky.androidide.templates.manager.models.versionLabel

@Composable
fun DeleteTemplateConfirmationDialog(
	item: CgtFileItem,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_delete_template)) },
		text = { Text(stringResource(R.string.msg_delete_template_confirm, item.displayName)) },
		confirmButton = {
			TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete_template)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

/** File-level details for a single-template .cgt (multi-template files use [TemplateListDialog]). */
@Composable
fun TemplateFileDetailsDialog(
	item: CgtFileItem,
	onDismiss: () -> Unit,
) {
	val primary = item.primaryTemplate
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(primary.name.ifBlank { item.displayName }) },
		text = {
			SelectionContainer {
				Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
					DetailRow(stringResource(R.string.label_template_file), item.displayName)
					DetailRow(
						stringResource(R.string.label_template_status),
						stringResource(
							if (item.installed) R.string.status_template_installed else R.string.status_template_not_installed,
						),
					)
					DetailRow(stringResource(R.string.label_template_location), item.file.absolutePath)
					TemplateMetadataDetails(primary)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
		},
	)
}

/** Details for a single template selected from the [TemplateListDialog] sub-screen. */
@Composable
fun TemplateDetailsDialog(
	template: TemplateMetadata,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(template.name.ifBlank { stringResource(R.string.template_unnamed) }) },
		text = {
			SelectionContainer {
				Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
					TemplateMetadataDetails(template)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
		},
	)
}

@Composable
private fun TemplateMetadataDetails(template: TemplateMetadata) {
	val versionText = versionLabel(template.version)
	if (versionText.isNotBlank()) {
		DetailRow(stringResource(R.string.label_template_version), versionText)
	}
	DetailRow(stringResource(R.string.label_template_description), template.description)
	if (template.optionalTags.isNotEmpty()) {
		Text(stringResource(R.string.label_template_optional_params), style = MaterialTheme.typography.labelLarge)
		template.optionalTags.forEach { tag -> Text("• $tag") }
	}
}

@Composable
private fun DetailRow(
	label: String,
	value: String,
) {
	Text("$label: $value")
}

/** Sub-screen: one card per template bundled inside a multi-template .cgt. */
@Composable
fun TemplateListDialog(
	item: CgtFileItem,
	onSelectTemplate: (TemplateMetadata) -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.title_templates_in, item.displayName)) },
		text = {
			LazyColumn {
				items(item.templates) { template ->
					Card(
						modifier =
							Modifier
								.fillMaxWidth()
								.padding(vertical = 4.dp),
					) {
						Column(
							modifier =
								Modifier
									.fillMaxWidth()
									.clickable { onSelectTemplate(template) }
									.padding(12.dp),
						) {
							Text(
								template.name.ifBlank { stringResource(R.string.template_unnamed) },
								style = MaterialTheme.typography.titleSmall,
							)
							val versionText = versionLabel(template.version)
							if (versionText.isNotBlank()) {
								Text(versionText, style = MaterialTheme.typography.labelSmall)
							}
							Text(template.description, style = MaterialTheme.typography.bodySmall)
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
		},
	)
}
