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
