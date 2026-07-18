package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.itsaky.androidide.events.InstallationEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.appdevforall.cotg.quickbuild.service.InstallBroadcast
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File

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
