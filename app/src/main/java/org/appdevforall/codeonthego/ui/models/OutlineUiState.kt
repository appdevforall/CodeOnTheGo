package org.appdevforall.codeonthego.ui.models

import org.appdevforall.codeonthego.editor.language.outline.OutlineSymbol
import org.appdevforall.codeonthego.models.Position

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
