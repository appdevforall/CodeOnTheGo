package com.itsaky.androidide.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.app.PendingIntentCompat
import com.itsaky.androidide.actions.build.DebugAction
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.services.InstallationResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Utility class for installing APKs.
 *
 * @author Akash Yadav
 */
object ApkInstaller {
	private val log = LoggerFactory.getLogger(ApkInstaller::class.java)
	private const val DEBUG_FALLBACK_INSTALLER = false

	/**
	 * Boolean extra riding the install callback intent: on STATUS_SUCCESS, do not run the
	 * launch-after-install behavior for this package.
	 *
	 * Set for Quick Build proxy-app installs (ADFA-4128): the session manager owns that
	 * foregrounding decision, switching to the proxy app on provisioning success. The
	 * generic post-install launch would otherwise fire a second, unasked launch of the
	 * same app - the observed double-launch - or, with the launch-after-install preference
	 * off, pop an "Open application?" dialog for an app the session is about to manage
	 * anyway.
	 * Travels the same road as the debug-mode extra: baseIntent -> PendingIntent ->
	 * InstallationResultReceiver -> InstallationResultHandler.
	 */
	const val EXTRA_SUPPRESS_POST_INSTALL_LAUNCH = "ide.installer.suppressPostInstallLaunch"

	/**
	 * Outcome of starting an APK install session.
	 */
	sealed interface SessionLaunchResult {
		/**
		 * The install was handed off to Android. [sessionId] is the committed
		 * [PackageInstaller] session ID, or `null` when the intent-based fallback was used.
		 */
		data class Started(
			val sessionId: Int?,
		) : SessionLaunchResult

		/**
		 * [PackageInstaller.createSession] rejected the request because this app UID already
		 * has the maximum number of active sessions (`MAX_ACTIVE_SESSIONS = 50`).
		 */
		data object TooManySessions : SessionLaunchResult

		/** Any other failure before or during session creation/commit. */
		data object Failed : SessionLaunchResult
	}

	/**
	 * Starts a session-based package installation workflow.
	 *
	 * @param context The context.
	 * @param apk The APK file to install.
	 * @param suppressPostInstallLaunch tag the install so its success result skips the
	 *   launch-after-install behavior; see [EXTRA_SUPPRESS_POST_INSTALL_LAUNCH].
	 */
	@JvmStatic
	suspend fun installApk(
		context: Context,
		apk: File,
		launchInDebugMode: Boolean = false,
		debugFallbackInstaller: Boolean = DEBUG_FALLBACK_INSTALLER,
		suppressPostInstallLaunch: Boolean = false,
	): Boolean =
		installApkSession(
			context = context,
			apk = apk,
			launchInDebugMode = launchInDebugMode,
			debugFallbackInstaller = debugFallbackInstaller,
			suppressPostInstallLaunch = suppressPostInstallLaunch,
		) is SessionLaunchResult.Started

	/**
	 * Starts a session-based package installation workflow and returns a [SessionLaunchResult]
	 * with the committed `sessionId` (or a distinct [SessionLaunchResult.TooManySessions]
	 * when the UID's active session quota is exhausted).
	 */
	@JvmStatic
	suspend fun installApkSession(
		context: Context,
		apk: File,
		launchInDebugMode: Boolean = false,
		debugFallbackInstaller: Boolean = DEBUG_FALLBACK_INSTALLER,
		suppressPostInstallLaunch: Boolean = false,
		onBeforeAbandon: (Int) -> Unit = {},
	): SessionLaunchResult {
		val isValidApk =
			withContext(Dispatchers.IO) {
				apk.exists() && apk.isFile && apk.extension.equals("apk", ignoreCase = true)
			}
		if (!isValidApk) {
			log.error("File is not an APK: {}", apk)
			return SessionLaunchResult.Failed
		}

		log.info("Installing APK: {}", apk)

		val baseIntent = Intent()
		if (launchInDebugMode) {
			// add debug flag to intent, so the installation handler
			// can launch the app in debug mode after launch
			baseIntent.putExtra(DebugAction.ID, true)
		}
		if (suppressPostInstallLaunch) {
			baseIntent.putExtra(EXTRA_SUPPRESS_POST_INSTALL_LAUNCH, true)
		}

		if (DeviceUtils.isMiui() || debugFallbackInstaller) {
			log.warn(
				"Cannot use session-based installer on this device." +
					" Falling back to intent-based installer.",
			)

			return if (installUsingIntent(context, apk, baseIntent)) {
				SessionLaunchResult.Started(sessionId = null)
			} else {
				SessionLaunchResult.Failed
			}
		}

		return installUsingSession(context, apk, baseIntent, onBeforeAbandon)
	}

	/**
	 * Abandons a single [PackageInstaller] session if it is still active. Safe to call after
	 * the session has already succeeded, failed, or been abandoned.
	 */
	@JvmStatic
	fun abandonSession(
		context: Context,
		sessionId: Int,
	): Boolean = abandonSession(context.packageManager.packageInstaller, sessionId)

	/**
	 * Abandons [sessionId] on [installer], returning `true` if `abandonSession` succeeded and
	 * `false` if the session was already finalized or no longer exists.
	 */
	internal fun abandonSession(
		installer: PackageInstaller,
		sessionId: Int,
	): Boolean =
		runCatching {
			installer.abandonSession(sessionId)
		}.onFailure { error ->
			log.debug("Session {} already finalized or could not be abandoned", sessionId, error)
		}.isSuccess

