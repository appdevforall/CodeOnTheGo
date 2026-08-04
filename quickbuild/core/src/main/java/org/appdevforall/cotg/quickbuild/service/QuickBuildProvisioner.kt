package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout

/**
 * The session manager's door to the real Gradle world: the one-time proxy app build and
 * the full-Gradle rebuild fallback.
 *
 * Implemented in the app module over GradleBuildService and ApkInstaller. The interface
 * keeps `:quick-build` off CoGo's project-model modules and the session manager testable.
 */
interface QuickBuildProvisioner {
	/**
	 * Builds, installs, and resolves the uid of the proxy app for the first time.
	 *
	 * Must not throw: failures come back as [ProvisionOutcome.Failure] and surface in the
	 * UI.
	 */
	suspend fun provision(): ProvisionOutcome

	/**
	 * Rebuilds and reinstalls the proxy app after an invalidation, moving the session to
	 * the new baseline. The orchestrator's rebuild protocol brackets this call.
	 */
	suspend fun rebuildProxyApp(): ProxyAppRebuildOutcome

	/**
	 * Builds the proxy app eagerly at project open, after the normal Gradle sync, while
	 * its daemon is still warm.
	 *
	 * Installs nothing: the install waits for the first Quick Build tap, whose [provision]
	 * re-runs the build cheaply against current disk. Failures are logged, never surfaced,
	 * since the user did not ask for this build.
	 */
	suspend fun prebuildProxyApp() {}

	/**
	 * Stops the proxy app build currently running through Gradle.
	 *
	 * Cancelling the coroutine that awaits [provision], [prebuildProxyApp], or
	 * [rebuildProxyApp] does not stop Gradle, which runs out of process behind a future,
	 * so a stop has to reach the tooling server's cancellation token. Call only while the
	 * session owns the Gradle slot: the device has one cancellation token, so issuing it
	 * blind could kill a Standard Run instead.
	 *
	 * @return true when a cancellation reached Gradle; false, the default, means this
	 *   implementation cannot cancel and the caller must not claim it stopped anything
	 */
	fun cancelProxyAppBuild(): Boolean = false
}

/** What became of a [QuickBuildProvisioner.provision]. */
sealed interface ProvisionOutcome {
	data class Success(
		val proxyApp: ProxyAppInfo,
		/** PackageManager uid of the installed proxy app; the deploy-channel gate. */
		val proxyAppUid: Int,
		val layout: QuickBuildProjectLayout,
	) : ProvisionOutcome

	data class Failure(
		val message: String,
	) : ProvisionOutcome
}

/** What became of a [QuickBuildProvisioner.rebuildProxyApp]. */
sealed interface ProxyAppRebuildOutcome {
	/**
	 * Carries the re-read proxy app report and the layout derived from it.
	 *
	 * A rebuild regenerates setup.json, so the live session must rebuild its
	 * ProxyAppInfo-derived state from this. Keeping the provisioning-time snapshot would
	 * leave the deploy policy blind to components the rebuild just added.
	 */
	data class Success(
		val proxyApp: ProxyAppInfo,
		val layout: QuickBuildProjectLayout,
	) : ProxyAppRebuildOutcome

	data class Failure(
		val message: String,
	) : ProxyAppRebuildOutcome

	/**
	 * The Gradle build produced a good APK but the OS install confirmation was never
	 * given (see [InstallOutcome.ConfirmationNotGiven]); [message] carries the
	 * case-specific user-facing text.
	 *
	 * Distinct from [Failure] because nothing needs fixing: re-running the rebuild is
	 * cheap and simply re-prompts, so the session manager parks in a retryable state
	 * instead of tearing down.
	 */
	data class InstallNotConfirmed(
		val message: String,
	) : ProxyAppRebuildOutcome

	/**
	 * The rebuild never started because the device's single Gradle slot was taken, by
	 * CoGo's own project sync or a Standard Run.
	 *
	 * Nothing was built, installed, or prompted, so this is not a failure to report and
	 * does not count against the bounded auto-retry budget. The session parks and a later
	 * trigger runs it.
	 */
	data object BuildSlotBusy : ProxyAppRebuildOutcome
}
