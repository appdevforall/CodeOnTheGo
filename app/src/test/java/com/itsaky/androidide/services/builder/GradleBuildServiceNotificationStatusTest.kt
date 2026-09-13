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

package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.R
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the shade says about a build that did not succeed (ADFA-5542).
 *
 * The notification is one of the four places a cancelled build used to be reported as a failure,
 * and the only one of them with no test: the chart marker and the message both had one, so the
 * notification could have been reverted without a single test noticing.
 */
@RunWith(RobolectricTestRunner::class)
class GradleBuildServiceNotificationStatusTest {
	private val service = GradleBuildService()

	@Test
	fun `a build the user stopped says so in the shade`() {
		assertThat(service.notificationStatusFor(TaskExecutionResult.Failure.BUILD_CANCELLED))
			.isEqualTo(R.string.info_build_cancelled)
	}

	@Test
	fun `every other failure says the build failed`() {
		TaskExecutionResult.Failure.entries
			.filter { it != TaskExecutionResult.Failure.BUILD_CANCELLED }
			.forEach { failure ->
				assertWithMessage(failure.name)
					.that(service.notificationStatusFor(failure))
					.isEqualTo(R.string.build_status_failed)
			}
	}

	@Test
	fun `a failure the server did not classify says the build failed`() {
		// Reporting an unclassified failure as a cancel would put the user's name on something
		// they did not do.
		assertThat(service.notificationStatusFor(null)).isEqualTo(R.string.build_status_failed)
	}
}
