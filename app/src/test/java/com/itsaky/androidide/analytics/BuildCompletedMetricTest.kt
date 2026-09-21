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

package com.itsaky.androidide.analytics

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.analytics.gradle.BuildCompletedMetric
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.result.BuildResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What build telemetry says about a build that did not succeed (ADFA-5542).
 *
 * A build the user stopped and a build that broke both report `success=false`. Without the reason
 * beside it the two are indistinguishable, so every build-success rate counts deliberate cancels
 * as failures -- and the cancel is the one the IDE deliberately makes easy to press.
 */
@RunWith(RobolectricTestRunner::class)
class BuildCompletedMetricTest {
	private fun metric(
		isSuccess: Boolean,
		failure: TaskExecutionResult.Failure?,
	) = BuildCompletedMetric(
		buildId = BuildId.Unknown,
		buildType = "assemble",
		isSuccess = isSuccess,
		buildResult =
			BuildResult(
				buildId = BuildId.Unknown,
				tasks = listOf(":app:assembleDebug"),
				durationMs = 1_234L,
				failure = failure,
			),
	)

	@Test
	fun `a cancelled build carries the reason that says so`() {
		val bundle = metric(isSuccess = false, failure = TaskExecutionResult.Failure.BUILD_CANCELLED).asBundle()

		assertThat(bundle.getString("failure_reason")).isEqualTo("BUILD_CANCELLED")
		// isSuccess keeps its plain meaning: the build did not succeed. The reason is what lets a
		// consumer separate the user's own Stop from a broken build.
		assertThat(bundle.getBoolean("success")).isFalse()
	}

	@Test
	fun `a build that really failed carries its own reason`() {
		val bundle = metric(isSuccess = false, failure = TaskExecutionResult.Failure.BUILD_FAILED).asBundle()

		assertThat(bundle.getString("failure_reason")).isEqualTo("BUILD_FAILED")
	}

	@Test
	fun `a successful build carries no reason at all`() {
		val bundle = metric(isSuccess = true, failure = null).asBundle()

		assertThat(bundle.containsKey("failure_reason")).isFalse()
		assertThat(bundle.getBoolean("success")).isTrue()
	}
}
