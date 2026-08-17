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
	 * Starts a session-based package installation workflow.
	 *
	 * @param context The context.
	 * @param apk The APK file to install.
	 * @param requestDowngrade request a version downgrade (API 29+, honored for
	 *   debuggable packages). Used by the same-app-id Quick Build restore, where the
	 *   real app's versionCode is below the pinned test versionCode (ADFA-4128).
	 * @param suppressPostInstallLaunch tag the install so its success result skips the
	 *   launch-after-install behavior; see [EXTRA_SUPPRESS_POST_INSTALL_LAUNCH].
	 */
	@JvmStatic
	suspend fun installApk(
		context: Context,
		apk: File,
		launchInDebugMode: Boolean = false,
		debugFallbackInstaller: Boolean = DEBUG_FALLBACK_INSTALLER,
		requestDowngrade: Boolean = false,
		suppressPostInstallLaunch: Boolean = false,
	): Boolean {
		val isValidApk =
			withContext(Dispatchers.IO) {
				apk.exists() && apk.isFile && apk.extension.equals("apk", ignoreCase = true)
			}
		if (!isValidApk) {
			log.error("File is not an APK: {}", apk)
			return false
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

			if (requestDowngrade) {
				// The intent installer has no downgrade request; the OS will reject a
				// lower-versionCode install and the user must uninstall manually.
				log.warn("Intent-based installer cannot request a downgrade")
			}
			installUsingIntent(context, apk, baseIntent)
			return true
		}

		return installUsingSession(context, apk, baseIntent, requestDowngrade)
	}

	@Suppress("DEPRECATION", "RequestInstallPackagesPolicy")
	private fun installUsingIntent(
		context: Context,
		apk: File,
		intent: Intent,
	) {
		val uri = context.fileProviderUriFor(apk)
		intent.setAction(Intent.ACTION_INSTALL_PACKAGE)
		intent.setDataAndType(uri, "application/vnd.android.package-archive")
		intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

		try {
			context.startActivity(intent)
		} catch (e: Exception) {
			log.warn("Failed to start installation intent", e)
		}
	}

	@Suppress("RequestInstallPackagesPolicy")
	private suspend fun installUsingSession(
		context: Context,
		apk: File,
		intent: Intent,
		requestDowngrade: Boolean = false,
	): Boolean {
		val installer = context.packageManager.packageInstaller
		val params = createSessionParams(requestDowngrade = requestDowngrade)

		return runCatching {
			withContext(Dispatchers.IO) {
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
				} catch (t: Throwable) {
					runCatching { installer.abandonSession(sessionId) }
					throw t
				} finally {
					session?.close()
				}
			}
		}.onFailure { error ->
			log.error("Package installation failed", error)
		}.isSuccess
	}

	private fun createSessionParams(
		appPackageName: String? = null,
		requestDowngrade: Boolean = false,
	): PackageInstaller.SessionParams =
		PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			if (appPackageName != null) {
				setAppPackageName(appPackageName)
			}

			if (requestDowngrade && isAtLeastQ()) {
				// SessionParams.setRequestDowngrade exists since API 29 but is
				// @SystemApi, so it is invoked reflectively. The system honors the
				// request for debuggable packages - which is all CoGo ever installs.
				// If the call is unavailable (hidden-API policy), the OS rejects the
				// downgrade install with a visible failure; nothing is uninstalled.
				runCatching {
					PackageInstaller.SessionParams::class.java
						.getMethod("setRequestDowngrade", Boolean::class.javaPrimitiveType)
						.invoke(this, true)
				}.onFailure {
					log.warn("setRequestDowngrade unavailable; a downgrade install may be rejected", it)
				}
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
