package org.appdevforall.cotg.quickbuild.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * What the installer needs to know about installed packages; implemented over
 * PackageManager in the app module, faked in tests.
 */
interface InstalledPackages {
	/** The package's uid, or null when not installed. */
	fun uid(packageName: String): Int?

	/** PackageInfo.lastUpdateTime, or null when not installed. */
	fun lastUpdateTime(packageName: String): Long?

	/** The installed base APK (sourceDir), or null when not installed. */
	fun apkFile(packageName: String): File?

	/** PackageInfo.longVersionCode, or null when not installed. */
	fun versionCode(packageName: String): Long?

	/**
	 * Lowercase hex SHA-256 of the package's current signing certificate, or null when
	 * not installed or unreadable. Null reads as "cannot verify" - the provisioner then
	 * refuses to clobber the occupant rather than guessing (see [RealIdInstall.signatureRefusal]).
	 */
	fun signingCertSha256(packageName: String): String?

	/**
	 * The installed package's `android:appComponentFactory` (ApplicationInfo.appComponentFactory,
	 * API 28+), or null when not installed or none is declared. A Quick Build test app carries
	 * the runtime factory here, which is how [RealIdInstall] tells it apart from the user's
	 * Standard-Run build under the same applicationId.
	 */
	fun appComponentFactory(packageName: String): String?
}

/**
 * One PackageInstaller status broadcast, decoupled from android.* so the wait logic is
 * JVM-testable. The app module maps InstallationResultReceiver's intent extras into this.
 */
data class InstallBroadcast(
	val packageName: String?,
	val status: Status,
	val message: String? = null,
) {
	/** [ABORTED] = STATUS_FAILURE_ABORTED: the user cancelled the confirm dialog. */
	enum class Status { SUCCESS, FAILURE, ABORTED, PENDING_USER_ACTION, OTHER }

	val isTerminal: Boolean
		get() = status == Status.SUCCESS || status == Status.FAILURE || status == Status.ABORTED
}

sealed interface InstallOutcome {
	/** The package is installed and current. [reinstalled] false = no dialog was shown. */
	data class Installed(
		val uid: Int,
		val reinstalled: Boolean,
	) : InstallOutcome

	data class Failed(
		val message: String,
	) : InstallOutcome

	/**
	 * The install started but the user's OS confirmation was never given. Distinct
	 * from [Failed]: nothing is broken - the built APK is fine and simply retrying
	 * the install re-prompts, so callers can offer a retry instead of failing hard.
	 *
	 * [reason] keeps the three ways this happens distinguishable (each carries its
	 * own user-facing [message]):
	 * - [Reason.DIALOG_NOT_SHOWN]: the confirm dialog could not be launched because
	 *   the host app was not foreground (its dialog-owning subscriber is
	 *   lifecycle-bound). Fail-fast: reported the moment PENDING_USER_ACTION arrives
	 *   with no dialog possible, not after a silent timeout.
	 * - [Reason.DECLINED]: the user cancelled the dialog (STATUS_FAILURE_ABORTED).
	 * - [Reason.TIMED_OUT]: the dialog was shown and simply never answered.
	 */
	data class ConfirmationNotGiven(
		val message: String,
		val reason: Reason,
	) : InstallOutcome {
		enum class Reason { DIALOG_NOT_SHOWN, DECLINED, TIMED_OUT }
	}
}

/**
 * Installs the quick-build test app through CoGo's own install pathway (plan B1) and
 * waits for a REAL verdict instead of blind uid-polling:
 *
 * - **Skip when current**: if the package is installed and its APK bytes equal the
 *   candidate's, no install runs at all - no PackageInstaller dialog, no Play Protect
 *   prompt. This is what keeps "the reload loop never reinstalls" true across
 *   rebaselines whose setup build came back up-to-date, and across CoGo restarts.
 * - **Same pathway as Run**: [launchInstall] is ApkInstaller.installApk - the exact
 *   call the Run button's flow bottoms out in, with the same session params, the same
 *   InstallationResultReceiver, and the same MIUI intent fallback.
 * - **Real failure reasons**: the receiver's broadcast (surfaced app-side as
 *   [InstallBroadcast]) reports success/failure with the PackageInstaller message,
 *   so a rejected install fails fast instead of timing out a 3-minute uid poll.
 * - **Poll backstop**: the MIUI intent fallback never broadcasts through our receiver,
 *   so a lastUpdateTime change also counts as completion.
 *
 * Failure broadcasts can lack EXTRA_PACKAGE_NAME, so a null-package terminal broadcast
 * is accepted as ours; a concurrent Run-install failure could in theory cross-signal,
 * which errs toward a visible (retryable) failure, never a false success.
 */
