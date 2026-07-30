package com.itsaky.androidide.ui.models

import androidx.annotation.StringRes
import com.itsaky.androidide.templates.manager.models.CgtFileItem

data class TemplateManagerUiState(
	val isLoading: Boolean = false,
	val items: List<CgtFileItem> = emptyList(),
) {
	val isEmpty: Boolean
		get() = items.isEmpty() && !isLoading
}

sealed class TemplateManagerUiEvent {
	object LoadTemplates : TemplateManagerUiEvent()

	data class InstallTemplate(
		val item: CgtFileItem,
	) : TemplateManagerUiEvent()

	data class UninstallTemplate(
		val item: CgtFileItem,
	) : TemplateManagerUiEvent()

	data class DeleteDownloadFile(
		val item: CgtFileItem,
	) : TemplateManagerUiEvent()

	data class ShowTemplateDetails(
		val item: CgtFileItem,
	) : TemplateManagerUiEvent()

	data class ShowTemplateList(
		val item: CgtFileItem,
	) : TemplateManagerUiEvent()
}

sealed class TemplateManagerUiEffect {
	data class ShowError(
		@StringRes val messageResId: Int,
		val formatArgs: List<Any> = emptyList(),
	) : TemplateManagerUiEffect()

	data class ShowSuccess(
		@StringRes val messageResId: Int,
	) : TemplateManagerUiEffect()

	data class ShowDeleteConfirmation(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()

	data class ShowTemplateDetails(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()

	data class ShowTemplateList(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()
}

sealed class TemplateOperation {
	object None : TemplateOperation()

	object Loading : TemplateOperation()

	data class Installing(
		val file: java.io.File,
	) : TemplateOperation()

	data class Uninstalling(
		val file: java.io.File,
	) : TemplateOperation()

	data class Deleting(
		val file: java.io.File,
	) : TemplateOperation()
}
