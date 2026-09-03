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

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.activities.editor.EditorActivityKt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DeepLinkActivity validates a deep-link URI and then hands the parsed request on to one of these
 * two activities in an Intent extra. Neither may be reachable from outside the app: an exported
 * target can be sent that same extra directly, skipping the validation, to force an arbitrary
 * project open and navigate to an arbitrary file inside it.
 *
 * MainActivity was exported with no intent-filter of its own, so nothing legitimate needed it
 * (SplashActivity holds MAIN/LAUNCHER) and the extra was forgeable (ADFA-5067 review).
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkTargetsNotExportedTest {
	@Test
	fun `the activities DeepLinkActivity hands a parsed request to are not exported`() {
		val context = ApplicationProvider.getApplicationContext<Context>()

		for (target in listOf(MainActivity::class.java, EditorActivityKt::class.java)) {
			val info =
				context.packageManager.getActivityInfo(ComponentName(context, target), 0)
			assertThat(info.exported).isFalse()
		}
	}

	// The activity that does the validating must stay exported -- it is the App Link entry point, and
	// a non-exported one would make every deep link a no-op rather than a security improvement.
	@Test
	fun `DeepLinkActivity itself is exported`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val info =
			context.packageManager.getActivityInfo(
				ComponentName(context, DeepLinkActivity::class.java),
				0,
			)
		assertThat(info.exported).isTrue()
	}
}
