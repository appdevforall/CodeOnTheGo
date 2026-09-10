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

package com.itsaky.androidide.utils

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.services.InstallationResultReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The install-result half of the double-launch fix (ADFA-4128): a Quick Build
 * proxy-app install rides the same PackageInstaller callback as the Run button's install,
 * so without [ApkInstaller.EXTRA_SUPPRESS_POST_INSTALL_LAUNCH] its STATUS_SUCCESS result
 * triggered the generic launch-after-install - a first foregrounding the session's own
 * switch to the proxy app then duplicated seconds later.
 *
 * [InstallationResultHandler.onResult]'s return value IS the launch decision (callers
 * launch whatever package it returns), so these tests pin the guard at that seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InstallationResultHandlerSuppressLaunchTest {
	private fun successIntent(suppress: Boolean): Intent =
		Intent(InstallationResultReceiver.ACTION_INSTALL_STATUS).apply {
			putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
			putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, "com.example.quickbuild")
			if (suppress) putExtra(ApkInstaller.EXTRA_SUPPRESS_POST_INSTALL_LAUNCH, true)
		}

	@Test
	fun `an ordinary install success still returns the package to launch`() {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

		val toLaunch = InstallationResultHandler.onResult(activity, successIntent(suppress = false))

		assertThat(toLaunch).isEqualTo("com.example.quickbuild")
	}

	@Test
	fun `a suppress-tagged install success returns nothing to launch`() {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

		val toLaunch = InstallationResultHandler.onResult(activity, successIntent(suppress = true))

		assertThat(toLaunch).isNull()
	}

	@Test
	fun `the suppress tag does not swallow the install-confirm dialog`() {
		// PENDING_USER_ACTION is the system's confirm dialog, which only CoGo can raise;
		// suppressing the LAUNCH must never suppress the CONFIRM, or tagged installs
		// would hang until the installer's timeout.
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val confirm = Intent("com.android.packageinstaller.CONFIRM")
		val pending =
			Intent(InstallationResultReceiver.ACTION_INSTALL_STATUS).apply {
				putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
				putExtra(Intent.EXTRA_INTENT, confirm)
				putExtra(ApkInstaller.EXTRA_SUPPRESS_POST_INSTALL_LAUNCH, true)
			}

		val toLaunch = InstallationResultHandler.onResult(activity, pending)

		assertThat(toLaunch).isNull()
		val started = Shadows.shadowOf(activity).nextStartedActivity
		assertThat(started).isNotNull()
		assertThat(started.action).isEqualTo("com.android.packageinstaller.CONFIRM")
	}
}
