package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineLabel
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineMode
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineVariablePlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.labelFor
import com.itsaky.androidide.lsp.kotlin.utils.refactor.substitutionTextFor
import com.itsaky.androidide.resources.R

/** What the sheet reports back up; it never touches the document itself. */
sealed interface InlineVariableUiEvent {
	/** The user picked [mode]. */
	data class ModeChosen(
		val mode: InlineMode,
	) : InlineVariableUiEvent

	/** The user dismissed the sheet without picking a mode. */
	data object Dismissed : InlineVariableUiEvent
}

/**
 * The inline-variable sheet: one button per available mode, and the substitution text.
 *
 * Stateless and ViewModel-free: there is no mutable state to own -- an immutable plan, two
 * derived labels and three events. A ViewModel holding nothing would be ceremony, and its test would
 * assert that a constant is a constant.
 */
@Composable
fun InlineVariableSheetContent(
	plan: InlineVariablePlan,
	onEvent: (InlineVariableUiEvent) -> Unit,
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
			text = stringResource(R.string.title_inline_variable),
			style = MaterialTheme.typography.titleLarge,
		)

		plan.modes.forEach { mode ->
			Button(
				onClick = { onEvent(InlineVariableUiEvent.ModeChosen(mode)) },
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(plan.labelFor(mode).text())
			}
		}

		LabelledSection(stringResource(R.string.label_inline_variable_value)) {
			Text(
				// The cursor's own reference: the sheet is shown only when the cursor is on an inlinable one.
				text = substitutionTextFor(plan, plan.references[plan.cursorReferenceIndex]),
				style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
				modifier = Modifier.fillMaxWidth(),
			)
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			TextButton(onClick = { onEvent(InlineVariableUiEvent.Dismissed) }) {
				Text(stringResource(android.R.string.cancel))
			}
		}
	}
}

/**
 * The localised form of a label derived beside the plan. The derivation lives with the plan so it
 * cannot drift from what the edit does; only the wording lives here.
 */
@Composable
private fun InlineLabel.text(): String =
	when (this) {
		InlineLabel.ThisReferenceOnly -> {
			stringResource(R.string.label_inline_variable_this_reference)
		}

		is InlineLabel.AllAndDelete -> {
			stringResource(R.string.label_inline_variable_all_and_delete, count, name)
		}

		is InlineLabel.AllKeepingDeclaration -> {
			stringResource(R.string.label_inline_variable_all_keeping, count, name)
		}

		is InlineLabel.PartialKeepingDeclaration -> {
			stringResource(R.string.label_inline_variable_partial, count, total, name)
		}
	}
