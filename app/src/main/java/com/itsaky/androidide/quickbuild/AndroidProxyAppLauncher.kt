package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.Intent
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.slf4j.LoggerFactory

/**
 * Relaunches the quick-build proxy app after a restart deploy, using the launcher's own intent so
 * Android RESUMES the app's task rather than starting a fresh instance of one screen.
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
			// ACTION_MAIN + CATEGORY_LAUNCHER, the intent a home screen sends: it means
			// "bring this app back", which is what resumes the surviving task with its back
			// stack and the top screen's saved state. It also resolves an <activity-alias>
			// launcher the same way the OS would.
			//
			// An explicit component intent does NOT carry that meaning, and preferring one
			// is what left the app dead in 2 of 8 restart deploys: measured on an A56, it
			// was delivered to the just-killed top ActivityRecord (START_DELIVERED_TO_TOP,
			// `notifyAbort ... reason=abort`), the candidate record was discarded, and 6 ms
			// later the framework force-removed the dead one - taking the task with it. No
			// process was ever started. It survives only as the fallback, for an app that
			// declares no launcher at all.
			val intent =
				context.packageManager.getLaunchIntentForPackage(packageName)
					?: activityClass?.let { Intent().apply { setClassName(packageName, it) } }
					?: return false
			// Starting from an application (non-activity) context requires NEW_TASK; against
			// an existing task it resumes that task rather than creating a second one.
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
