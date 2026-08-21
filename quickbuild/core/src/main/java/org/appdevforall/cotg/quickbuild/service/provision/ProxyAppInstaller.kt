package org.appdevforall.cotg.quickbuild.service.provision

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * What the installer needs to know about installed packages; implemented over
 * PackageManager in the app module, faked in tests.
 */
interface InstalledPackages {
	/**
	 * The package's uid, or null when not installed.
	 *
	 * @param packageName the applicationId to look up
	 * @return the uid, or null when the package is absent; PackageManager can lag an
	 *   install by a moment, so a null right after one is not proof of failure
	 */
	fun uid(packageName: String): Int?

	/**
	 * PackageInfo.lastUpdateTime, or null when not installed.
	 *
	 * @param packageName the applicationId to look up
	 * @return the stamp, meaningful only as something to compare against an earlier read
	 */
	fun lastUpdateTime(packageName: String): Long?

	/**
	 * The installed base APK (sourceDir), or null when not installed.
	 *
	 * @param packageName the applicationId to look up
	 * @return the on-device APK, readable for hashing but never writable
	 */
	fun apkFile(packageName: String): File?

	/**
	 * Lowercase hex SHA-256 of the package's current signing certificate, or null when
	 * not installed or unreadable. Null means "cannot verify", and the provisioner then
	 * refuses to clobber the occupant rather than guess.
	 *
	 * @param packageName the applicationId to look up
	 * @return the lowercase hex digest, or null meaning "cannot verify" - never treat null
	 *   as "no signature" or as a mismatch
	 */
	fun signingCertSha256(packageName: String): String?

	/**
	 * The installed package's `android:appComponentFactory` (API 28+), or null when not
	 * installed or none is declared. A Quick Build proxy app carries the runtime factory
	 * here, which is how it is told apart from the user's Standard-Run build under the
	 * same applicationId.
	 *
	 * @param packageName the applicationId to look up
	 * @return the declared factory's FQN, or null when absent, undeclared, or below API 28
	 */
	fun appComponentFactory(packageName: String): String?
}

/**
 * One PackageInstaller status broadcast, decoupled from android.* so the wait logic is
 * JVM-testable. The app module maps InstallationResultReceiver's intent extras into this.
 *
 * @property packageName null when the broadcast carried no EXTRA_PACKAGE_NAME, which
 *   failure broadcasts often do not; a waiter must then accept it as its own
 * @property status the mapped status; anything unrecognized arrives as [Status.OTHER]
 * @property message the OS failure text when there is one, shown to the user verbatim
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
	 * The package is installed and current.
	 *
	 * @property uid the installed package's uid, which becomes the deploy channel's gate
	 */
	data class Installed(
		val uid: Int,
	) : InstallOutcome

	/**
	 * The install could not be completed, and retrying will not help until something
	 * changes. Distinct from [ConfirmationNotGiven], which is merely unanswered.
	 *
	 * @property message the OS failure text, or a fallback when the broadcast carried none
	 */
	data class Failed(
		val message: QuickBuildMessage,
	) : InstallOutcome

	/**
	 * The install started but the OS confirmation was never given.
	 *
	 * Distinct from [Failed] because nothing is broken: the APK is fine and retrying re-prompts,
	 * so callers can offer a retry instead of failing hard. DIALOG_NOT_SHOWN is reported as soon
	 * as PENDING_USER_ACTION arrives with the host app backgrounded, not after a silent timeout:
	 * the lifecycle-bound dialog subscriber means nobody will ever tap.
	 *
	 * @property message the user-facing text for this particular [reason]; safe to show as-is
	 * @property reason which of the three ways the confirmation went missing, and the only
	 *   thing that tells a deliberate refusal from nobody-was-ever-asked
	 */
	data class ConfirmationNotGiven(
		val message: QuickBuildMessage,
		val reason: Reason,
	) : InstallOutcome {
		/**
		 * Why the confirmation never came. Only DECLINED is a deliberate user answer; the
		 * other two mean nobody was ever asked, or was asked and walked away.
		 */
		enum class Reason { DIALOG_NOT_SHOWN, DECLINED, TIMED_OUT }
	}
}

