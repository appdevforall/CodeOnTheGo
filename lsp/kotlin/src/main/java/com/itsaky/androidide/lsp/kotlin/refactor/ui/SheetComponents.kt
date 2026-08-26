package com.itsaky.androidide.lsp.kotlin.refactor.ui

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
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.resources.R

/** Shared by the extract-variable and extract-method sheets; neither owns them. */
@Composable
internal fun LabelledSection(
	label: String,
	content: @Composable () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(text = label, style = MaterialTheme.typography.labelLarge)
		content()
	}
}

/** A radio group. Expression text is monospaced so a candidate reads as the code it is. */
@Composable
internal fun OptionList(
	options: List<String>,
	selected: Int,
	monospace: Boolean,
	onSelect: (Int) -> Unit,
) {
	Column(
		modifier = Modifier.selectableGroup(),
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

/** The message shown under a name field for each way a name can be unusable. */
internal fun NameProblem.messageRes(): Int =
	when (this) {
		NameProblem.Blank -> R.string.msg_extract_variable_name_blank
		NameProblem.NotAnIdentifier -> R.string.msg_extract_variable_name_invalid
		NameProblem.Keyword -> R.string.msg_extract_variable_name_keyword
		NameProblem.AlreadyTaken -> R.string.msg_extract_variable_name_taken
	}
