package com.itsaky.androidide.ui.compose.templates

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.R
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import com.itsaky.androidide.templates.manager.models.displayName
import com.itsaky.androidide.templates.manager.models.hasMultipleTemplates
import com.itsaky.androidide.templates.manager.models.primaryTemplate
import com.itsaky.androidide.templates.manager.models.versionLabel

/**
 * Card for a single `.cgt` file. Matches the reference plugin's card: tapping the card only
 * opens the multi-template sub-list when the file bundles more than one template; single-template
 * files are only actionable through the overflow menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TemplateListItem(
	item: CgtFileItem,
	onInstall: () -> Unit,
	onUninstall: () -> Unit,
	onDetails: () -> Unit,
	onDelete: () -> Unit,
	onViewTemplates: () -> Unit,
	onLongPressTooltip: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var menuExpanded by remember { mutableStateOf(false) }
	val primary = item.primaryTemplate

	Card(
		modifier =
			modifier
				.fillMaxWidth()
				.let { cardModifier ->
					if (item.hasMultipleTemplates) {
						cardModifier.combinedClickable(onClick = onViewTemplates, onLongClick = onLongPressTooltip)
					} else {
						cardModifier.pointerInput(Unit) {
							detectTapGestures(onLongPress = { onLongPressTooltip() })
						}
					}
				},
	) {
		Row(modifier = Modifier.padding(16.dp)) {
			Column(modifier = Modifier.weight(1f)) {
				Text(primary.name.ifBlank { item.displayName }, style = MaterialTheme.typography.titleMedium)
				Text(
					primary.description,
					style = MaterialTheme.typography.bodySmall,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)

				val versionText = versionLabel(primary.version)
				if (versionText.isNotBlank()) {
					Text(versionText, style = MaterialTheme.typography.labelSmall)
				}
				Text(item.displayName, style = MaterialTheme.typography.labelSmall)

				if (item.hasMultipleTemplates) {
					Text(
						pluralStringResource(
							R.plurals.template_contains_count,
							item.templates.size,
							item.templates.size,
						),
						style = MaterialTheme.typography.labelSmall,
						modifier = Modifier.clickable(onClick = onViewTemplates),
					)
				}

				Row {
					val (statusText, statusColor) =
						if (item.installed) {
							stringResource(R.string.status_template_installed) to colorResource(R.color.success)
						} else {
							stringResource(R.string.status_template_not_installed) to colorResource(R.color.error)
						}
					Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium)
					Text(
						" - " + stringResource(item.provenance.labelRes()),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			Box {
				IconButton(onClick = { menuExpanded = true }) {
					Icon(
						painter = painterResource(R.drawable.ic_more_vert),
						contentDescription = stringResource(R.string.cd_more_options),
					)
				}
				DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
					if (item.installed) {
						if (item.provenance != TemplateProvenance.BUNDLED) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.action_uninstall_template)) },
								onClick = {
									menuExpanded = false
									onUninstall()
								},
							)
						}
					} else {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.action_install_template)) },
							onClick = {
								menuExpanded = false
								onInstall()
							},
						)
					}

					if (item.hasMultipleTemplates) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.action_view_templates)) },
							onClick = {
								menuExpanded = false
								onViewTemplates()
							},
						)
					} else {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.template_details)) },
							onClick = {
								menuExpanded = false
								onDetails()
							},
						)
					}

					if (!item.installed) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.action_delete_template)) },
							onClick = {
								menuExpanded = false
								onDelete()
							},
						)
					}
				}
			}
		}
	}
}

private fun TemplateProvenance.labelRes(): Int =
	when (this) {
		TemplateProvenance.BUNDLED -> R.string.template_provenance_bundled
		TemplateProvenance.PLUGIN -> R.string.template_provenance_plugin
		TemplateProvenance.USER -> R.string.template_provenance_user
	}
