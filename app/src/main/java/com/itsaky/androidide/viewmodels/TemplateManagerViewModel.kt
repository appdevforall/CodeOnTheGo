package com.itsaky.androidide.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.repositories.TemplateRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.displayName
import com.itsaky.androidide.ui.models.TemplateManagerUiEffect
import com.itsaky.androidide.ui.models.TemplateManagerUiEvent
import com.itsaky.androidide.ui.models.TemplateManagerUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Templates tab. Same UDF shape as [PluginManagerViewModel].
 */
class TemplateManagerViewModel(
	private val templateRepository: TemplateRepository,
) : ViewModel() {
	private companion object {
		private const val TAG = "TemplateManagerViewModel"
	}

	private val _uiState = MutableStateFlow(TemplateManagerUiState())
	val uiState: StateFlow<TemplateManagerUiState> = _uiState.asStateFlow()

	private val _uiEffect = Channel<TemplateManagerUiEffect>(Channel.BUFFERED)
	val uiEffect = _uiEffect.receiveAsFlow()

	init {
		loadTemplates()
	}

	fun onEvent(event: TemplateManagerUiEvent) {
		when (event) {
			is TemplateManagerUiEvent.LoadTemplates -> loadTemplates()
			is TemplateManagerUiEvent.InstallTemplate -> installTemplate(event.item)
			is TemplateManagerUiEvent.UninstallTemplate -> uninstallTemplate(event.item)
			is TemplateManagerUiEvent.DeleteDownloadFile -> showDeleteConfirmation(event.item)
			is TemplateManagerUiEvent.ShowTemplateDetails -> showTemplateDetails(event.item)
			is TemplateManagerUiEvent.ShowTemplateList -> showTemplateList(event.item)
		}
	}

	private fun loadTemplates() {
		viewModelScope.launch {
			_uiState.update { it.copy(isLoading = true) }

			templateRepository
				.listTemplateFiles()
				.onSuccess { items ->
					Log.d(TAG, "Loaded ${items.size} template files")
					_uiState.update { it.copy(isLoading = false, items = items) }
				}.onFailure { exception ->
					Log.e(TAG, "Failed to load template files", exception)
					_uiState.update { it.copy(isLoading = false) }
					_uiEffect.send(
						TemplateManagerUiEffect.ShowError(
							R.string.msg_template_load_failed,
							listOf(exception.message ?: ""),
						),
					)
				}
		}
	}

	private fun installTemplate(item: CgtFileItem) {
		viewModelScope.launch {
			_uiState.update { it.copy(isLoading = true) }
			templateRepository
				.installTemplate(item)
				.onSuccess {
					Log.d(TAG, "Template installed successfully: ${item.name}")
					_uiEffect.send(
						TemplateManagerUiEffect.ShowSuccess(
							R.string.msg_template_installed,
							listOf(item.displayName),
						),
					)
					// loadTemplates() clears isLoading once the post-install rescan completes, so the
					// indicator stays up continuously across both phases instead of flickering off
					// between them.
					loadTemplates()
				}.onFailure { exception ->
					Log.e(TAG, "Failed to install template: ${item.name}", exception)
					_uiState.update { it.copy(isLoading = false) }
					_uiEffect.send(
						TemplateManagerUiEffect.ShowError(
							R.string.msg_template_install_failed,
							listOf(exception.message ?: ""),
						),
					)
				}
		}
	}

	private fun uninstallTemplate(item: CgtFileItem) {
		viewModelScope.launch {
			_uiState.update { it.copy(isLoading = true) }
			templateRepository
				.uninstallTemplate(item)
				.onSuccess {
					Log.d(TAG, "Template uninstalled successfully: ${item.name}")
					_uiEffect.send(TemplateManagerUiEffect.ShowSuccess(R.string.msg_template_uninstalled))
					loadTemplates()
				}.onFailure { exception ->
					Log.e(TAG, "Failed to uninstall template: ${item.name}", exception)
					_uiState.update { it.copy(isLoading = false) }
					_uiEffect.send(
						TemplateManagerUiEffect.ShowError(
							R.string.msg_template_uninstall_failed,
							listOf(exception.message ?: ""),
						),
					)
				}
		}
	}

	private fun showDeleteConfirmation(item: CgtFileItem) {
		viewModelScope.launch {
			_uiEffect.send(TemplateManagerUiEffect.ShowDeleteConfirmation(item))
		}
	}

	/** Deletes a not-installed template's Downloads file (called after confirmation). */
	fun confirmDeleteDownloadFile(item: CgtFileItem) {
		viewModelScope.launch {
			_uiState.update { it.copy(isLoading = true) }
			templateRepository
				.deleteDownloadFile(item)
				.onSuccess {
					Log.d(TAG, "Deleted download file: ${item.name}")
					_uiEffect.send(TemplateManagerUiEffect.ShowSuccess(R.string.msg_template_deleted))
					loadTemplates()
				}.onFailure { exception ->
					Log.e(TAG, "Failed to delete download file: ${item.name}", exception)
					_uiState.update { it.copy(isLoading = false) }
					_uiEffect.send(
						TemplateManagerUiEffect.ShowError(
							R.string.msg_template_delete_failed,
							listOf(exception.message ?: ""),
						),
					)
				}
		}
	}

	private fun showTemplateDetails(item: CgtFileItem) {
		viewModelScope.launch {
			_uiEffect.send(TemplateManagerUiEffect.ShowTemplateDetails(item))
		}
	}

	private fun showTemplateList(item: CgtFileItem) {
		viewModelScope.launch {
			_uiEffect.send(TemplateManagerUiEffect.ShowTemplateList(item))
		}
	}
}
