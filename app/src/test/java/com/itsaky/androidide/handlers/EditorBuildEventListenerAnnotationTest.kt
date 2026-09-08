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

package com.itsaky.androidide.handlers

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.tooling.events.internal.DefaultOperationDescriptor
import com.itsaky.androidide.tooling.events.internal.DefaultProgressEvent
import com.itsaky.androidide.tooling.events.task.TaskFailureResult
import com.itsaky.androidide.tooling.events.task.TaskFinishEvent
import com.itsaky.androidide.tooling.events.task.TaskOperationDescriptor
import com.itsaky.androidide.tooling.events.task.TaskStartEvent
import com.itsaky.androidide.tooling.model.PluginIdentifier
import com.itsaky.androidide.utils.MetricsAnnotationStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the metrics charts annotate, and which build each annotation belongs to.
 *
 * Two decisions, both asserted against the function that makes them rather than through the
 * callback that acts on it -- those need a live activity before they get this far. Which progress
 * events are marked at all (ADFA-5486), and whether a build that failed was really the user
 * stopping it (ADFA-5542). The second is now a reading of what the server said rather than a
 * conclusion drawn on this side, so what is worth pinning is which answers are *not* a cancel.
 */
@RunWith(RobolectricTestRunner::class)
class EditorBuildEventListenerAnnotationTest {
	private val listener = EditorBuildEventListener()

	private fun taskDescriptor() =
		TaskOperationDescriptor(
			dependencies = emptySet(),
			originPlugin = PluginIdentifier("org.gradle"),
			taskPath = ":app:compileKotlin",
			name = "compileKotlin",
			displayName = "Task :app:compileKotlin",
		)

	private fun taskStart(): ProgressEvent =
		TaskStartEvent(
			displayName = "Task :app:compileKotlin",
			eventTime = 0L,
			descriptor = taskDescriptor(),
		)

	private fun taskFinish(): ProgressEvent =
		TaskFinishEvent(
			displayName = "Task :app:compileKotlin",
			eventTime = 0L,
			descriptor = taskDescriptor(),
			result = TaskFailureResult(startTime = 0L, endTime = 1L),
		)

	private fun plainEvent(): ProgressEvent =
		DefaultProgressEvent(
			displayName = "Configure project :app",
			eventTime = 0L,
			descriptor = DefaultOperationDescriptor(name = "configure", displayName = "Configure"),
		)

	@Test
	fun `the server saying a build was cancelled is what marks it cancelled`() {
		// The listener used to answer this from a flag it set when the cancel was asked for, which
		// meant deciding from the order two main-thread runnables happened to run in -- and a
		// cancel that overtook prepareBuild was cleared by it. Nothing here depends on order any
		// more: the server classifies the throwable Gradle raised and this reads the answer.
		assertThat(listener.outcomeKind(TaskExecutionResult.Failure.BUILD_CANCELLED))
			.isEqualTo(MetricsAnnotationStore.Kind.BUILD_CANCELLED)
	}

	@Test
	fun `every other reason the server gives is a failure`() {
		// The substance of the mapping, and the half worth pinning: a connection that dropped or a
		// Gradle version that is not supported is not the user stopping anything, and reporting it
		// as one would tell them their own action broke a build they never touched.
		val notCancels =
			TaskExecutionResult.Failure.entries.filterNot { it == TaskExecutionResult.Failure.BUILD_CANCELLED }
		notCancels.forEach { failure ->
			assertWithMessage(failure.name)
				.that(listener.outcomeKind(failure))
				.isEqualTo(MetricsAnnotationStore.Kind.BUILD_FAILED)
		}
	}

	@Test
	fun `a failure the server did not classify is a failure`() {
		// Null reaches here from any path that reports a build result without a reason. Treating
		// an absent answer as a cancel would put the user's name on something they did not do.
		assertThat(listener.outcomeKind(null)).isEqualTo(MetricsAnnotationStore.Kind.BUILD_FAILED)
	}

	@Test
	fun `a build the user stopped is not reported as an error anywhere`() {
		// The annotation was fixed first and the reports beside it were not, so a user who pressed
		// Stop still got a red "Build failed" bar, a "Build failed" notification and an
		// isSuccess=false result carrying failure text -- their own action read back to them as an
		// error in every place but the chart.
		assertThat(listener.failureMessage(TaskExecutionResult.Failure.BUILD_CANCELLED, CANCELLED_TEXT))
			.isEqualTo(CANCELLED_TEXT)
	}

	@Test
	fun `a build that really failed still says so`() {
		assertThat(listener.failureMessage(TaskExecutionResult.Failure.BUILD_FAILED, CANCELLED_TEXT))
			.isNotEqualTo(CANCELLED_TEXT)
		assertThat(listener.failureMessage(null, CANCELLED_TEXT)).isNotEqualTo(CANCELLED_TEXT)
	}

	@Test
	fun `preparing a build clears a stale pairing, even with no activity attached`() {
		listener.annotatedBuild = true

		// No activity is attached here, so prepareBuild returns early -- which is the point. The
		// flag means "a start marker was drawn for the build now running", and this listener
		// outlives any one activity, so a build whose outcome arrived without one would otherwise
		// leave it set for the next build to inherit and draw a finish for a build that never
		// started.
		listener.prepareBuild(BuildInfo(BuildId.Unknown, listOf(":app:assembleDebug")))

		assertThat(listener.annotatedBuild).isFalse()
	}

	@Test
	fun `a task starting is annotated`() {
		assertThat(listener.isAnnotated(taskStart())).isTrue()
	}

	@Test
	fun `a task finishing is annotated`() {
		assertThat(listener.isAnnotated(taskFinish())).isTrue()
	}

	@Test
	fun `an unrelated progress event is not annotated`() {
		// Gradle emits far more than task events. Annotating everything would bury the markers
		// that matter under configuration noise.
		assertThat(listener.isAnnotated(plainEvent())).isFalse()
	}

	private companion object {
		/** Stands in for the string the activity would resolve, which a test has no activity for. */
		const val CANCELLED_TEXT = "Build was cancelled by the user."
	}
}
