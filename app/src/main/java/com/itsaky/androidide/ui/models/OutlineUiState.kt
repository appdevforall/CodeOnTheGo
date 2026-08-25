package com.itsaky.androidide.ui.models

import com.itsaky.androidide.editor.language.outline.OutlineSymbol
import com.itsaky.androidide.models.Position

sealed interface OutlineUiState {
	data object NoFileOpen : OutlineUiState

	data class Unsupported(
		val fileName: String,
	) : OutlineUiState

	data class Loading(
		val fileName: String,
	) : OutlineUiState

	data class Empty(
		val fileName: String,
	) : OutlineUiState

	data class Content(
		val fileName: String,
		val symbols: List<OutlineSymbol>,
		val collapsedPaths: Set<String>,
	) : OutlineUiState
}

sealed interface OutlineUiEvent {
	data class SymbolClicked(
		val symbol: OutlineSymbol,
	) : OutlineUiEvent

	data class ToggleCollapsed(
		val path: String,
	) : OutlineUiEvent
}

sealed interface OutlineUiEffect {
	data class NavigateTo(
		val position: Position,
	) : OutlineUiEffect
}