class TestAppInstaller(
	private val packages: InstalledPackages,
	/** Starts the install (ApkInstaller.installApk); false = could not even start. */
	private val launchInstall: suspend (File) -> Boolean,
	/** InstallationResultReceiver broadcasts, adapted app-side. */
	private val broadcasts: Flow<InstallBroadcast>,
	private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
	private val pollMillis: Long = DEFAULT_POLL_MILLIS,
	private val digest: (File) -> String? = ::sha256OrNull,
	/**
	 * Whether the OS install-confirm dialog can be shown RIGHT NOW. The dialog-owning
	 * subscriber (InstallationResultHandler via the editor activity) is EventBus
	 * lifecycle-bound (registered onStart, unregistered onStop), so with the host app
	 * backgrounded a PENDING_USER_ACTION status never launches a dialog - the app wires
	 * this to a process-foreground probe. The default (always true) preserves the plain
	 * wait-for-the-user behavior for callers without a probe.
	 */
	private val canShowConfirmDialog: () -> Boolean = { true },
) {
	suspend fun ensureInstalled(
		apk: File,
		packageName: String,
	): InstallOutcome {
		val initialStamp = packages.lastUpdateTime(packageName)
		val existingUid = packages.uid(packageName)
		if (existingUid != null && isSameContent(apk, packageName)) {
			log.info("{} already runs these bytes; skipping reinstall", packageName)
			return InstallOutcome.Installed(existingUid, reinstalled = false)
		}

		return coroutineScope {
			// Subscribe BEFORE committing the install so a fast broadcast cannot slip
			// past us (same pattern as DeployChannel). PENDING_USER_ACTION is decisive
			// too when no confirm dialog can be launched (host backgrounded): waiting
			// out the full timeout there is a silent lie - nobody will ever tap.
			val verdict =
				async(start = CoroutineStart.UNDISPATCHED) {
					broadcasts.first { broadcast ->
						(broadcast.packageName == null || broadcast.packageName == packageName) &&
							(
								broadcast.isTerminal ||
									(
										broadcast.status == InstallBroadcast.Status.PENDING_USER_ACTION &&
											!canShowConfirmDialog()
									)
							)
					}
				}
			val stampChanged = async { awaitStampChange(packageName, initialStamp) }

			val started = runCatching { launchInstall(apk) }.getOrDefault(false)
			if (!started) {
				verdict.cancel()
				stampChanged.cancel()
				return@coroutineScope InstallOutcome.Failed(
					"Could not start the test app installation",
				)
			}

			val outcome =
				withTimeoutOrNull(timeoutMillis) {
					select<InstallOutcome> {
						verdict.onAwait { broadcast ->
							when (broadcast.status) {
								InstallBroadcast.Status.SUCCESS -> {
									resolveUid(packageName)
								}

								InstallBroadcast.Status.PENDING_USER_ACTION -> {
									// Fail-fast park: the OS asked for a confirmation no dialog
									// can deliver right now. Truthful and immediate - no 180s wait.
									InstallOutcome.ConfirmationNotGiven(
										MESSAGE_RETURN_TO_CONFIRM,
										InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
									)
								}

								InstallBroadcast.Status.ABORTED -> {
									InstallOutcome.ConfirmationNotGiven(
										MESSAGE_CONFIRM_DECLINED,
										InstallOutcome.ConfirmationNotGiven.Reason.DECLINED,
									)
								}

								else -> {
									InstallOutcome.Failed(
										broadcast.message ?: "Test app installation failed",
									)
								}
							}
						}
						stampChanged.onAwait { resolveUid(packageName) }
					}
				}
			verdict.cancel()
			stampChanged.cancel()
			outcome ?: confirmationNotGivenAtTimeout()
		}
	}

	/**
	 * No verdict arrived at all within the timeout. With the host backgrounded that means
	 * the PENDING_USER_ACTION status is still deferred by Android (delivered only once the
	 * app is foregrounded), so no dialog was ever launched - same truthful message as the
	 * fail-fast path. Foreground, the dialog was up the whole time: the user walked away.
	 */
	private fun confirmationNotGivenAtTimeout(): InstallOutcome.ConfirmationNotGiven =
		if (!canShowConfirmDialog()) {
			InstallOutcome.ConfirmationNotGiven(
				MESSAGE_RETURN_TO_CONFIRM,
				InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
			)
		} else {
			InstallOutcome.ConfirmationNotGiven(
				"Your app needs a reinstall - the install prompt went unanswered for " +
					"${timeoutMillis / 1000}s. Tap Quick Build to try again.",
				InstallOutcome.ConfirmationNotGiven.Reason.TIMED_OUT,
			)
		}

	private suspend fun awaitStampChange(
		packageName: String,
		initialStamp: Long?,
	) {
		while (true) {
			val stamp = packages.lastUpdateTime(packageName)
			if (stamp != null && stamp != initialStamp) return
			delay(pollMillis)
		}
	}

	private suspend fun resolveUid(packageName: String): InstallOutcome {
		// The uid should exist the moment the install lands; retry briefly for the
		// window between the success broadcast and PackageManager visibility.
		repeat(UID_RETRIES) {
			packages.uid(packageName)?.let { return InstallOutcome.Installed(it, reinstalled = true) }
			delay(pollMillis)
		}
		return InstallOutcome.Failed("Installed $packageName but PackageManager cannot resolve it")
	}

	private fun isSameContent(
		apk: File,
		packageName: String,
	): Boolean {
		val installed = packages.apkFile(packageName) ?: return false
		val candidate = digest(apk) ?: return false
		return candidate == digest(installed)
	}

	companion object {
		private val log = LoggerFactory.getLogger(TestAppInstaller::class.java)

		/** Generous: the user has to tap through PackageInstaller + Play Protect. */
		const val DEFAULT_TIMEOUT_MILLIS = 180_000L
		const val DEFAULT_POLL_MILLIS = 1_000L
		private const val UID_RETRIES = 5

		/** Case (a): no dialog could be shown - returning to CoGo is what re-prompts. */
		const val MESSAGE_RETURN_TO_CONFIRM =
			"Your app needs a reinstall - return to CoGo to confirm."

		/** Case (b): the dialog was shown and the user cancelled it. */
		const val MESSAGE_CONFIRM_DECLINED =
			"Your app needs a reinstall - the install prompt was cancelled. " +
				"Tap Quick Build to try again."

		/** Streaming SHA-256; null on any IO problem (treated as content mismatch). */
		fun sha256OrNull(file: File): String? =
			runCatching {
				val md = MessageDigest.getInstance("SHA-256")
				file.inputStream().use { input ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						md.update(buffer, 0, read)
					}
				}
				md.digest().joinToString("") { "%02x".format(it) }
			}.getOrNull()
	}
}