	/**
	 * Abandons all active [PackageInstaller] sessions owned by this installer package.
	 *
	 * Called at Quick Build startup and before creating a new install session so that
	 * unconfirmed sessions from earlier runs cannot accumulate to Android's 50-session
	 * per-UID cap (`PackageInstallerService.MAX_ACTIVE_SESSIONS`) or leave a stale
	 * confirmation dialog in the system `packageinstaller` task.
	 *
	 * @param onBeforeAbandon invoked with each session ID before `abandonSession` runs so
	 *   callers can ignore the `STATUS_FAILURE_ABORTED` broadcast Android emits on abandonment.
	 * @return the number of sessions abandoned.
	 */
	@JvmStatic
	@JvmOverloads
	fun abandonMySessions(
		context: Context,
		onBeforeAbandon: (Int) -> Unit = {},
	): Int = abandonMySessions(context.packageManager.packageInstaller, onBeforeAbandon)

	/**
	 * Queries [PackageInstaller.getMySessions] on [installer] and abandons each session after
	 * notifying [onBeforeAbandon].
	 */
	internal fun abandonMySessions(
		installer: PackageInstaller,
		onBeforeAbandon: (Int) -> Unit = {},
	): Int {
		val sessions =
			runCatching { installer.mySessions }
				.onFailure { error -> log.warn("Failed to query existing PackageInstaller sessions", error) }
				.getOrNull()
				?: return 0

		var abandoned = 0
		for (info in sessions) {
			runCatching { onBeforeAbandon(info.sessionId) }
			if (abandonSession(installer, info.sessionId)) {
				abandoned++
			}
		}
		if (abandoned > 0) {
			log.info("Abandoned {} stale PackageInstaller session(s)", abandoned)
		}
		return abandoned
	}

	/**
	 * Launches a fallback `ACTION_INSTALL_PACKAGE` activity intent for devices where
	 * session-based installation is disabled, returning `true` if the activity started.
	 */
	@Suppress("DEPRECATION", "RequestInstallPackagesPolicy")
	private fun installUsingIntent(
		context: Context,
		apk: File,
		intent: Intent,
	): Boolean {
		val uri = context.fileProviderUriFor(apk)
		intent.setAction(Intent.ACTION_INSTALL_PACKAGE)
		intent.setDataAndType(uri, "application/vnd.android.package-archive")
		intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

		return try {
			context.startActivity(intent)
			true
		} catch (e: Exception) {
			log.warn("Failed to start installation intent", e)
			false
		}
	}

	/**
	 * Creates, populates, and commits a [PackageInstaller] session for [apk], returning
	 * a [SessionLaunchResult] with the committed session ID or failure classification.
	 */
	@Suppress("RequestInstallPackagesPolicy")
	internal suspend fun installUsingSession(
		context: Context,
		apk: File,
		intent: Intent,
		onBeforeAbandon: (Int) -> Unit = {},
	): SessionLaunchResult {
		val installer = context.packageManager.packageInstaller
		val params = createSessionParams()

		return runCatching {
			withContext(Dispatchers.IO) {
				abandonMySessions(installer, onBeforeAbandon)
				val sessionId = installer.createSession(params)
				var session: PackageInstaller.Session? = null

				try {
					session = installer.openSession(sessionId)
					val callback =
						requireNotNull(getCallbackIntent(context, intent, sessionId)) {
							"PackageInstaller callback intent is null"
						}
					addToSession(session, apk)
					session.commit(callback.intentSender)
					sessionId
				} catch (t: Throwable) {
					runCatching { onBeforeAbandon(sessionId) }
					abandonSession(installer, sessionId)
					throw t
				} finally {
					session?.close()
				}
			}
		}.fold(
			onSuccess = { sessionId -> SessionLaunchResult.Started(sessionId) },
			onFailure = { error ->
				log.error("Package installation failed", error)
				if (isTooManyActiveSessions(error)) {
					SessionLaunchResult.TooManySessions
				} else {
					SessionLaunchResult.Failed
				}
			},
		)
	}

	/**
	 * Returns `true` when [error] or any cause in its chain is the `IllegalStateException`
	 * thrown by Android's `PackageInstallerService` when the per-UID session cap is reached.
	 */
	internal fun isTooManyActiveSessions(error: Throwable): Boolean =
		generateSequence(error) { it.cause }.any { cause ->
			cause is IllegalStateException &&
				cause.message?.contains("Too many active sessions", ignoreCase = true) == true
		}

	private fun createSessionParams(appPackageName: String? = null): PackageInstaller.SessionParams =
		PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			if (appPackageName != null) {
				setAppPackageName(appPackageName)
			}

			setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
			setInstallReason(PackageManager.INSTALL_REASON_USER)
			setOriginatingUid(Process.myUid())

			if (isAtLeastS()) {
				// TODO: When we want to enable automatic, non-interactive updates
				//   change this to PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
				setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
			}

			if (isAtLeastT()) {
				setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
			}

			if (isAtLeastU()) {
				setInstallerPackageName(BuildInfo.PACKAGE_NAME)
				setRequestUpdateOwnership(true)
				setApplicationEnabledSettingPersistent()
			}
		}

	private fun getCallbackIntent(
		context: Context,
		intent: Intent,
		sessionId: Int,
	): PendingIntent? {
		val intent =
			intent.apply {
				action = InstallationResultReceiver.ACTION_INSTALL_STATUS
				setClass(context, InstallationResultReceiver::class.java)
				setPackage(context.packageName)
				addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
			}

		return PendingIntentCompat.getBroadcast(
			context,
			sessionId,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT,
			true,
		)
	}

	private fun addToSession(
		session: PackageInstaller.Session,
		apk: File,
	) {
		val length = apk.length()
		if (length == 0L) {
			throw RuntimeException("File is empty (has length 0)")
		}

		session.openWrite(apk.name, 0, length).use { outStream ->
			apk.inputStream().use { inStream ->
				inStream.transferToStream(outStream)
			}
			session.fsync(outStream)
		}
	}
}
