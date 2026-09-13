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
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FileUtil
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
 *
 * The consequence, which is easy to miss: SplashActivity is reached only when this answers FALSE, so
 * whenever it answers true a deep link goes straight to the editor and Splash's low-storage dialog
 * and x86 `finishAffinity()` never run -- nor OnboardingActivity's primary-user, SD-card-install and
 * device-supported checks. A device that finished onboarding and has since filled its storage is
 * stopped by Splash on a normal launch and waved through by a link, landing in an editor whose Gradle
 * build then fails for want of disk. That is the accepted trade (a link must not re-run onboarding),
 * but it means any check added to Splash or Onboarding is silently NOT applied to links: a new one
 * that must cover them has to be added here too, or hoisted somewhere both paths share.
 */
internal fun Context.isIdeSetupComplete(): Boolean =
	isJdkInstalled() &&
		androidSdkHome().exists() &&
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
	val jvmDir = File(jdkInstallPrefix(), "lib/jvm")
	return jvmDir.isDirectory && (jvmDir.list()?.isNotEmpty() == true)
}

/**
 * [Environment.PREFIX], or the same path it will hold once `Environment.init()` has run.
 *
 * `init()` runs inside the same unawaited loader coroutine that loads the JDK distributions (see
 * [isJdkInstalled]), so on a cold start this main-thread read routinely happens first and finds the
 * field still null -- and the field is not volatile, so even a completed `init()` guarantees nothing
 * about visibility here (ADFA-5067 review). `File(null, "lib/jvm")` doesn't throw; it silently
 * yields a *relative* path that exists nowhere, answering "not set up" on a fully set-up device and
 * discarding the deep link. `init()` derives the field from constants (`new File(DEFAULT_ROOT)`
 * then `"usr"`), and [Environment.DEFAULT_PREFIX] is that same path, so falling back to it reads
 * the same directory without waiting on the loader. The field is still preferred when visible --
 * it is what the rest of the app uses, and tests redirect it to a temp dir.
 */
@VisibleForTesting
internal fun jdkInstallPrefix(): File = Environment.PREFIX ?: File(Environment.DEFAULT_PREFIX)

/**
 * [Environment.ANDROID_HOME], with the same pre-`Environment.init()` fallback as
 * [jdkInstallPrefix]. Needed for the same race: before the fix in [jdkInstallPrefix], the only
 * thing keeping [isIdeSetupComplete] from an NPE on this field was [isJdkInstalled] short-circuiting
 * to false first. The literal mirrors [Environment]'s private `DEFAULT_ANDROID_HOME`
 * (`DEFAULT_HOME + "/android-sdk"`), which `init()` assigns verbatim.
 */
@VisibleForTesting
internal fun androidSdkHome(): File = Environment.ANDROID_HOME ?: File(Environment.DEFAULT_HOME, "android-sdk")

/**
 * [Environment.PROJECTS_DIR], with the same pre-`Environment.init()` fallback as
 * [jdkInstallPrefix] and [androidSdkHome].
 *
 * The third field assigned by that same unawaited loader coroutine, and the one the sweep missed.
 * It is worse than the other two here: they feed [isIdeSetupComplete], which merely answers "not
 * set up" when they read null, but this one is handed straight to `resolveDeepLinkProject`'s
 * non-null `projectsRoot` parameter -- so a null is not a wrong answer, it is
 * `NullPointerException: Parameter specified as non-null is null` thrown inside a
 * `lifecycleScope.launch` with no handler, i.e. the process dying when the user taps a link during
 * a cold start. `init()` derives the field as `new File(FileUtil.getExternalStorageDir(),
 * PROJECTS_FOLDER)`, which is what this reconstructs.
 */
@VisibleForTesting
internal fun projectsRoot(): File = Environment.PROJECTS_DIR ?: File(FileUtil.getExternalStorageDir(), Environment.PROJECTS_FOLDER)
