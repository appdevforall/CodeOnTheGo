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
		val formatArgs: List<Any> = emptyList(),
	) : TemplateManagerUiEffect()

	data class ShowDeleteConfirmation(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()

	/** Ask before uninstalling [item], mirroring [ShowDeleteConfirmation]'s pattern for a Downloads file. */
	data class ShowUninstallConfirmation(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()

	/** A Downloads file with the same name already exists; ask before [uninstallTemplate][com.itsaky.androidide.repositories.TemplateRepository.uninstallTemplate] overwrites it. */
	data class ShowReplaceConfirmation(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()

	data class ShowTemplateDetails(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()

	data class ShowTemplateList(
		val item: CgtFileItem,
	) : TemplateManagerUiEffect()
}
