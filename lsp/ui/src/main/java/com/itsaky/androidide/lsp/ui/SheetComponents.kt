package com.itsaky.androidide.lsp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Shared by every refactoring sheet in either language; none of them owns these. */
@Composable
fun LabelledSection(
	label: String,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(text = label, style = MaterialTheme.typography.labelLarge)
		content()
	}
}

/** A radio group. Expression text is monospaced so a candidate reads as the code it is. */
@Composable
fun OptionList(
	options: List<String>,
	selected: Int,
	monospace: Boolean,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.selectableGroup(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		options.forEachIndexed { index, option ->
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier =
					Modifier
						.fillMaxWidth()
						.selectable(
							selected = index == selected,
							role = Role.RadioButton,
							onClick = { onSelect(index) },
						),
			) {
				RadioButton(
					selected = index == selected,
					onClick = null,
				)

				Text(
					text = option,
					style =
						if (monospace) {
							MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
						} else {
							MaterialTheme.typography.bodyMedium
						},
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}
	}
}
