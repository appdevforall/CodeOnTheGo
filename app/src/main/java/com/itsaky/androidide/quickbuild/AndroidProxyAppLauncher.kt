package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.Intent
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.slf4j.LoggerFactory

/**
 * Relaunches the quick-build proxy app after a restart deploy: an explicit intent to the launcher
 * proxy activity when its FQN is known, else the package's default launch intent.
 *
 * Requires CoGo to hold the foreground, and Android blocks a background start SILENTLY - so
 * [launch] returning true means "the start was issued", never "the app came up", and only the
 * caller's reconnect wait is evidence. Checking our own process lifecycle first would refuse to try
 * in the cases Android exempts (recent-foreground grace, overlay permission, foreground service).
 */
class AndroidProxyAppLauncher(
	private val context: Context,
) : ProxyAppLauncher {
	override fun launch(
		packageName: String,
		activityClass: String?,
	): Boolean =
		try {
			val intent =
				if (activityClass != null) {
					Intent().apply { setClassName(packageName, activityClass) }
				} else {
					// No proxied activity carries MAIN/LAUNCHER (alias launcher): resolve the
					// launch intent the OS itself would use, which points at the alias.
					context.packageManager.getLaunchIntentForPackage(packageName)
						?: return false
				}
			// Starting from an application (non-activity) context requires NEW_TASK;
			// the proxy app keeps its own task either way.
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(intent)
			true
		} catch (e: Exception) {
			log.error("Could not relaunch proxy app {}/{}", packageName, activityClass, e)
			false
		}

	private companion object {
		private val log = LoggerFactory.getLogger("QB-ProxyLauncher")
	}
}
