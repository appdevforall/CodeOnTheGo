package com.itsaky.androidide.plugins.services

/**
 * Whether [IdeBuildService.startQuickBuild] can run for the open project, from
 * [IdeBuildService.getQuickBuildAvailability].
 */
sealed class QuickBuildAvailability {
	/** Quick Build can start. A start can still be refused, e.g. when another build is running. */
	data object Available : QuickBuildAvailability()

	/**
	 * Quick Build cannot start. [reason] is for the plugin to branch on; [message] is for the user.
	 */
	data class Unavailable(
		val reason: QuickBuildUnavailableReason,
		val message: String,
	) : QuickBuildAvailability()
}

/** Why Quick Build is [QuickBuildAvailability.Unavailable]. */
enum class QuickBuildUnavailableReason {
	/** The IDE predates the Quick Build API, or does not offer it. */
	NOT_SUPPORTED,

	/** Quick Build is experimental and the user has experiments turned off. */
	EXPERIMENTS_DISABLED,

	/** No project is open in the editor. */
	NO_PROJECT,

	/** The open project builds a plugin (`.cgp`), not an app. */
	PLUGIN_PROJECT,

	/** The project has no Android application module. */
	NO_APP_MODULE,

	/** The selected build variant is a release variant; Quick Build needs a debuggable one. */
	RELEASE_VARIANT,

	/** The project has not finished its Gradle sync. */
	PROJECT_SYNCING,
}

/**
 * What the Quick Build button shows, from [IdeBuildService.getQuickBuildStatus].
 */
enum class QuickBuildState {
	/** Quick Build is not available; see [IdeBuildService.getQuickBuildAvailability]. */
	UNAVAILABLE,

	/** Ready: no session yet, or the app runs the latest change. */
	READY,

	/** A Quick Build is running. [IdeBuildService.cancelQuickBuild] stops it. */
	BUILDING,

	/** The last change cannot be reloaded live, so the next Quick Build is a full build. */
	FULL_BUILD_NEXT,

	/** The Quick Build compiler is restarting. Clears by itself. */
	RECONNECTING,

	/** The last Quick Build failed and needs the user: a compile error, a crash, a failed install. */
	ERROR,
}

/**
 * Outcome of [IdeBuildService.startQuickBuild].
 */
sealed class QuickBuildResult {
	/**
	 * The app on the device now runs the change. [restarted] is true when the app process was
	 * relaunched (first start, or a change a live reload cannot apply) rather than reloaded in
	 * place. [durationMillis] is the time from the call to the change being live.
	 */
	data class Reloaded(
		val durationMillis: Long,
		val restarted: Boolean,
	) : QuickBuildResult()

	/** Nothing changed since the last Quick Build; the app already runs the latest code. */
	data object UpToDate : QuickBuildResult()

	/**
	 * The change built, but the app is not running to receive it. Starting Quick Build again
	 * launches it. [message] says so in words for the user.
	 */
	data class AppNotRunning(
		val message: String,
	) : QuickBuildResult()

	/**
	 * The Quick Build ran and failed. [reason] summarizes why (the first compile errors, a crash,
	 * a failed install); [output] is the Build Output pane's text, or null when there is none.
	 */
	data class Failed(
		val reason: String,
		val output: String?,
	) : QuickBuildResult()

	/** Quick Build never started. [reason] says why, e.g. another build is running. */
	data class Refused(
		val reason: String,
	) : QuickBuildResult()

	/** The user declined to replace the app installed under the project's application ID. */
	data object Declined : QuickBuildResult()

	/** The Quick Build was stopped, by [IdeBuildService.cancelQuickBuild] or by the user. */
	data object Cancelled : QuickBuildResult()
}
