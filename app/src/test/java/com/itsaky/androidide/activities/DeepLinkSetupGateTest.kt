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

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
}
