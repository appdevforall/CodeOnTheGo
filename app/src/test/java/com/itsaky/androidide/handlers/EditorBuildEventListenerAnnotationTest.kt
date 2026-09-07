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
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
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
 * Two decisions, both asserted against the predicate that makes them rather than through the
 * callback that acts on it -- those need a live activity before they get this far. Which progress
 * events are marked at all (ADFA-5486), and whether a build that failed was really the user
 * stopping it (ADFA-5542).
 */
@RunWith(RobolectricTestRunner::class)
class EditorBuildEventListenerAnnotationTest {
	private val listener = EditorBuildEventListener()

	private fun buildId(id: Long) = BuildId(buildSessionId = "session", buildId = id, runType = BuildRunType.TaskRun)

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
	fun `a cancel that lands before its build is prepared still marks that build cancelled`() {
		// The interleaving ADFA-5542 is about, and the one the main thread can really produce:
		// onBuildCancelRequested is raised on the UI thread and runs inline, prepareBuild is
		// raised from the build's own thread and is posted, so the cancel can overtake it. When
		// the listener held a bare flag, prepareBuild cleared it and the build the user stopped
		// was reported back to them as a failure.
		listener.onBuildCancelRequested(buildId(7))
		listener.prepareBuild(BuildInfo(buildId(7), listOf(":app:assembleDebug")))

		assertThat(listener.outcomeKind(buildId(7)))
			.isEqualTo(MetricsAnnotationStore.Kind.BUILD_CANCELLED)
	}

	@Test
	fun `a cancel is not inherited by the next build`() {
		// The other half of keying to a build rather than to a moment. This listener outlives any
		// one activity, so a cancel whose outcome never arrived stays held -- and must not relabel
		// the next build's genuine failure.
		listener.onBuildCancelRequested(buildId(7))

		assertThat(listener.outcomeKind(buildId(8)))
			.isEqualTo(MetricsAnnotationStore.Kind.BUILD_FAILED)
	}

	@Test
	fun `a build nobody stopped is a failure`() {
		assertThat(listener.outcomeKind(buildId(7)))
			.isEqualTo(MetricsAnnotationStore.Kind.BUILD_FAILED)
	}

	@Test
	fun `a cancel naming no build leaves the one already held alone`() {
		// cancelCurrentBuild passes null when nothing is running. Taking that as "forget the
		// cancel" would lose the attribution for a build still finishing.
		listener.onBuildCancelRequested(buildId(7))
		listener.onBuildCancelRequested(null)

		assertThat(listener.outcomeKind(buildId(7)))
			.isEqualTo(MetricsAnnotationStore.Kind.BUILD_CANCELLED)
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
