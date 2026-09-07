package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.lsp.kotlin.refactor.KOTLIN_NAME_MESSAGES
import com.itsaky.androidide.lsp.ui.LabelledSection
import com.itsaky.androidide.lsp.ui.OptionList
import com.itsaky.androidide.resources.R

/**
 * The extract-method sheet: the expression chooser (when there is a choice), the name, and the
 * signature exactly as it will be emitted.
 *
 * A sibling of the extract-variable sheet rather than a generalisation of it: a single shared sheet
 * would need a state class where half the fields are meaningless to either caller (ADR 0013).
 *
 * Stateless: all state arrives in [state] and every interaction leaves as an [ExtractMethodUiEvent].
 */
@Composable
fun ExtractMethodSheetContent(
	state: ExtractMethodUiState,
	onEvent: (ExtractMethodUiEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier =
			modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(
			text = stringResource(R.string.title_extract_method),
			style = MaterialTheme.typography.titleLarge,
		)

		if (state.showCandidatePicker) {
			LabelledSection(stringResource(R.string.label_extract_variable_expression)) {
				OptionList(
					options = state.candidateLabels,
					selected = state.selectedCandidate,
					monospace = true,
					onSelect = { onEvent(ExtractMethodUiEvent.CandidateSelected(it)) },
				)
			}
		}

		OutlinedTextField(
			value = state.name,
			onValueChange = { onEvent(ExtractMethodUiEvent.NameChanged(it)) },
			label = { Text(stringResource(R.string.label_extract_variable_name)) },
			isError = state.nameProblem != null,
			singleLine = true,
			supportingText =
				state.nameProblem?.let { problem ->
					{ Text(stringResource(KOTLIN_NAME_MESSAGES.resFor(problem))) }
				},
			modifier = Modifier.fillMaxWidth(),
		)

		LabelledSection(stringResource(R.string.label_extract_method_signature)) {
			Text(
				text = state.signaturePreview,
				style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
				modifier = Modifier.fillMaxWidth(),
			)
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			TextButton(onClick = { onEvent(ExtractMethodUiEvent.Dismissed) }) {
				Text(stringResource(android.R.string.cancel))
			}
			Button(
				onClick = { onEvent(ExtractMethodUiEvent.Confirmed) },
				enabled = state.canConfirm,
				modifier = Modifier.padding(start = 8.dp),
			) {
				Text(stringResource(R.string.action_extract))
			}
		}
	}
}
