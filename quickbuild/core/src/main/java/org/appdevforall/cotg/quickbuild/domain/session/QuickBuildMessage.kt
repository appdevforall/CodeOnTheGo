package org.appdevforall.cotg.quickbuild.domain.session

/**
 * A failure the session needs the host to flash, named rather than written.
 *
 * Same reason as [QuickBuildNotice]: this module has no `R`, so copy written here would ship
 * untranslated into an IDE that has a dozen locales. Each case names a situation and carries only
 * the values the wording needs; the host maps it to a string resource. [Literal] is the deliberate
 * exception - text nothing can translate (a PackageManager verdict, an exception message) or that
 * the host already resolved from its own resources.
 */
sealed interface QuickBuildMessage {
	/**
	 * Final text, passed through untouched.
	 *
	 * @property text host-resolved copy, or an opaque detail from Android or a thrown exception -
	 *   never a sentence written in this module, which is what the named cases are for.
	 */
	data class Literal(
		val text: String,
	) : QuickBuildMessage

	/**
	 * The OS wants the reinstall confirmed but no dialog could be shown, because CoGo was not
	 * in the foreground to host it. Returning to CoGo is what re-prompts.
	 */
	data object ReinstallReturnToCoGo : QuickBuildMessage

	/** The reinstall dialog was shown and the user declined it. */
	data object ReinstallDeclined : QuickBuildMessage

	/**
	 * The reinstall dialog was shown and went unanswered until the installer timed out.
	 *
	 * @property seconds how long it waited, in whole seconds, because the wording names it
	 */
	data class ReinstallTimedOut(
		val seconds: Long,
	) : QuickBuildMessage

	/**
	 * A reinstall retry could not get the Gradle slot, usually to the project sync that the
	 * invalidating edit triggered. The app still needs its reinstall.
	 */
	data object ReinstallWaitingForGradle : QuickBuildMessage

	/** The installer could not even launch the install. */
	data object InstallCouldNotStart : QuickBuildMessage

	/** The install ran and the OS reported a failure with nothing more specific to say. */
	data object InstallFailed : QuickBuildMessage

	/**
	 * The install reported success but PackageManager will not resolve the package, so there
	 * is no uid to open the deploy channel with.
	 *
	 * @property packageName the proxy app package that cannot be resolved
	 */
	data class InstalledButUnresolvable(
		val packageName: String,
	) : QuickBuildMessage

	/**
	 * The app already installed under the project's own applicationId was built by something
	 * other than this device's CoGo, so Quick Build would have to delete it and its data.
	 *
	 * @property applicationId the occupied applicationId, named so the user knows what to back
	 *   up before uninstalling it themselves
	 */
	data class ForeignAppInstalled(
		val applicationId: String,
	) : QuickBuildMessage

	/** The proxy app rebuild failed with no more specific cause to report. */
	data object RebuildFailed : QuickBuildMessage

	/**
	 * App storage is too tight to hold the build's intermediates. Checked up front so this
	 * fails in seconds rather than minutes into a build.
	 *
	 * @property requiredMb what the guard wants free, in MB
	 * @property availableMb what is actually free, in MB
	 */
	data class NotEnoughStorage(
		val requiredMb: Long,
		val availableMb: Long,
	) : QuickBuildMessage

	/**
	 * The scratch tree could not be created, so the pipeline has nowhere to write.
	 *
	 * @property path the location that could not be created, which is diagnostic but is the
	 *   only thing that distinguishes one of these from another
	 */
	data class ScratchDirUnavailable(
		val path: String,
	) : QuickBuildMessage

	/** The compile daemon refused the configuration it was started with. */
	data object DaemonRejectedConfiguration : QuickBuildMessage

	/**
	 * The compile daemon died and could not be restarted, so the session stays degraded until
	 * the next tap or a session restart retries.
	 *
	 * @property detail the respawn failure's own text, which is diagnostic rather than
	 *   translatable
	 */
	data class DaemonRestartFailed(
		val detail: String,
	) : QuickBuildMessage

	/**
	 * A Quick Build tap while the compiler is down is retrying the restart.
	 *
	 * The tap's own acknowledgement, so that it is never silent. The respawn it triggers can be
	 * superseded by one already in flight, which reports nothing, and the status reads "restarting
	 * the compiler" either way - so without this the tap would look ignored.
	 */
	data object DaemonRestartRetrying : QuickBuildMessage
}
