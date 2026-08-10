package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.resources.R

/**
 * The extract-variable sheet: one surface holding every choice, with no navigation between steps.
 *
 * Expression, name, scope and replace-all are interdependent -- picking a different expression changes
 * the scope list and the occurrence count -- so they are shown together, where that relationship is
 * visible, rather than across sequential dialogs the user would have to back out of to explore.
 *
 * Stateless: all state arrives in [state] and every interaction leaves as an [ExtractVariableUiEvent].
 */
@Composable
fun ExtractVariableSheetContent(
	state: ExtractVariableUiState,
	onEvent: (ExtractVariableUiEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier =
			modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(
			text = stringResource(R.string.title_extract_variable),
			style = MaterialTheme.typography.titleLarge,
		)

		if (state.showCandidatePicker) {
			LabelledSection(stringResource(R.string.label_extract_variable_expression)) {
				OptionList(
					options = state.candidateLabels,
					selected = state.selectedCandidate,
					monospace = true,
					onSelect = { onEvent(ExtractVariableUiEvent.CandidateSelected(it)) },
				)
			}
		}

		OutlinedTextField(
			value = state.name,
			onValueChange = { onEvent(ExtractVariableUiEvent.NameChanged(it)) },
			label = { Text(stringResource(R.string.label_extract_variable_name)) },
			isError = state.nameProblem != null,
			singleLine = true,
			supportingText = state.nameProblem?.let { problem -> { Text(stringResource(problem.messageRes())) } },
			modifier = Modifier.fillMaxWidth(),
		)

		if (state.showScopePicker) {
			LabelledSection(stringResource(R.string.label_extract_variable_scope)) {
				OptionList(
					options = state.scopeLabels,
					selected = state.selectedScope,
					monospace = false,
					onSelect = { onEvent(ExtractVariableUiEvent.ScopeSelected(it)) },
				)
			}
		}

		if (state.showReplaceAll) {
			val replaceAllLabel =
				pluralStringResource(
					R.plurals.label_extract_variable_replace_all,
					state.occurrenceCount,
					state.occurrenceCount,
				)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier =
					Modifier
						.fillMaxWidth()
						.toggleable(
							value = state.replaceAll,
							role = Role.Checkbox,
							onValueChange = { onEvent(ExtractVariableUiEvent.ReplaceAllChanged(it)) },
						),
			) {
				Checkbox(
					checked = state.replaceAll,
					// Null so the row, not the box, is the single accessibility target.
					onCheckedChange = null,
				)
				Text(
					text = replaceAllLabel,
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			TextButton(onClick = { onEvent(ExtractVariableUiEvent.Dismissed) }) {
				Text(stringResource(android.R.string.cancel))
			}
			Button(
				onClick = { onEvent(ExtractVariableUiEvent.Confirmed) },
				enabled = state.canConfirm,
				modifier = Modifier.padding(start = 8.dp),
			) {
				Text(stringResource(R.string.action_extract))
			}
		}
	}
}

@Composable
private fun LabelledSection(
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
private fun OptionList(
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

/** The message shown under the name field for each way a name can be unusable. */
internal fun NameProblem.messageRes(): Int =
	when (this) {
		NameProblem.Blank -> R.string.msg_extract_variable_name_blank
		NameProblem.NotAnIdentifier -> R.string.msg_extract_variable_name_invalid
		NameProblem.Keyword -> R.string.msg_extract_variable_name_keyword
		NameProblem.AlreadyTaken -> R.string.msg_extract_variable_name_taken
	}
