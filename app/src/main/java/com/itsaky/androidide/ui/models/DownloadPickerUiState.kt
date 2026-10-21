package com.itsaky.androidide.ui.models

import com.itsaky.androidide.repositories.DownloadedFile
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION

/** Which add-on file type the Manager screen's install picker offers. */
enum class AddonFileKind(
	val extension: String,
) {
	PLUGIN(PLUGIN_ARCHIVE_EXTENSION),
	TEMPLATE(TEMPLATE_ARCHIVE_EXTENSION),
}

sealed interface DownloadPickerUiState {
	data object Hidden : DownloadPickerUiState

	/** [files] is null while the Downloads folder is still being read. */
	data class Open(
		val kind: AddonFileKind,
		val files: List<DownloadedFile>? = null,
	) : DownloadPickerUiState
}
