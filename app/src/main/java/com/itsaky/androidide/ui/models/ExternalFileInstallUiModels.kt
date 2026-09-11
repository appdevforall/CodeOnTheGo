package com.itsaky.androidide.ui.models

import androidx.annotation.StringRes
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import java.io.File

sealed class ExternalFileInstallUiEvent {
	data class ConfirmTemplateInstall(
		val tempFile: File,
		val targetBaseName: String,
		val overwrite: Boolean,
	) : ExternalFileInstallUiEvent()

	data class IgnoreTemplateInstall(
		val tempFile: File,
	) : ExternalFileInstallUiEvent()
}

sealed class ExternalFileInstallUiEffect {
	data class ForwardToPluginManager(
		val filePath: String,
	) : ExternalFileInstallUiEffect()

	data class ShowTemplateInstallConfirmation(
		val info: TemplateCollectionRepository.CollectionInfo,
		val tempFile: File,
		val suggestedBaseName: String,
	) : ExternalFileInstallUiEffect()

	data class ShowTemplateNameConflict(
		val existingName: String,
		val info: TemplateCollectionRepository.CollectionInfo,
		val tempFile: File,
	) : ExternalFileInstallUiEffect()

	data class ShowError(
		@StringRes val messageResId: Int,
		val formatArgs: List<Any> = emptyList(),
	) : ExternalFileInstallUiEffect()

	data class ShowSuccess(
		@StringRes val messageResId: Int,
		val formatArgs: List<Any> = emptyList(),
	) : ExternalFileInstallUiEffect() {
		constructor(
			@StringRes messageResId: Int,
			vararg formatArgs: Any,
		) : this(messageResId, formatArgs.toList())
	}

	object Finish : ExternalFileInstallUiEffect()
}
