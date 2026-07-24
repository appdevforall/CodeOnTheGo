package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.Intent
import org.appdevforall.cotg.quickbuild.service.TestAppLauncher
import org.slf4j.LoggerFactory

/**
 * Relaunches the quick-build test app after a restart deploy: an explicit intent to
 * the launcher proxy activity when its FQN is known, else the package's default launch
 * intent (the launcher lives on an <activity-alias>). Relies on CoGo being the
 * foreground app while the user edits - Android's background-activity-launch
 * restrictions permit the start then.
 */
class AndroidTestAppLauncher(
	private val context: Context,
) : TestAppLauncher {
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
			// the test app keeps its own task either way.
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(intent)
			true
		} catch (e: Exception) {
			log.error("Could not relaunch test app {}/{}", packageName, activityClass, e)
			false
		}

	private companion object {
		private val log = LoggerFactory.getLogger(AndroidTestAppLauncher::class.java)
	}
}
