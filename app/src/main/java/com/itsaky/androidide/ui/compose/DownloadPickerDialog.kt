package com.itsaky.androidide.ui.compose

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.R
import com.itsaky.androidide.repositories.DownloadedFile
import com.itsaky.androidide.ui.models.AddonFileKind
import com.itsaky.androidide.ui.models.DownloadPickerUiState

/**
 * The + button's picker: the open tab's add-on files in Downloads, plus a way out to the system
 * picker for files kept anywhere else.
 *
 * Long-press on a file row or on Browse other folders calls [onShowHelp] with the dialog's own
 * view, to anchor the help popup above the dialog.
 */
@Composable
fun DownloadPickerDialog(
	state: DownloadPickerUiState.Open,
	onPick: (DownloadedFile) -> Unit,
	onBrowse: () -> Unit,
	onDismiss: () -> Unit,
	onShowHelp: (anchor: View) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = {
			Text(
				stringResource(
					when (state.kind) {
						AddonFileKind.PLUGIN -> R.string.title_choose_plugin_file
						AddonFileKind.TEMPLATE -> R.string.title_choose_template_file
					},
				),
			)
		},
		text = {
			val files = state.files
			when {
				files == null -> {
					Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
				}

				files.isEmpty() -> {
					Text(
						stringResource(R.string.msg_no_download_files, ".${state.kind.extension}"),
						style = MaterialTheme.typography.bodyMedium,
					)
				}

				// Scrolls: Downloads can hold many add-ons, and rows grow with the font size.
				else -> {
					LazyColumn {
						items(files, key = { it.file.absolutePath }) { file ->
							DownloadFileRow(file = file, onClick = { onPick(file) }, onShowHelp = onShowHelp)
							HorizontalDivider()
						}
					}
				}
			}
		},
		confirmButton = {
			val view = LocalView.current
			val longPressAwareClick = rememberLongPressInteractionSource { onShowHelp(view) }
			TextButton(
				onClick = {
					// TextButton's clickable() fires on the lift that ends the long press; drop that one.
					if (longPressAwareClick.consumeIfSuppressed()) return@TextButton
					onBrowse()
				},
				interactionSource = longPressAwareClick.interactionSource,
			) {
				Text(stringResource(R.string.action_browse_other_folders))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadFileRow(
	file: DownloadedFile,
	onClick: () -> Unit,
	onShowHelp: (anchor: View) -> Unit,
) {
	val context = LocalContext.current
	val view = LocalView.current
	val size = Formatter.formatShortFileSize(context, file.sizeBytes)
	val date =
		DateUtils.formatDateTime(
			context,
			file.lastModified,
			DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
		)
	Column(
		modifier =
			Modifier
				.fillMaxWidth()
				.combinedClickable(
					role = Role.Button,
					onClickLabel = stringResource(R.string.action_install),
					onLongClickLabel = stringResource(R.string.cd_show_tooltip),
					onClick = onClick,
					onLongClick = { onShowHelp(view) },
				).padding(vertical = 12.dp),
	) {
		// Not ellipsized: names like "fun-and-games-libgdx-v2.cgt" differ only at the end.
		Text(file.name, style = MaterialTheme.typography.titleSmall)
		Text(
			stringResource(R.string.label_download_file_details, size, date),
			style = MaterialTheme.typography.bodySmall,
		)
	}
}
