package com.itsaky.androidide.quickbuild

/**
 * Composes the Gradle task path for the quick-build setup build's `assembleDebug`
 * task from a module's Gradle project path.
 *
 * The root/single-module project's Gradle path is `:` (defensively, an empty string
 * is treated the same way) - naive `"$modulePath:assembleDebug"` composition then
 * produces `::assembleDebug`, which Gradle's task selector rejects with
 * `TaskSelectionException: Cannot locate tasks that match '::assembleDebug'`
 * (ADFA-4128 Bug 3: single-module projects, e.g. the Plugin template, have no `:app`
 * and hit this instantly).
 */
object QuickBuildTaskPaths {
	fun assembleDebug(modulePath: String): String =
		if (modulePath == ":" || modulePath.isBlank()) {
			":assembleDebug"
		} else {
			"$modulePath:assembleDebug"
		}
}
