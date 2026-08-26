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

package com.itsaky.androidide.activities

import android.content.Context
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.PermissionsHelper
import java.io.File

/**
 * Whether the IDE has everything it needs to open a project: a JDK, an SDK, and the permissions to
 * reach them.
 *
 * Asked by [DeepLinkActivity], because a link arriving before setup finishes would otherwise walk
 * straight past onboarding into an editor with no toolchain (ADFA-5067 review).
 *
 * OnboardingActivity deliberately does *not* share this: it asks the stricter question -- a JDK the
 * provider has loaded and validated -- because it can afford to wait for one and must not hand over
 * to MainActivity until the toolchain is really usable. This one has to answer on a cold-start main
 * thread, where nothing has loaded yet, so it asks what is on disk. Two questions, not two copies of
 * one question.
 *
 * Deliberately *not* the whole of what [SplashActivity] enforces -- free storage and the x86 exit are
 * its business, and a caller that finds this false should send the user there rather than re-deciding
 * any of it.
 */
internal fun Context.isIdeSetupComplete(): Boolean =
	isJdkInstalled() &&
		Environment.ANDROID_HOME.exists() &&
		PermissionsHelper.areAllPermissionsGranted(this)

/**
 * Whether a JDK is present *on disk*, which is not the same question as whether one has been loaded
 * into memory yet.
 *
 * The provider's `installedDistributions` is the obvious thing to ask, and it is wrong
 * here: it returns an empty list until `loadDistributions()` has run, and that happens inside the
 * loader coroutine `IDEApplication` launches on `Dispatchers.Default`. On a cold start an Activity's
 * `onCreate` reaches the main thread first, so asking the provider says "no JDK" on a device that
 * has one -- which for [DeepLinkActivity] meant discarding the link and telling the user to finish a
 * setup they had already finished (ADFA-5067 review).
 *
 * So this reads the same directory `JdkUtils.findJavaInstallations` scans, and only that: one stat
 * and one listing, cheap enough for the main thread, and true as soon as the bootstrap has unpacked
 * regardless of what has been loaded. It deliberately does not validate the installations -- that is
 * the provider's job once it runs, and a directory that exists but holds nothing usable is a broken
 * install, not an unfinished setup.
 */
private fun isJdkInstalled(): Boolean {
	val jvmDir = File(Environment.PREFIX, "lib/jvm")
	return jvmDir.isDirectory && (jvmDir.list()?.isNotEmpty() == true)
}
