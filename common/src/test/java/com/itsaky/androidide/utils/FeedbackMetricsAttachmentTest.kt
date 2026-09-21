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

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * What the metrics attachment is allowed to cost the feedback send (ADFA-5534).
 *
 * Feedback about a broken IDE has to reach us even when the part that describes the breakage does
 * not work. The two ways that was not true: a throw from the URI step, which sat outside the guard,
 * and a cancellation, which the guard caught and hid.
 */
class FeedbackMetricsAttachmentTest {
	private val uri = mockk<Uri>()

	private val file = File("metrics.csv.gz")

	@Test
	fun `an attachment that writes becomes a uri`() =
		runTest {
			val result = FeedbackManager.metricsAttachmentUri({ file }) { uri }

			assertThat(result).isSameInstanceAs(uri)
		}

	@Test
	fun `nothing to attach is not a failure`() =
		runTest {
			assertThat(FeedbackManager.metricsAttachmentUri(null) { uri }).isNull()
			assertThat(FeedbackManager.metricsAttachmentUri({ null }) { uri }).isNull()
		}

	@Test
	fun `a write that fails costs the attachment, not the send`() =
		runTest {
			val result =
				FeedbackManager.metricsAttachmentUri({ throw IOException("no space") }) { uri }

			assertThat(result).isNull()
		}

	@Test
	fun `a uri that cannot be granted costs the attachment, not the send`() =
		runTest {
			// FileProvider throws IllegalArgumentException for a path outside its configured roots,
			// and the metrics reports live in a directory this feature added. Chained outside the
			// guard, as it was, this threw past it and took the whole feedback send with it.
			val result =
				FeedbackManager.metricsAttachmentUri({ file }) {
					throw IllegalArgumentException("Failed to find configured root")
				}

			assertThat(result).isNull()
		}

	@Test
	fun `a cancelled send is not carried on with`() =
		runTest {
			// runCatching catches Throwable, so this used to be swallowed and the caller ran on to
			// startActivity() on an activity that had already been destroyed.
			try {
				FeedbackManager.metricsAttachmentUri({ throw CancellationException("destroyed") }) { uri }
				throw AssertionError("expected the cancellation to propagate")
			} catch (expected: CancellationException) {
				assertThat(expected).hasMessageThat().isEqualTo("destroyed")
			}
		}
}
