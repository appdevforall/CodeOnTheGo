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

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Which flags reach the intent that is actually started (ADFA-5486).
 *
 * The metrics carousel shares a chart image, and while it is floating it does so from a window
 * context with no task of its own -- where startActivity needs FLAG_ACTIVITY_NEW_TASK. The flag
 * was added to the send intent, but `Intent.createChooser` copies only the URI-grant flags
 * outwards and the chooser is what gets started, so the flag never reached the intent that needed
 * it and the share threw.
 *
 * The mirror case -- that a share from an activity is left alone, with no NEW_TASK added -- is not
 * covered here. Robolectric routes Activity.startActivity down to ContextImpl, which applies the
 * "outside of an Activity context" check regardless, so the assertion would fail for reasons that
 * have nothing to do with this code.
 */
@RunWith(RobolectricTestRunner::class)
class IntentUtilsShareTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun file(): File =
		File(context.cacheDir, "chart.png").apply {
			parentFile?.mkdirs()
			writeBytes(byteArrayOf(1, 2, 3))
		}

	private fun lastStarted(): Intent? = shadowOf(context as Application).nextStartedActivity

	@Test
	fun `the started chooser carries the extra flags it was given`() {
		IntentUtils.shareFile(context, file(), "image/png", Intent.FLAG_ACTIVITY_NEW_TASK)

		val started = lastStarted()
		assertThat(started).isNotNull()
		assertThat(started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
	}

	@Test
	fun `the wrapped send intent still grants read access to the image`() {
		IntentUtils.shareFile(context, file(), "image/png", Intent.FLAG_ACTIVITY_NEW_TASK)

		@Suppress("DEPRECATION")
		val inner = lastStarted()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
		assertThat(inner).isNotNull()
		assertThat(inner!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
		assertThat(inner.type).isEqualTo("image/png")
	}
}
