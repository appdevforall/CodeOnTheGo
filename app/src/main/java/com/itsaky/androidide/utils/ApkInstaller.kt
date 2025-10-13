package com.itsaky.androidide.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.app.PendingIntentCompat
import androidx.core.content.FileProvider
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
	 * Starts a session-based package installation workflow.
	 *
	 * @param context The context.
	 * @param apk The APK file to install.
	 */
	@JvmStatic
	suspend fun installApk(
		context: Context,
		apk: File,
		launchInDebugMode: Boolean = false,
		debugFallbackInstaller: Boolean = DEBUG_FALLBACK_INSTALLER,
	): Boolean {
		if (!apk.exists() || !apk.isFile || apk.extension != "apk") {
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

		if (DeviceUtils.isMiui() || debugFallbackInstaller) {
			log.warn(
				"Cannot use session-based installer on this device." +
						" Falling back to intent-based installer."
			)

			installUsingIntent(context, apk, baseIntent)
			return true
		}

		return installUsingSession(context, apk, baseIntent)
	}

	@Suppress("DEPRECATION", "RequestInstallPackagesPolicy")
	private fun installUsingIntent(context: Context, apk: File, intent: Intent) {
		val authority = "${context.packageName}.providers.fileprovider"
		val uri = FileProvider.getUriForFile(context, authority, apk)
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
	): Boolean {
		val installer = context.packageManager.packageInstaller
		val params = createSessionParams()
		val sessionId = installer.createSession(params)
		val session = installer.openSession(sessionId)
		val callback = getCallbackIntent(context, intent, sessionId)

		return runCatching {
			withContext(Dispatchers.IO) {
				addToSession(session, apk)
			}

			session.commit(callback!!.intentSender)
			session.close()
		}.onFailure { error ->
			log.error("Package installation failed", error)
			session.abandon()
		}.isSuccess
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

	private fun getCallbackIntent(context: Context, intent: Intent, sessionId: Int): PendingIntent? {
		val intent = intent.apply {
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