package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.editor.language.outline.OutlineProvider
import com.itsaky.androidide.ui.models.OutlineUiEffect
import com.itsaky.androidide.ui.models.OutlineUiEvent
import com.itsaky.androidide.ui.models.OutlineUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class OutlineViewModel(
	private val outlineProvider: OutlineProvider,
	private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
	private data class Snapshot(
		val fileName: String,
		val extension: String,
		val text: String,
		val immediate: Boolean,
	)

	private val snapshots = MutableStateFlow<Snapshot?>(null)

	private val _uiState = MutableStateFlow<OutlineUiState>(OutlineUiState.NoFileOpen)
	val uiState = _uiState.asStateFlow()

	private val _effects = MutableSharedFlow<OutlineUiEffect>()
	val effects = _effects.asSharedFlow()

	private var collapsedPaths = emptySet<String>()
	private var collapsedForFile: String? = null

	companion object {
		private const val DEBOUNCE_MILLIS = 250L
		private val log = LoggerFactory.getLogger(OutlineViewModel::class.java)
	}

	init {
		viewModelScope.launch(computeDispatcher) {
			@OptIn(FlowPreview::class)
			snapshots
				.debounce { snapshot ->
					if (snapshot == null || snapshot.immediate) 0L else DEBOUNCE_MILLIS
				}.collectLatest { snapshot -> compute(snapshot) }
		}
	}

	fun onSnapshot(
		fileName: String,
		extension: String,
		text: String,
		immediate: Boolean,
	) {
		snapshots.value = Snapshot(fileName, extension, text, immediate)
	}

	fun onNoEditor() {
		snapshots.value = null
	}

	fun onEvent(event: OutlineUiEvent) {
		when (event) {
			is OutlineUiEvent.SymbolClicked -> {
				viewModelScope.launch {
					_effects.emit(OutlineUiEffect.NavigateTo(event.symbol.selectionRange.start))
				}
			}

			is OutlineUiEvent.ToggleCollapsed -> {
				toggleCollapsed(event.path)
			}
		}
	}

	private suspend fun compute(snapshot: Snapshot?) {
		if (snapshot == null) {
			_uiState.value = OutlineUiState.NoFileOpen
			return
		}
		if (!outlineProvider.supports(snapshot.extension)) {
			_uiState.value = OutlineUiState.Unsupported(snapshot.fileName)
			return
		}
		if (collapsedForFile != snapshot.fileName) {
			collapsedForFile = snapshot.fileName
			collapsedPaths = emptySet()
			_uiState.value = OutlineUiState.Loading(snapshot.fileName)
		}
		val symbols =
			try {
				outlineProvider.outlineOf(snapshot.extension, snapshot.text)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to compute outline for {}", snapshot.fileName, e)
				emptyList()
			}
		_uiState.value =
			if (symbols.isEmpty()) {
				OutlineUiState.Empty(snapshot.fileName)
			} else {
				OutlineUiState.Content(snapshot.fileName, symbols, collapsedPaths)
			}
	}

	private fun toggleCollapsed(path: String) {
		collapsedPaths = if (path in collapsedPaths) collapsedPaths - path else collapsedPaths + path
		_uiState.update { state ->
			if (state is OutlineUiState.Content) state.copy(collapsedPaths = collapsedPaths) else state
		}
	}
}
