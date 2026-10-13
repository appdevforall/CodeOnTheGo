/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.security.CryptogramActivity
import com.itsaky.androidide.security.LauncherAliasManager
import com.itsaky.androidide.security.NeutralizationMarker
import com.itsaky.androidide.security.SecurityOverride
import com.itsaky.androidide.security.WipeManager
import com.itsaky.androidide.security.WipePlan
import com.itsaky.androidide.security.WipeService
import com.itsaky.androidide.security.WipeTeardown
import com.itsaky.androidide.utils.Environment
import com.termux.app.TermuxService
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.MainScope
import java.io.File

/**
 * The `:app` implementation of [WipeService] (ADFA-6510). It resolves the real on-device paths, tears
 * down the IDE's runtime holds, presents the disguise, and runs [WipeManager] off the main thread.
 * Honours the `/sdcard/Download/CodeOnTheGo.nowipe` dry-run override (dev/QA scaffold; remove before GA).
 */
class AppWipeService(
	private val app: Context,
) : WipeService {
	private val mainHandler = Handler(Looper.getMainLooper())

	override fun trigger(activity: Activity) {
		// The /sdcard/Download/CodeOnTheGo.nowipe override is honoured only in debug builds, so a
		// dropped file can never silently no-op a real wipe in a release build (F02).
		val dryRun = BuildConfig.DEBUG && SecurityOverride.isWipeDryRun()

		// Present the disguise immediately (reuse Instant Hide's presentation): the Code Breaker game
		// covers the screen while the destroy pass runs behind it. Shown in dry-run too, so the whole
		// flow is exercised; the exit gesture restores it since dry-run writes no marker.
		SecurityPreferences.isHiddenStateActive = true
		runCatching { LauncherAliasManager.setStealthMode(activity, true) }
		activity.startActivity(
			Intent(activity, CryptogramActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			},
		)
		activity.finish()

		// File IO + build cancel: never on the main thread.
		Thread {
			runCatching { Environment.init(app) }
			val result = WipeManager.wipe(buildPlan(), teardown(dryRun), dryRun) { line -> Log.w(TAG, line) }
			if (dryRun) {
				// Advisory in dry-run only (QA). A real wipe shows nothing user-visible: a toast would
				// pop over the disguise and announce the wipe to anyone watching the seized device (F01).
				mainHandler.post {
					Toast
						.makeText(
							app,
							"Instant Wipe dry run: ${result.enumeratedTargets.size} target(s), nothing deleted",
							Toast.LENGTH_LONG,
						).show()
				}
			} else {
				Log.w(TAG, "Instant Wipe complete")
			}
		}.start()
	}

	private fun buildPlan(): WipePlan {
		val db = app.getDatabasePath(RECENTS_DB)
		val dbFiles = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))
		return WipePlan(
			// The user's source plus the small Recents DB files (sensitive metadata: project
			// names/paths). The DB is closed first in a real wipe (see teardown), so shredding its files
			// carries no open-fd hazard. Cache dirs inside each project are fast-deleted (skipDirs) so
			// the shred stays fast on a multi-GB workspace.
			secureTargets = listOfNotNull(Environment.PROJECTS_DIR) + dbFiles,
			secureTargetSkipDirs = setOf("build", ".gradle", ".cg"),
			// Bulk regenerable caches.
			fastDeleteTargets = listOfNotNull(Environment.ANDROIDIDE_HOME, Environment.GRADLE_USER_HOME),
			markerFiles = listOf(NeutralizationMarker.pipFile(app), NeutralizationMarker.pip2File(app)),
		)
	}

	private fun teardown(dryRun: Boolean): WipeTeardown =
		object : WipeTeardown {
			override fun stopEverything() {
				// Cancel any in-progress Gradle build (async; fire-and-forget). Null when not running.
				runCatching {
					val build = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
					if (build != null && build.isBuildInProgress) build.cancelCurrentBuild()
				}
				// Stop the Termux session (delivered via startService; the service stops itself).
				runCatching {
					app.startService(
						Intent(app, TermuxService::class.java).apply {
							action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE
						},
					)
				}
				// Release the open project's in-memory state (does not touch files).
				runCatching { IProjectManager.getInstance().destroy() }
				// Close the Recents Room DB before its files are shredded, so no connection holds or
				// rewrites them. Real wipe only: afterwards the app is neutralized and the IDE -- the
				// DB's only user -- never runs again, so the now-closed cached instance is never touched.
				// Skipped in dry-run, which must not mutate state (and where the app keeps running).
				if (!dryRun) {
					runCatching {
						val db = RecentProjectRoomDatabase.getDatabase(app, MainScope())
						if (db.isOpen) db.close()
					}
				}
			}

			override fun resetBookkeeping() {
				runCatching { GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT }
			}

			override fun clearPreferences() {
				SecurityPreferences.clearAll()
			}
		}

	companion object {
		private const val TAG = "AppWipeService"
		private const val RECENTS_DB = "RecentProject_database"
	}
}
