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
	 * not installed or unreadable. Null means "cannot verify", and the provisioner then
	 * refuses to clobber the occupant rather than guess.
	 */
	fun signingCertSha256(packageName: String): String?

	/**
	 * The installed package's `android:appComponentFactory` (API 28+), or null when not
	 * installed or none is declared. A Quick Build proxy app carries the runtime factory
	 * here, which is how it is told apart from the user's Standard-Run build under the
	 * same applicationId.
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
	/** ABORTED is STATUS_FAILURE_ABORTED: the user cancelled the confirm dialog. */
	enum class Status { SUCCESS, FAILURE, ABORTED, PENDING_USER_ACTION, OTHER }

	/** True when no further broadcast will follow for this install. */
	val isTerminal: Boolean
		get() = status == Status.SUCCESS || status == Status.FAILURE || status == Status.ABORTED
}

/** What became of a [ProxyAppInstaller.ensureInstalled]. */
sealed interface InstallOutcome {
	/**
	 * The package is installed and current. [reinstalled] is false when the bytes already
	 * matched and no dialog was shown.
	 */
	data class Installed(
		val uid: Int,
		val reinstalled: Boolean,
	) : InstallOutcome

	data class Failed(
		val message: String,
	) : InstallOutcome

	/**
	 * The install started but the OS confirmation was never given.
	 *
	 * Distinct from [Failed] because nothing is broken: the APK is fine and retrying
	 * re-prompts, so callers can offer a retry instead of failing hard. [reason] keeps the
	 * three ways this happens apart, and [message] is the user-facing text for each.
	 * DIALOG_NOT_SHOWN is reported the moment PENDING_USER_ACTION arrives with the host
	 * app backgrounded, rather than after a silent timeout, because the dialog-owning
	 * subscriber is lifecycle-bound and nobody will ever tap.
	 */
	data class ConfirmationNotGiven(
		val message: String,
		val reason: Reason,
	) : InstallOutcome {
		enum class Reason { DIALOG_NOT_SHOWN, DECLINED, TIMED_OUT }
	}
}

/**
 * Installs the Quick Build proxy app through CoGo's own install pathway and waits for a
 * real verdict rather than polling for a uid.
 *
 * Skips the install entirely when the installed APK's bytes already match the candidate,
 * which is what keeps the reload loop free of reinstalls across rebaselines and CoGo
 * restarts. Uses the same call the Run button bottoms out in, so failures arrive as
 * PackageInstaller broadcasts with real messages; a lastUpdateTime change is the backstop
 * for the MIUI intent fallback, which never broadcasts through our receiver.
 *
 * Failure broadcasts can lack EXTRA_PACKAGE_NAME, so a terminal broadcast with a null
 * package is accepted as ours. A concurrent Run install could in theory cross-signal,
 * which errs toward a visible retryable failure, never a false success.
 */
class ProxyAppInstaller(
	private val packages: InstalledPackages,
	/** Starts the install (ApkInstaller.installApk); false when it could not start. */
	private val launchInstall: suspend (File) -> Boolean,
	/** InstallationResultReceiver broadcasts, adapted app-side. */
	private val broadcasts: Flow<InstallBroadcast>,
	private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
	private val pollMillis: Long = DEFAULT_POLL_MILLIS,
	private val digest: (File) -> String? = ::sha256OrNull,
	/**
	 * Whether the OS install-confirm dialog can be shown right now; the app wires this to
	 * a process-foreground probe.
	 *
	 * The dialog-owning subscriber is EventBus lifecycle-bound, so with the host app
	 * backgrounded a PENDING_USER_ACTION status never launches a dialog. The default of
	 * always-true keeps the plain wait-for-the-user behavior for callers without a probe.
	 */
	private val canShowConfirmDialog: () -> Boolean = { true },
) {
	/**
	 * Gets [packageName] installed from [apk], skipping the install when the bytes on
	 * device already match.
	 */
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
			// Subscribe before committing the install so a fast broadcast cannot slip
			// past us. PENDING_USER_ACTION is decisive too when no confirm dialog can be
			// launched, since nobody will ever tap.
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
					"Could not start the proxy app installation",
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
									// The OS asked for a confirmation no dialog can deliver
									// right now, so park immediately instead of waiting out
									// the timeout.
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
										broadcast.message ?: "Proxy app installation failed",
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
	 * Explains a timeout with no verdict at all.
	 *
	 * Backgrounded, Android is still deferring the PENDING_USER_ACTION status, so no
	 * dialog was ever launched. Foregrounded, the dialog was up the whole time and the
	 * user walked away.
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

	/** Polls until the package's lastUpdateTime moves off [initialStamp]. */
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

	/** Reads the uid of a just-installed package, tolerating PackageManager lag. */
	private suspend fun resolveUid(packageName: String): InstallOutcome {
		// The uid should exist the moment the install lands; retry briefly for the
		// window between the success broadcast and PackageManager visibility.
		repeat(UID_RETRIES) {
			packages.uid(packageName)?.let { return InstallOutcome.Installed(it, reinstalled = true) }
			delay(pollMillis)
		}
		return InstallOutcome.Failed("Installed $packageName but PackageManager cannot resolve it")
	}

	/** True when the installed APK's bytes match [apk]; an unreadable file reads as false. */
	private fun isSameContent(
		apk: File,
		packageName: String,
	): Boolean {
		val installed = packages.apkFile(packageName) ?: return false
		val candidate = digest(apk) ?: return false
		return candidate == digest(installed)
	}

	companion object {
		private val log = LoggerFactory.getLogger(ProxyAppInstaller::class.java)

		/** Long, because the user has to tap through PackageInstaller and Play Protect. */
		const val DEFAULT_TIMEOUT_MILLIS = 180_000L
		const val DEFAULT_POLL_MILLIS = 1_000L
		private const val UID_RETRIES = 5

		/** No dialog could be shown; returning to CoGo is what re-prompts. */
		const val MESSAGE_RETURN_TO_CONFIRM =
			"Your app needs a reinstall - return to CoGo to confirm."

		/** The dialog was shown and the user cancelled it. */
		const val MESSAGE_CONFIRM_DECLINED =
			"Your app needs a reinstall - the install prompt was cancelled. " +
				"Tap Quick Build to try again."

		/** Streaming SHA-256 of a file; null on any IO problem, read as a content mismatch. */
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
