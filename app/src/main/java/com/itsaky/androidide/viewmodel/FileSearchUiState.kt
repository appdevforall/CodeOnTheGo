package com.itsaky.androidide.viewmodel

import com.itsaky.androidide.utils.FileMatch
import java.io.File

sealed interface FileSearchUiState {
	data object Inactive : FileSearchUiState

	data class InvalidPattern(
		val reason: String,
	) : FileSearchUiState

	data class Results(
		val root: File,
		val matches: List<FileMatch>,
		val isGlob: Boolean,
	) : FileSearchUiState {
		val exceedsOpenLimit: Boolean
			get() = isGlob && matches.size > MAX_FILES_OPENED_AT_ONCE

		val filesOpenedByEnter: List<File>
			get() =
				when {
					!isGlob -> matches.take(1)
					exceedsOpenLimit -> emptyList()
					else -> matches
				}.map(::fileOf)

		fun fileOf(match: FileMatch): File = File(root, match.relativePath)

		companion object {
			const val MAX_FILES_OPENED_AT_ONCE = 20
		}
	}
}
