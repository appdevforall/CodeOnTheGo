package com.itsaky.androidide.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.repositories.DownloadFilesRepository
import com.itsaky.androidide.ui.models.AddonFileKind
import com.itsaky.androidide.ui.models.DownloadPickerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Manager screen's install picker: the `.cgp` or `.cgt` files in Downloads,
 * matching the open tab. Picking a file is routed by the screen, like a system-picker result.
 */
class DownloadPickerViewModel(
	private val downloadFilesRepository: DownloadFilesRepository,
) : ViewModel() {
	private val _uiState = MutableStateFlow<DownloadPickerUiState>(DownloadPickerUiState.Hidden)
	val uiState: StateFlow<DownloadPickerUiState> = _uiState.asStateFlow()

	private var loadJob: Job? = null

	fun open(kind: AddonFileKind) {
		loadJob?.cancel()
		_uiState.value = DownloadPickerUiState.Open(kind)
		loadJob =
			viewModelScope.launch {
				val files = downloadFilesRepository.list(kind.extension)
				// A dismiss or a reopen for the other tab may have happened meanwhile.
				_uiState.update { state ->
					if (state is DownloadPickerUiState.Open && state.kind == kind) state.copy(files = files) else state
				}
			}
	}

	fun dismiss() {
		loadJob?.cancel()
		_uiState.value = DownloadPickerUiState.Hidden
	}
}
