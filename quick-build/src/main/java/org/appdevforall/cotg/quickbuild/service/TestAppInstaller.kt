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
	 * not installed or unreadable. Null reads as "cannot verify" - same-app-id mode
	 * then refuses rather than guessing (design contract, section 2).
	 */
	fun signingCertSha256(packageName: String): String?
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
	enum class Status { SUCCESS, FAILURE, PENDING_USER_ACTION, OTHER }

	val isTerminal: Boolean
		get() = status == Status.SUCCESS || status == Status.FAILURE
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
			// past us (same pattern as DeployChannel).
			val verdict =
				async(start = CoroutineStart.UNDISPATCHED) {
					broadcasts.first { broadcast ->
						broadcast.isTerminal &&
							(broadcast.packageName == null || broadcast.packageName == packageName)
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
								InstallBroadcast.Status.SUCCESS -> resolveUid(packageName)
								else ->
									InstallOutcome.Failed(
										broadcast.message ?: "Test app installation failed",
									)
							}
						}
						stampChanged.onAwait { resolveUid(packageName) }
					}
				}
			verdict.cancel()
			stampChanged.cancel()
			outcome
				?: InstallOutcome.Failed(
					"Test app install was not confirmed within ${timeoutMillis / 1000}s",
				)
		}
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
