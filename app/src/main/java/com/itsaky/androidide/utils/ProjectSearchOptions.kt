package com.itsaky.androidide.utils

import java.io.File

data class ProjectSearchOptions(
	val matchCase: Boolean = false,
	val wholeWord: Boolean = false,
	val bufferOverrides: Map<File, String> = emptyMap(),
	val excludedDirNames: Set<String> = emptySet(),
	val nameExclusionRoot: File? = null,
	val excludedDirs: Set<File> = emptySet(),
) {
	companion object {
		@JvmField
		val DEFAULT = ProjectSearchOptions()

		@JvmField
		val PROJECT_ROOT_EXCLUDED_DIR_NAMES = setOf("build", ".gradle", ".git", ".idea", ".kotlin", ".cxx")
	}
}
