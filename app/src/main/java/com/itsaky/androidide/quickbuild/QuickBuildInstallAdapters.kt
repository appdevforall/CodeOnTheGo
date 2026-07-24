package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.itsaky.androidide.events.InstallationEvent
import com.itsaky.androidide.utils.isAtLeastP
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.appdevforall.cotg.quickbuild.service.InstallBroadcast
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.security.MessageDigest

/**
 * PackageManager-backed [InstalledPackages] for the quick-build test-app installer.
 */
class AndroidInstalledPackages(
	private val context: Context,
) : InstalledPackages {
	override fun uid(packageName: String): Int? =
		try {
			context.packageManager.getPackageUid(packageName, 0)
		} catch (e: PackageManager.NameNotFoundException) {
			null
		}

	override fun lastUpdateTime(packageName: String): Long? =
		try {
			context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
		} catch (e: PackageManager.NameNotFoundException) {
			null
		}

	override fun apkFile(packageName: String): File? =
		try {
			context.packageManager
				.getApplicationInfo(packageName, 0)
				.sourceDir
				?.let(::File)
		} catch (e: PackageManager.NameNotFoundException) {
			null
		}

	override fun versionCode(packageName: String): Long? =
		try {
			PackageInfoCompat.getLongVersionCode(
				context.packageManager.getPackageInfo(packageName, 0),
			)
		} catch (e: PackageManager.NameNotFoundException) {
			null
		}

	override fun signingCertSha256(packageName: String): String? =
		try {
			if (!isAtLeastP()) {
				null
			} else {
				context.packageManager
					.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
					.let(::currentSigningCertSha256)
			}
		} catch (e: PackageManager.NameNotFoundException) {
			null
		}
}

/**
 * Signing-cert digests for the same-app-id signature comparison (design contract,
 * section 2): the same SHA-256 computed from an installed package and from a built APK
 * file, so the two sides compare like for like.
 */
object ApkSigningCert {
	/** SHA-256 of [apk]'s signing cert via PackageManager, or null when unreadable. */
	fun sha256(
		context: Context,
		apk: File,
	): String? {
		if (!isAtLeastP()) return null
		return runCatching {
			context.packageManager
				.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
				?.let(::currentSigningCertSha256)
		}.getOrNull()
	}
}

/**
 * The CURRENT cert: the newest rotation-history entry (its last element). CoGo-built
 * debug apps are single-signed with no rotation, so this is simply their one cert.
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
private fun currentSigningCertSha256(info: PackageInfo): String? {
	val signingInfo = info.signingInfo ?: return null
	val signers =
		if (signingInfo.hasMultipleSigners()) {
			signingInfo.apkContentsSigners
		} else {
			signingInfo.signingCertificateHistory
		}
	val cert = signers?.lastOrNull()?.toByteArray() ?: return null
	return MessageDigest
		.getInstance("SHA-256")
		.digest(cert)
		.joinToString("") { "%02x".format(it) }
}

/**
 * Adapts [InstallationEvent.InstallationResultEvent] (posted by CoGo's own
 * InstallationResultReceiver - the SAME receiver the Run button's install uses) into
 * the [InstallBroadcast] flow the quick-build installer awaits. This is what gives
 * quick-build the real PackageInstaller verdict instead of a blind uid poll.
 */
class InstallationEventFlow {
	private val _broadcasts = MutableSharedFlow<InstallBroadcast>(extraBufferCapacity = 16)

	val broadcasts: SharedFlow<InstallBroadcast> = _broadcasts

	/** Idempotent; call before the first install is committed. */
	fun register() {
		val bus = EventBus.getDefault()
		if (!bus.isRegistered(this)) {
			bus.register(this)
		}
	}

	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onInstallationResult(event: InstallationEvent.InstallationResultEvent) {
		val extras = event.intent.extras ?: return
		val code = extras.getInt(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
		val status =
			when {
				code == PackageInstaller.STATUS_SUCCESS -> InstallBroadcast.Status.SUCCESS
				code == PackageInstaller.STATUS_PENDING_USER_ACTION ->
					InstallBroadcast.Status.PENDING_USER_ACTION
				code >= PackageInstaller.STATUS_FAILURE -> InstallBroadcast.Status.FAILURE
				else -> InstallBroadcast.Status.OTHER
			}
		_broadcasts.tryEmit(
			InstallBroadcast(
				packageName = extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME),
				status = status,
				message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE),
			),
		)
	}
}
