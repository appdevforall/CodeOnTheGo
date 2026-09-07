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
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.tooling.events.internal.DefaultOperationDescriptor
import com.itsaky.androidide.tooling.events.internal.DefaultProgressEvent
import com.itsaky.androidide.tooling.events.task.TaskFailureResult
import com.itsaky.androidide.tooling.events.task.TaskFinishEvent
import com.itsaky.androidide.tooling.events.task.TaskOperationDescriptor
import com.itsaky.androidide.tooling.events.task.TaskStartEvent
import com.itsaky.androidide.tooling.model.PluginIdentifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which Gradle progress events the metrics charts annotate (ADFA-5486).
 *
 * Task starts and stops, and nothing else. Asserted against the predicate rather than through
 * `onProgressEvent`, which needs a live activity before it gets this far.
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
	fun `preparing a build clears a stale cancel, even with no activity attached`() {
		listener.cancelRequested = true

		// No activity is attached here, so prepareBuild returns early -- which is the point. This
		// listener outlives any one activity, and a cancel whose onBuildFailed arrived without one
		// would otherwise leave the flag set for the next build to inherit and be mislabelled.
		listener.prepareBuild(BuildInfo(BuildId.Unknown, listOf(":app:assembleDebug")))

		assertThat(listener.cancelRequested).isFalse()
	}

	@Test
	fun `preparing a build clears a stale pairing`() {
		listener.annotatedBuild = true

		// The flag means "a start marker was drawn for the build now running", so a new build
		// must not inherit it: the outcome callbacks read it to decide whether to draw the other
		// half of the pair, and they are handed a different task list from this one. Cleared
		// before the activity check for the same reason as the cancel flag.
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
}
