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

package com.itsaky.androidide.tooling.impl.progress

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskStartEvent
import org.junit.Test

class EventTransformerTest {
	@Test
	fun `a task event lists only its direct dependencies, by task path`() {
		val leaf = task(":app:leaf")
		val middle = task(":app:middle", leaf)
		val event =
			mockk<TaskStartEvent> {
				every { eventTime } returns 1L
				every { displayName } returns "Task :root started"
				every { descriptor } returns task(":app:root", middle)
			}

		val dependencies = EventTransformer.taskStart(event).descriptor.dependencies

		assertThat(dependencies).containsExactly(":app:middle")
	}

	private fun task(
		path: String,
		vararg dependsOn: TaskOperationDescriptor,
	): TaskOperationDescriptor =
		mockk {
			every { name } returns path.substringAfterLast(':')
			every { displayName } returns "Task $path"
			every { taskPath } returns path
			every { originPlugin } returns null
			every { dependencies } returns dependsOn.toSet()
		}
}
