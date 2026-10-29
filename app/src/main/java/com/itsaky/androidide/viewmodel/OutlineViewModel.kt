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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class OutlineViewModel(
	private val outlineProvider: OutlineProvider,
	private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
	private data class Snapshot(
		val path: String,
		val extension: String,
		val text: String,
		val immediate: Boolean,
	) {
		val fileName: String get() = path.substringAfterLast('/')
	}

	private data class Collapsed(
		val path: String?,
		val paths: Set<String>,
	)

	private val snapshots = MutableStateFlow<Snapshot?>(null)
	private val _uiState = MutableStateFlow<OutlineUiState>(OutlineUiState.NoFileOpen)
	private val collapsed = MutableStateFlow(Collapsed(path = null, paths = emptySet()))
	private var lastComputedPath: String? = null

	val uiState: StateFlow<OutlineUiState> = _uiState.asStateFlow()
	val collapsedPaths: StateFlow<Set<String>> =
		collapsed.map { it.paths }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

	private val _effects = MutableSharedFlow<OutlineUiEffect>()
	val effects = _effects.asSharedFlow()

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
				}.collectLatest { snapshot ->
					try {
						compute(snapshot)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						log.error("Failed to refresh outline for {}", snapshot?.fileName, e)
					}
				}
		}
	}

	fun onSnapshot(
		path: String,
		extension: String,
		text: String,
		immediate: Boolean,
	) {
		snapshots.value = Snapshot(path, extension, text, immediate)
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
				collapsed.update { it.copy(paths = if (event.path in it.paths) it.paths - event.path else it.paths + event.path) }
			}
		}
	}

	private suspend fun compute(snapshot: Snapshot?) {
		if (snapshot == null) {
			lastComputedPath = null
			_uiState.value = OutlineUiState.NoFileOpen
			return
		}
		if (!outlineProvider.supports(snapshot.extension)) {
			lastComputedPath = snapshot.path
			_uiState.value = OutlineUiState.Unsupported(snapshot.fileName)
			return
		}
		collapsed.update { if (it.path == snapshot.path) it else Collapsed(snapshot.path, emptySet()) }
		if (lastComputedPath != snapshot.path) {
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
		lastComputedPath = snapshot.path
		_uiState.value =
			if (symbols.isEmpty()) {
				OutlineUiState.Empty(snapshot.fileName)
			} else {
				OutlineUiState.Content(snapshot.fileName, symbols)
			}
	}
}