/**
 * Installs the Quick Build proxy app and waits for a real verdict rather than polling for a uid.
 *
 * Skips the install when the installed APK's bytes already match, which keeps the reload loop
 * free of reinstalls across rebaselines and CoGo restarts. Failures arrive as PackageInstaller
 * broadcasts with real messages, and a lastUpdateTime change backstops the MIUI intent
 * fallback, which never broadcasts through our receiver. A broadcast with no package name is
 * accepted as ours, erring toward a retryable failure rather than a false success.
 */
class ProxyAppInstaller(
	/** Installed-package facts; every read goes through here so tests need no PackageManager. */
	private val packages: InstalledPackages,
	/** Starts the install (ApkInstaller.installApk); false when it could not start. */
	private val launchInstall: suspend (File) -> Boolean,
	/** InstallationResultReceiver broadcasts, adapted app-side. */
	private val broadcasts: Flow<InstallBroadcast>,
	/** Whole-install budget, including the time the user spends tapping through dialogs. */
	private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
	/**
	 * How long one committed install may sit without any verdict before the prompt is
	 * re-issued. Must be well under [timeoutMillis], which still bounds the whole install.
	 */
	private val promptTimeoutMillis: Long = DEFAULT_PROMPT_TIMEOUT_MILLIS,
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
	 *
	 * @param apk the candidate APK; hashed against the installed one before anything runs
	 * @param packageName the applicationId the APK declares, used for every lookup and to
	 *   match inbound broadcasts
	 * @return the verdict; never throws, and an unanswered confirmation comes back as
	 *   [InstallOutcome.ConfirmationNotGiven] rather than a failure, so callers can retry
	 */
	suspend fun ensureInstalled(
		apk: File,
		packageName: String,
	): InstallOutcome {
		val initialStamp = packages.lastUpdateTime(packageName)
		val existingUid = packages.uid(packageName)
		if (existingUid != null && isSameContent(apk, packageName)) {
			log.info("{} already runs these bytes; skipping reinstall", packageName)
			return InstallOutcome.Installed(existingUid)
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
				return@coroutineScope InstallOutcome.Failed(QuickBuildMessage.InstallCouldNotStart)
			}

			val awaitVerdict: suspend () -> InstallOutcome = {
				select<InstallOutcome> {
					verdict.onAwait { broadcast -> classify(broadcast, packageName) }
					stampChanged.onAwait { resolveUid(packageName) }
				}
			}
			val outcome =
				withTimeoutOrNull(timeoutMillis) {
					// A commit whose confirm dialog never reached the user is indistinguishable
					// from one the user is still reading, so the first wait is bounded rather than
					// the whole budget. Re-committing costs a second dialog at worst and is the
					// only way back from a prompt nobody was shown - what a CoGo process death does
					// to the next session's first install, the dialog-owning subscriber being
					// lifecycle-bound. The deferreds are reused, so a late verdict still resolves.
					withTimeoutOrNull(promptTimeoutMillis) { awaitVerdict() }
						?: run {
							if (canShowConfirmDialog()) {
								log.info(
									"no install verdict for {} in {}ms; re-issuing the prompt",
									packageName,
									promptTimeoutMillis,
								)
								runCatching { launchInstall(apk) }
							}
							awaitVerdict()
						}
				}
			verdict.cancel()
			stampChanged.cancel()
			outcome ?: confirmationNotGivenAtTimeout()
		}
	}

	/**
	 * Turns the broadcast that settled an install into its outcome.
	 *
	 * @param broadcast the terminal broadcast, or a PENDING_USER_ACTION no dialog can answer
	 * @param packageName the applicationId being installed, needed to read back the uid
	 * @return the outcome this broadcast means
	 */
	private suspend fun classify(
		broadcast: InstallBroadcast,
		packageName: String,
	): InstallOutcome =
		when (broadcast.status) {
			InstallBroadcast.Status.SUCCESS -> {
				resolveUid(packageName)
			}

			InstallBroadcast.Status.PENDING_USER_ACTION -> {
				// The OS asked for a confirmation no dialog can deliver right now, so park
				// immediately instead of waiting out the timeout.
				InstallOutcome.ConfirmationNotGiven(
					QuickBuildMessage.ReinstallReturnToCoGo,
					InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
				)
			}

			InstallBroadcast.Status.ABORTED -> {
				InstallOutcome.ConfirmationNotGiven(
					QuickBuildMessage.ReinstallDeclined,
					InstallOutcome.ConfirmationNotGiven.Reason.DECLINED,
				)
			}

			else -> {
				InstallOutcome.Failed(
					broadcast.message
						?.let(QuickBuildMessage::Literal)
						?: QuickBuildMessage.InstallFailed,
				)
			}
		}

	/**
	 * Explains a timeout with no verdict at all.
	 *
	 * Backgrounded, Android is still deferring the PENDING_USER_ACTION status, so no
	 * dialog was ever launched. Foregrounded, the dialog was up the whole time and the
	 * user walked away.
	 *
	 * @return the parked outcome, whose reason and text depend on which of those two it
	 *   was; both are retryable
	 */
	private fun confirmationNotGivenAtTimeout(): InstallOutcome.ConfirmationNotGiven =
		if (!canShowConfirmDialog()) {
			InstallOutcome.ConfirmationNotGiven(
				QuickBuildMessage.ReinstallReturnToCoGo,
				InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
			)
		} else {
			InstallOutcome.ConfirmationNotGiven(
				QuickBuildMessage.ReinstallTimedOut(timeoutMillis / 1000),
				InstallOutcome.ConfirmationNotGiven.Reason.TIMED_OUT,
			)
		}

	/**
	 * Polls until the package's lastUpdateTime moves off [initialStamp].
	 *
	 * @param packageName the applicationId to watch
	 * @param initialStamp the stamp read before the install started; null means the package
	 *   was absent, so any stamp at all counts as the change
	 */
	private suspend fun awaitStampChange(
		packageName: String,
		initialStamp: Long?,
	) {
		while (true) {
			val stamp = packages.lastUpdateTime(packageName)
			if (stamp != null && stamp != initialStamp) return
			delay(DEFAULT_POLL_MILLIS)
		}
	}

	/**
	 * Reads the uid of a just-installed package, tolerating PackageManager lag.
	 *
	 * @param packageName the applicationId just installed
	 * @return an installed outcome, or a failure once the bounded retries are spent
	 */
	private suspend fun resolveUid(packageName: String): InstallOutcome {
		// The uid should exist the moment the install lands; retry briefly for the
		// window between the success broadcast and PackageManager visibility.
		repeat(UID_RETRIES) {
			packages.uid(packageName)?.let { return InstallOutcome.Installed(it) }
			delay(DEFAULT_POLL_MILLIS)
		}
		return InstallOutcome.Failed(QuickBuildMessage.InstalledButUnresolvable(packageName))
	}

	/**
	 * True when the installed APK's bytes match [apk]; an unreadable file reads as false.
	 *
	 * @param apk the freshly built proxy app APK, whose digest decides whether the install can
	 *   be skipped entirely
	 * @param packageName the applicationId whose installed APK is compared against it
	 * @return true only on a confirmed match, so an unreadable file errs toward reinstalling
	 */
	private fun isSameContent(
		apk: File,
		packageName: String,
	): Boolean {
		val installed = packages.apkFile(packageName) ?: return false
		val candidate = sha256OrNull(apk) ?: return false
		return candidate == sha256OrNull(installed)
	}

	companion object {
		private val log = LoggerFactory.getLogger("QB-ProxyInstaller")

		/** Long, because the user has to tap through PackageInstaller and Play Protect. */
		const val DEFAULT_TIMEOUT_MILLIS = 180_000L

		/**
		 * Long enough that a user reading the dialog is never re-prompted under it, short
		 * enough that a dialog that never appeared does not burn the whole budget in silence.
		 */
		const val DEFAULT_PROMPT_TIMEOUT_MILLIS = 45_000L
		const val DEFAULT_POLL_MILLIS = 1_000L
		private const val UID_RETRIES = 5

		/**
		 * Streaming SHA-256 of a file; null on any IO problem, read as a content mismatch.
		 *
		 * @param file the file to hash; streamed, so APK-sized inputs cost no extra memory
		 * @return the lowercase hex digest, or null on any IO failure
		 */
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
