package com.itsaky.androidide.ui.compose.plugins

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.R
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.templates.manager.models.versionLabel
import com.itsaky.androidide.ui.compose.common.FileImage
import com.itsaky.androidide.utils.isSystemInDarkMode
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginListItem(
	plugin: PluginInfo,
	onEnable: () -> Unit,
	onDisable: () -> Unit,
	onUninstall: () -> Unit,
	onDetails: () -> Unit,
	onLongPressTooltip: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var menuExpanded by remember { mutableStateOf(false) }
	val context = LocalContext.current

	Card(
		modifier =
			modifier
				.fillMaxWidth()
				.combinedClickable(onClick = onDetails, onLongClick = onLongPressTooltip),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			val iconPath =
				if (context.isSystemInDarkMode()) {
					plugin.metadata.iconNightPath
				} else {
					plugin.metadata.iconDayPath
				}
			FileImage(
				file = iconPath?.let(::File),
				placeholder = painterResource(R.drawable.ic_extension),
				contentDescription = null,
				modifier = Modifier.size(40.dp),
			)

			Spacer(Modifier.width(16.dp))

			Column(modifier = Modifier.weight(1f)) {
				Text(plugin.metadata.name, style = MaterialTheme.typography.titleMedium)
				Text(
					plugin.metadata.description,
					style = MaterialTheme.typography.bodySmall,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Row {
					val versionText = versionLabel(plugin.metadata.version)
					if (versionText.isNotBlank()) {
						Text(versionText, style = MaterialTheme.typography.labelSmall)
						Spacer(Modifier.width(8.dp))
					}
					Text(
						stringResource(R.string.by_author, plugin.metadata.author),
						style = MaterialTheme.typography.labelSmall,
					)
				}

				val (statusText, statusColor) =
					when {
						!plugin.isLoaded -> stringResource(R.string.status_not_loaded) to colorResource(R.color.error)
						!plugin.isEnabled -> stringResource(R.string.status_disabled) to colorResource(R.color.warning)
						else -> stringResource(R.string.status_enabled) to colorResource(R.color.success)
					}
				Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium)
			}

			Box {
				IconButton(onClick = { menuExpanded = true }) {
					Icon(
						painter = painterResource(R.drawable.ic_more_vert),
						contentDescription = stringResource(R.string.cd_more_options),
					)
				}
				DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
					if (plugin.isLoaded) {
						if (plugin.isEnabled) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.disable_plugin)) },
								onClick = {
									menuExpanded = false
									onDisable()
								},
							)
						} else {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.enable_plugin)) },
								onClick = {
									menuExpanded = false
									onEnable()
								},
							)
						}
					}
					DropdownMenuItem(
						text = { Text(stringResource(R.string.uninstall_plugin)) },
						onClick = {
							menuExpanded = false
							onUninstall()
						},
					)
					DropdownMenuItem(
						text = { Text(stringResource(R.string.plugin_details)) },
						onClick = {
							menuExpanded = false
							onDetails()
						},
					)
				}
			}
		}
	}
}
