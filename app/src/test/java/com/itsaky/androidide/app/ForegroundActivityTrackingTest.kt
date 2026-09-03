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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [IDEApplication.foregroundActivityState] is process-global, so anything it still points at
 * outlives the activity it names. ADFA-5252: only *finishing* activities were cleared, which
 * leaked an activity destroyed by an unhandled configuration change or a background reclaim.
 *
 * Every case sets its own starting state through the resume callback, since the application under
 * test is the process singleton.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric defaults to targetSdk (28), where Application.ActivityLifecycleCallbacks has no
// onActivityPreResumed/onActivityPostPaused at all -- resolving the super call throws
// NoSuchMethodError. Both callbacks were added in API 29.
@Config(sdk = [29])
class ForegroundActivityTrackingTest {
	private lateinit var application: IDEApplication
	private var previousTestMode: String? = null

	@Before
	fun setUp() {
		previousTestMode = System.setProperty("androidide.test.mode", "true")
		application = ApplicationProvider.getApplicationContext()
	}

	@After
	fun tearDown() {
		// The property is JVM-global, so leaving it set would leak into any test that runs after
		// this class in the same worker.
		val previous = previousTestMode
		if (previous == null) {
			System.clearProperty("androidide.test.mode")
		} else {
			System.setProperty("androidide.test.mode", previous)
		}
	}

	@Test
	fun givenNoTrackedActivity_whenOneIsResumed_thenItBecomesTheForegroundActivity() {
		val activity = activity(finishing = false)

		application.onActivityPreResumed(activity)

		assertThat(application.foregroundActivity).isSameInstanceAs(activity)
	}

	@Test
	fun givenTheForegroundActivity_whenItIsDestroyed_thenTheReferenceIsCleared() {
		val activity = activity(finishing = false)
		application.onActivityPreResumed(activity)

		application.onActivityDestroyed(activity)

		assertThat(application.foregroundActivity).isNull()
	}

	@Test
	fun givenASuccessorHasResumed_whenThePreviousActivityIsDestroyed_thenTheSuccessorIsKept() {
		val previous = activity(finishing = true)
		val current = activity(finishing = false)
		application.onActivityPreResumed(previous)
		application.onActivityPreResumed(current)

		// A finishes into B: B resumes before A is destroyed, so A's destroy must not win.
		application.onActivityDestroyed(previous)

		assertThat(application.foregroundActivity).isSameInstanceAs(current)
	}

	@Test
	fun givenTheForegroundActivityIsFinishing_whenItPauses_thenTheReferenceIsCleared() {
		val activity = activity(finishing = true)
		application.onActivityPreResumed(activity)

		application.onActivityPostPaused(activity)

		assertThat(application.foregroundActivity).isNull()
	}

	@Test
	fun givenTheForegroundActivityIsNotFinishing_whenItPauses_thenTheReferenceIsKept() {
		val activity = activity(finishing = false)
		application.onActivityPreResumed(activity)

		application.onActivityPostPaused(activity)

		assertThat(application.foregroundActivity).isSameInstanceAs(activity)
	}

	private fun activity(finishing: Boolean): Activity =
		mockk<Activity>(relaxed = true).also {
			every { it.isFinishing } returns finishing
		}
}
