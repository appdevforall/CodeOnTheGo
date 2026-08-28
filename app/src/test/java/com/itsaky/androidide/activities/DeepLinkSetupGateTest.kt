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
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.PermissionsHelper
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * A deep link must not walk past setup. Both of DeepLinkActivity's targets sit beyond
 * SplashActivity and OnboardingActivity, which are the only things enforcing terms, permissions,
 * the JDK and SDK install, the low-storage check and the x86 exit -- so a link arriving on a fresh
 * install used to land the user in an editor that could not build (ADFA-5067 review).
 *
 * Robolectric's environment has no installed JDK distribution and no ANDROID_HOME, which is exactly
 * the not-set-up state under test.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkSetupGateTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `a link arriving before setup is finished goes to the launcher chain, not the editor`() {
		val intent =
			Intent(Intent.ACTION_VIEW, Uri.parse("https://appdevforall.org/device/open/project/MyApp"))
		val activity = Robolectric.buildActivity(DeepLinkActivity::class.java, intent).create().get()

		val next = shadowOf(activity).nextStartedActivity
		assertThat(next).isNotNull()
		assertThat(next.component?.className).isEqualTo(SplashActivity::class.java.name)
		assertThat(activity.isFinishing).isTrue()
	}

	@Test
	fun `the setup predicate is false when no toolchain is installed`() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		assertThat(context.isIdeSetupComplete()).isFalse()
	}

	/**
	 * The cold-start case, and the reason this predicate reads the filesystem.
	 *
	 * `IJdkDistributionProvider.installedDistributions` is empty until the loader coroutine
	 * `IDEApplication` starts on `Dispatchers.Default` has run, and an Activity's `onCreate` beats it
	 * to the main thread. Asking the provider therefore answered "not set up" on a device that was,
	 * and the link was discarded with a message telling the user to finish a finished setup. Nothing
	 * loads the provider in this test either -- which is precisely the state under test.
	 */
	@Test
	fun `the setup predicate is true from disk alone, with no distributions loaded`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val prefix = tempFolder.newFolder("prefix")
		File(prefix, "lib/jvm/jdk-17").mkdirs()
		val previousPrefix = Environment.PREFIX
		val previousHome = Environment.ANDROID_HOME
		Environment.PREFIX = prefix
		Environment.ANDROID_HOME = tempFolder.newFolder("android-sdk")
		mockkObject(PermissionsHelper)
		every { PermissionsHelper.areAllPermissionsGranted(any()) } returns true
		try {
			assertThat(IJdkDistributionProvider.getInstance().installedDistributions).isEmpty()
			assertThat(context.isIdeSetupComplete()).isTrue()
		} finally {
			unmockkAll()
			Environment.PREFIX = previousPrefix
			Environment.ANDROID_HOME = previousHome
		}
	}

	// The second cold-start race, same shape as the provider one (ADFA-5067 review):
	// Environment.init() runs on the same unawaited loader coroutine, so PREFIX and ANDROID_HOME
	// are still null when a cold-start main thread asks. The gate must fall back to the constant
	// defaults init() itself would assign, not wait -- and not NPE on ANDROID_HOME.
	@Test
	fun `the toolchain paths do not wait for Environment-init`() {
		val previousPrefix = Environment.PREFIX
		val previousHome = Environment.ANDROID_HOME
		Environment.PREFIX = null
		Environment.ANDROID_HOME = null
		try {
			assertThat(jdkInstallPrefix().path).isEqualTo(Environment.DEFAULT_PREFIX)
			assertThat(androidSdkHome().path).isEqualTo(Environment.DEFAULT_HOME + "/android-sdk")
		} finally {
			Environment.PREFIX = previousPrefix
			Environment.ANDROID_HOME = previousHome
		}
	}

	/**
	 * The regression from the ADFA-5067 review: toolchain fully on disk, `Environment.PREFIX` never
	 * assigned -- a cold start that beat the loader coroutine to `Environment.init()`. The old
	 * `File(Environment.PREFIX, "lib/jvm")` read silently became the relative path `lib/jvm` and
	 * answered false, so the link was discarded on a fully set-up device.
	 *
	 * [jdkInstallPrefix] is stubbed to a temp dir because its real null-fallback
	 * (`Environment.DEFAULT_PREFIX`, i.e. `/data/data/...`) is not creatable on a test host; the
	 * fallback's own value is pinned by the test above. What this test pins is that the predicate
	 * consults [jdkInstallPrefix] rather than reading the unassigned field directly.
	 */
	@Test
	fun `the setup predicate is true with the JDK on disk and PREFIX never assigned`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val prefix = tempFolder.newFolder("prefix-cold-start")
		File(prefix, "lib/jvm/jdk-17").mkdirs()
		val previousPrefix = Environment.PREFIX
		val previousHome = Environment.ANDROID_HOME
		Environment.PREFIX = null
		Environment.ANDROID_HOME = tempFolder.newFolder("android-sdk-cold-start")
		mockkStatic("com.itsaky.androidide.activities.SetupStateKt")
		every { jdkInstallPrefix() } returns prefix
		mockkObject(PermissionsHelper)
		every { PermissionsHelper.areAllPermissionsGranted(any()) } returns true
		try {
			assertThat(context.isIdeSetupComplete()).isTrue()
		} finally {
			unmockkAll()
			Environment.PREFIX = previousPrefix
			Environment.ANDROID_HOME = previousHome
		}
	}

	// ...and an empty lib/jvm is not a JDK: a bootstrap that unpacked the directory but no
	// distribution is an unfinished install, which the gate should still refuse.
	@Test
	fun `an empty lib-jvm directory does not count as installed`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val prefix = tempFolder.newFolder("prefix-empty")
		File(prefix, "lib/jvm").mkdirs()
		val previousPrefix = Environment.PREFIX
		val previousHome = Environment.ANDROID_HOME
		Environment.PREFIX = prefix
		Environment.ANDROID_HOME = tempFolder.newFolder("android-sdk-empty")
		mockkObject(PermissionsHelper)
		every { PermissionsHelper.areAllPermissionsGranted(any()) } returns true
		try {
			assertThat(context.isIdeSetupComplete()).isFalse()
		} finally {
			unmockkAll()
			Environment.PREFIX = previousPrefix
			Environment.ANDROID_HOME = previousHome
		}
	}
}
