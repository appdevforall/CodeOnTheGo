package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.FileQuery
import com.itsaky.androidide.utils.ProjectSearchOptions
import com.itsaky.androidide.utils.walkProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.regex.PatternSyntaxException

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FileSearchViewModel(
	private val projectRoot: () -> File = { IProjectManager.getInstance().projectDir },
) : ViewModel() {
	private val query = MutableStateFlow("")
	private var projectFiles: List<String>? = null
	private val searchOpen = MutableStateFlow(false)

	val isSearchOpen: StateFlow<Boolean> = searchOpen.asStateFlow()

	val uiState: StateFlow<FileSearchUiState> =
		query
			.debounce { if (it.isEmpty()) 0L else QUERY_DEBOUNCE_MS }
			.mapLatest(::search)
			.flowOn(Dispatchers.IO)
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FileSearchUiState.Inactive)

	fun onQueryChanged(text: String) {
		query.value = text.trim()
	}

	fun toggleSearch() {
		searchOpen.update { !it }
	}

	private fun search(text: String): FileSearchUiState {
		if (text.isEmpty()) {
			projectFiles = null
			return FileSearchUiState.Inactive
		}

		val fileQuery =
			try {
				FileQuery.parse(text)
			} catch (e: PatternSyntaxException) {
				return FileSearchUiState.InvalidPattern(e.description)
			}

		val root = projectRoot()
		val files =
			projectFiles ?: walkProjectFiles(root, ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES).also { projectFiles = it }
		return FileSearchUiState.Results(root, fileQuery.search(files), fileQuery is FileQuery.Glob)
	}

	private companion object {
		const val QUERY_DEBOUNCE_MS = 100L
		const val STOP_TIMEOUT_MS = 5_000L
	}
}
