package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.SetupInfo

/**
 * The session manager's door to the real Gradle world: the one-time setup build
 * (plan 2.2) and the full-Gradle re-baseline fallback. Implemented in the app module
 * (GradleBuildService + ApkInstaller); an interface here keeps `:quick-build` off
 * CoGo's project-model modules and the session manager testable.
 */
interface QuickBuildProvisioner {
	/**
	 * Runs the setup build (`assembleDebug` with the quick-build -P properties),
	 * installs the produced test app and resolves its uid. Must not throw - failures
	 * come back as [ProvisionOutcome.Failure] and surface in the UI.
	 */
	suspend fun provision(): ProvisionOutcome

	/**
	 * Re-runs the setup build after an invalidation (manifest/gradle change) and
	 * reinstalls, moving the session baseline. The orchestrator's rebaseline protocol
	 * (onRebaselineStarted/onBaselineReset/onRebaselineFailed) brackets this call.
	 */
	suspend fun rebaseline(): RebaselineOutcome

	/**
	 * Best-effort eager setup build (plan B2): runs at project open, AFTER the normal
	 * Gradle sync, riding its warm daemon. Installs NOTHING - the install is deferred to
	 * the first Quick Build tap, whose [provision] re-runs the setup build (fast: tasks
	 * come back up-to-date) so it always reads current disk. Failures are logged, never
	 * surfaced - the user did not ask for this build; a real tap reports real errors.
	 */
	suspend fun warmSetupBuild() {}
}

sealed interface ProvisionOutcome {
	data class Success(
		val setup: SetupInfo,
		/** PackageManager uid of the installed test app; the deploy-channel gate. */
		val testAppUid: Int,
		val layout: QuickBuildProjectLayout,
	) : ProvisionOutcome

	data class Failure(
		val message: String,
	) : ProvisionOutcome
}

sealed interface RebaselineOutcome {
	data object Success : RebaselineOutcome

	data class Failure(
		val message: String,
	) : RebaselineOutcome
}
