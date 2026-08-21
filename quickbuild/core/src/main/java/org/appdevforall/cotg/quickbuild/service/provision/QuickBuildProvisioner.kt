package org.appdevforall.cotg.quickbuild.service.provision

import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage

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
	 *
	 * @return the baseline, its uid, and the layout on success; a message on failure
	 */
	suspend fun provision(): ProvisionOutcome

	/**
	 * Rebuilds and reinstalls the proxy app after an invalidation, moving the session to
	 * the new baseline. The orchestrator's rebuild protocol brackets this call.
	 *
	 * @return the re-read baseline and layout on success; otherwise a failure, an
	 *   unconfirmed install, or a busy Gradle slot, which callers must not conflate
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
	 * [rebuildProxyApp] does not stop Gradle, which runs out of process behind a future, so
	 * a stop must reach the tooling server's cancellation token. Call only while the session
	 * owns the Gradle slot: there is one token, so issuing it blind could kill a Standard Run.
	 *
	 * @return true when a cancellation reached Gradle; false, the default, means this
	 *   implementation cannot cancel and the caller must not claim it stopped anything
	 */
	fun cancelProxyAppBuild(): Boolean = false
}

/** What became of a [QuickBuildProvisioner.provision]. */
sealed interface ProvisionOutcome {
	/** The proxy app is built, installed, and identified; the session can be assembled. */
	data class Success(
		/** The report read from the setup.json this build generated. */
		val proxyApp: ProxyAppInfo,
		/** PackageManager uid of the installed proxy app; the deploy-channel gate. */
		val proxyAppUid: Int,
		/** Derived from the same setup.json as [proxyApp], never from an earlier one. */
		val layout: QuickBuildProjectLayout,
		/**
		 * Build variant this proxy app was built from ("debug", "demoDebug"), or null when
		 * the provisioner does not track one. The session records it so a later variant
		 * switch reprovisions instead of hot-reloading into the old variant's application
		 * id.
		 */
		val variantName: String? = null,
		/**
		 * The generation stamped into the installed APK's baseline, allocated from the
		 * project's persistent counter before the Gradle build ran; 0 for an unstamped
		 * build (a provisioner that does not stamp). The installed app boots at this
		 * number, so the session adopts it as the deployed generation.
		 */
		val baselineGeneration: Long = 0L,
	) : ProvisionOutcome

	/**
	 * Provisioning did not complete, for any reason from a Gradle failure to a declined
	 * install.
	 *
	 * @property message user-facing failure text; the session tears down and shows it
	 */
	data class Failure(
		val message: QuickBuildMessage,
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
		/** The re-read report, which may declare components the old baseline did not. */
		val proxyApp: ProxyAppInfo,
		/** Derived from the same re-read setup.json as [proxyApp]. */
		val layout: QuickBuildProjectLayout,
		/**
		 * The generation stamped into the reinstalled APK's baseline; 0 for an unstamped
		 * build. See [ProvisionOutcome.Success.baselineGeneration].
		 */
		val baselineGeneration: Long = 0L,
	) : ProxyAppRebuildOutcome

	/**
	 * The rebuild did not complete, so the session is still on the baseline that could not
	 * take the deploy.
	 *
	 * @property message user-facing failure text
	 */
	data class Failure(
		val message: QuickBuildMessage,
	) : ProxyAppRebuildOutcome

	/**
	 * The Gradle build produced a good APK but the OS install confirmation was never
	 * given (see [InstallOutcome.ConfirmationNotGiven]).
	 *
	 * Distinct from [Failure] because nothing needs fixing: re-running the rebuild is
	 * cheap and simply re-prompts, so the session manager parks in a retryable state
	 * instead of tearing down.
	 *
	 * @property message user-facing text specific to how the confirmation went missing, so
	 *   it should be shown alongside the retry rather than swapped for a generic prompt
	 */
	data class InstallNotConfirmed(
		val message: QuickBuildMessage,
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
