package com.itsaky.androidide.models

data class InstallTaskRequest(
	val modulePath: String?,
	val taskSuffix: String,
) {
	val assembleTaskName: String get() = "assemble$taskSuffix"
}

private val INSTALL_TASK = Regex("^install([A-Z]\\w*)$")

fun installTaskRequestsIn(tasks: List<String>): List<InstallTaskRequest> =
	tasks.mapNotNull { path ->
		val suffix = INSTALL_TASK.matchEntire(path.substringAfterLast(':'))?.groupValues?.get(1) ?: return@mapNotNull null
		if (suffix.endsWith("AndroidTest")) return@mapNotNull null
		InstallTaskRequest(
			modulePath = path.substringBeforeLast(':', "").takeIf { it.isNotEmpty() },
			taskSuffix = suffix,
		)
	}
