package com.itsaky.androidide.ui.outline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsaky.androidide.R
import com.itsaky.androidide.editor.language.outline.OutlineSymbolKind
import com.itsaky.androidide.ui.models.OutlineUiEvent
import com.itsaky.androidide.ui.models.OutlineUiState
import com.itsaky.androidide.viewmodel.OutlineViewModel

private const val MAX_INDENT_DEPTH = 6
private val INDENT_STEP = 14.dp

private val TYPE_BADGE_LIGHT = Color(0xFF6650C4)
private val TYPE_BADGE_DARK = Color(0xFFB9A8FF)
private val CALLABLE_BADGE_LIGHT = Color(0xFF0B6E77)
private val CALLABLE_BADGE_DARK = Color(0xFF7FD8DF)
private val DATA_BADGE_LIGHT = Color(0xFFA05A00)
private val DATA_BADGE_DARK = Color(0xFFF0B45C)

@Composable
private fun badgeColorFor(kind: OutlineSymbolKind): Color {
	val dark = isSystemInDarkTheme()
	return when (kind) {
		OutlineSymbolKind.METHOD,
		OutlineSymbolKind.CONSTRUCTOR,
		-> if (dark) CALLABLE_BADGE_DARK else CALLABLE_BADGE_LIGHT

		OutlineSymbolKind.FIELD,
		OutlineSymbolKind.PROPERTY,
		OutlineSymbolKind.ENUM_MEMBER,
		-> if (dark) DATA_BADGE_DARK else DATA_BADGE_LIGHT

		else -> if (dark) TYPE_BADGE_DARK else TYPE_BADGE_LIGHT
	}
}

@Composable
fun OutlinePanel(
	viewModel: OutlineViewModel,
	modifier: Modifier = Modifier,
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	OutlinePanelContent(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
internal fun OutlinePanelContent(
	state: OutlineUiState,
	onEvent: (OutlineUiEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	when (state) {
		is OutlineUiState.NoFileOpen -> {
			CenteredMessage(stringResource(R.string.outline_no_file_open), modifier)
		}

		is OutlineUiState.Unsupported -> {
			CenteredMessage(stringResource(R.string.outline_unsupported, state.fileName), modifier)
		}

		is OutlineUiState.Loading -> {
			CenteredMessage(stringResource(R.string.outline_loading), modifier)
		}

		is OutlineUiState.Empty -> {
			CenteredMessage(stringResource(R.string.outline_empty), modifier)
		}

		is OutlineUiState.Content -> {
			OutlineTree(state, onEvent, modifier)
		}
	}
}

@Composable
private fun CenteredMessage(
	message: String,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.padding(24.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = message,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun OutlineTree(
	state: OutlineUiState.Content,
	onEvent: (OutlineUiEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	val rows =
		remember(state.symbols, state.collapsedPaths) {
			flattenOutline(state.symbols, state.collapsedPaths)
		}
	LazyColumn(modifier = modifier.fillMaxSize()) {
		items(rows, key = { it.path }) { row ->
			OutlineRow(row = row, onEvent = onEvent)
		}
	}
}

@Composable
private fun OutlineRow(
	row: OutlineRowModel,
	onEvent: (OutlineUiEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	val symbol = row.symbol
	val kindLabel =
		symbol.kind.name
			.lowercase()
			.replace('_', ' ')
	val rowDescription = listOfNotNull(kindLabel, symbol.name, symbol.detail).joinToString(", ")
	val cappedDepth = minOf(row.depth, MAX_INDENT_DEPTH)
	val indent = INDENT_STEP * cappedDepth
	val guideColor = MaterialTheme.colorScheme.outlineVariant
	Row(
		verticalAlignment = Alignment.Top,
		modifier =
			modifier
				.fillMaxWidth()
				.clickable { onEvent(OutlineUiEvent.SymbolClicked(symbol)) }
				.drawBehind {
					val step = INDENT_STEP.toPx()
					val stroke = 1.dp.toPx()
					val base = 15.dp.toPx()
					for (level in 0 until cappedDepth) {
						val x = base + level * step
						drawLine(
							color = guideColor,
							start = Offset(x, 0f),
							end = Offset(x, size.height),
							strokeWidth = stroke,
						)
					}
				}.padding(start = 4.dp + indent, top = 5.dp, bottom = 5.dp, end = 12.dp)
				.semantics { contentDescription = rowDescription },
	) {
		if (row.hasChildren) {
			val toggleDescription =
				stringResource(
					if (row.collapsed) R.string.cd_outline_expand else R.string.cd_outline_collapse,
				)
			Icon(
				imageVector = Icons.Filled.ArrowDropDown,
				contentDescription = toggleDescription,
				modifier =
					Modifier
						.size(22.dp)
						.rotate(if (row.collapsed) -90f else 0f)
						.clickable { onEvent(OutlineUiEvent.ToggleCollapsed(row.path)) },
			)
		} else {
			Spacer(modifier = Modifier.size(22.dp))
		}
		Text(
			text = symbol.kind.badge,
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Bold,
			style = MaterialTheme.typography.bodyMedium,
			color = badgeColorFor(symbol.kind),
			modifier = Modifier.padding(start = 2.dp, end = 8.dp),
		)
		Text(
			text = symbol.name,
			style = MaterialTheme.typography.bodyMedium,
		)
		symbol.detail?.let { detail ->
			Text(
				text = detail,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier =
					Modifier
						.weight(1f, fill = false)
						.padding(start = 8.dp, top = 2.dp),
			)
		}
	}
}
