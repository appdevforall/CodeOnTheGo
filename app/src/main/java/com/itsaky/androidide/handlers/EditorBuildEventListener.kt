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

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.builder.BuildResult
import com.itsaky.androidide.projects.builder.LaunchResult
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.tooling.events.configuration.ProjectConfigurationStartEvent
import com.itsaky.androidide.tooling.events.task.TaskFinishEvent
import com.itsaky.androidide.tooling.events.task.TaskStartEvent
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import com.itsaky.androidide.plugins.manager.services.IdeBuildServiceImpl as IdeBuildService

/**
 * Handles events received from [GradleBuildService] updates [EditorHandlerActivity].
 * @author Akash Yadav
 */
class EditorBuildEventListener : GradleBuildService.EventListener {
	private var lastStatusLine: String = ""

	private var buildStartTimeMs: Long = System.currentTimeMillis()
	private var lastOutputTimeMs: Long = SystemClock.elapsedRealtime()

	/**
	 * Whether the build now running drew a "Build started" marker.
	 *
	 * The outcome callbacks used to decide for themselves, from the task list they are handed --
	 * a different list from the one prepareBuild sees. If those two ever disagreed the chart got
	 * a start with no finish, or a finish with no start, which is the one thing a pair of markers
	 * exists to avoid. The build that started decides, and its outcome follows.
	 */
	@VisibleForTesting
	internal var annotatedBuild = false

	private var enabled = true
	private var activityReference: WeakReference<EditorHandlerActivity> = WeakReference(null)

	private val pluginBuildService by lazy {
		try {
			IdeBuildService.getInstance()
		} catch (e: Exception) {
			log.warn("Failed to get IdeBuildServiceImpl instance", e)
			null
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(EditorBuildEventListener::class.java)
	}

	private val activityOrNull: EditorHandlerActivity?
		get() = activityReference.get()
	private val activity: EditorHandlerActivity
		get() = checkNotNull(activityReference.get()) { "Activity reference has been destroyed!" }

	fun setActivity(activity: EditorHandlerActivity) {
		this.activityReference = WeakReference(activity)
		this.enabled = true
	}

	fun release() {
		activityReference.clear()
		this.enabled = false
	}

	override fun prepareBuild(buildInfo: BuildInfo) {
		// Before the activity check, not after: this listener outlives any one activity, so a
		// build whose outcome arrived with none attached would otherwise leave the flag set for
		// the next build to inherit and draw a finish for a build that never started.
		annotatedBuild = false

		val act = checkActivity("prepareBuild") ?: return

		// A project sync runs through the same callbacks with no tasks, so annotating every
		// prepareBuild put a "Build started" marker on the chart merely for opening a project --
		// and blamed the sync's own memory spike on a build the user never ran.
		//
		// The outcome callbacks are handed their own task list, which is not this one. Recorded
		// here so the pair is decided once, by the build that started.
		if (buildInfo.tasks.isNotEmpty()) {
			annotatedBuild = true
			act.recordBuildAnnotation(MetricsAnnotationStore.Kind.BUILD_STARTED)
		}

		pluginBuildService?.setBuildInProgress(true)

		val isFirstBuild = GeneralPreferences.isFirstBuild
		act
			.setStatus(
				act.getString(if (isFirstBuild) string.preparing_first else string.preparing),
			)

		if (isFirstBuild) {
			act.showFirstBuildNotice()
		}

		resetBuildTimers()

		act.editorViewModel.isBuildInProgress = true
		act.content.bottomSheet.clearBuildOutput()

		if (buildInfo.tasks.isNotEmpty()) {
			onOutput(
				act.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks,
			)
		}
	}

	/**
	 * Which marker a failed build gets: the user's own cancel, or a real failure (ADFA-5542).
	 *
	 * [failure] is the server's own classification of the throwable Gradle raised. The listener
	 * used to answer this from a flag it set when the cancel was requested, which meant deciding
	 * from the order two main-thread runnables happened to run in -- and a cancel that overtook
	 * [prepareBuild] was cleared by it, so the build the user stopped was reported back to them as
	 * an error.
	 *
	 * Separated from [onBuildFailed] so the decision can be tested: that method needs a live
	 * activity before it reaches this point, and returns early without one.
	 */
	@VisibleForTesting
	internal fun outcomeKind(failure: TaskExecutionResult.Failure?): MetricsAnnotationStore.Kind =
		if (failure == TaskExecutionResult.Failure.BUILD_CANCELLED) {
			MetricsAnnotationStore.Kind.BUILD_CANCELLED
		} else {
			MetricsAnnotationStore.Kind.BUILD_FAILED
		}

	private fun resetBuildTimers() {
		buildStartTimeMs = System.currentTimeMillis()
		lastOutputTimeMs = SystemClock.elapsedRealtime()
	}

	override fun onBuildSuccessful(tasks: List<String?>) {
		val act = checkActivity("onBuildSuccessful") ?: return

		if (annotatedBuild) {
			act.recordBuildAnnotation(MetricsAnnotationStore.Kind.BUILD_FINISHED)
		}
		annotatedBuild = false

		pluginBuildService?.notifyBuildFinished()

		analyzeCurrentFile()

		GeneralPreferences.isFirstBuild = false
		act.editorViewModel.isBuildInProgress = false
		act.flashSuccess(R.string.build_status_sucess)

		val message =
			if (lastStatusLine.contains("BUILD SUCCESSFUL")) lastStatusLine else "Build completed successfully."

		// Create a simulated LaunchResult because the build succeeded.
		// We assume the action that triggered this was a "build and run".
		val launchResult = LaunchResult(isSuccess = true, message = "Launch command issued.")

		// Pass the new launchResult to the BuildResult constructor
		act.notifyBuildResult(
			BuildResult(
				isSuccess = true,
				message = message,
				launchResult = launchResult,
			),
		)

		lastStatusLine = ""
	}

	override fun onProgressEvent(event: ProgressEvent) {
		val act = checkActivity("onProgressEvent") ?: return

		if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
			act.setStatus(event.descriptor.displayName)
		}

		if (isAnnotated(event)) {
			act.recordMetricsAnnotation(event.descriptor.displayName)
		}
	}

	/**
	 * Whether [event] is one the metrics charts annotate (ADFA-5486).
	 *
	 * Task starts and stops, and nothing else. Gradle emits these far faster than a chart can show
	 * them -- dozens a second during configuration -- so the store throttles to one every five
	 * seconds and keeps the first of each quiet period.
	 *
	 * Separated from [onProgressEvent] so the decision can be tested: that method needs a live
	 * activity before it reaches this point, and returns early without one.
	 */
	@VisibleForTesting
	internal fun isAnnotated(event: ProgressEvent): Boolean = event is TaskStartEvent || event is TaskFinishEvent

	override fun onBuildFailed(
		tasks: List<String?>,
		failure: TaskExecutionResult.Failure?,
	) {
		val act = checkActivity("onBuildFailed") ?: return

		if (annotatedBuild) {
			// A build the user stopped arrives through this same callback. Marking it as a failure
			// would report their own deliberate action back to them in the error colour.
			act.recordBuildAnnotation(outcomeKind(failure))
		}
		annotatedBuild = false

		analyzeCurrentFile()
		GeneralPreferences.isFirstBuild = false
		act.editorViewModel.isBuildInProgress = false
		act.flashError(R.string.build_status_failed)

		val message =
			if (lastStatusLine.contains("BUILD FAILED")) lastStatusLine else "Build failed. Check build output for details."

		pluginBuildService?.notifyBuildFailed(message)

		act.notifyBuildResult(BuildResult(isSuccess = false, message = message, launchResult = null))

		lastStatusLine = ""
	}

	override fun onOutput(line: String?) {
		val act = checkActivity("onOutput") ?: return
		line?.let { raw ->
			val formattedOutput = formatOutput(raw)
			act.appendBuildOutput(formattedOutput)
			if (raw.contains("BUILD SUCCESSFUL") || raw.contains("BUILD FAILED")) {
				act.setStatus(raw)
				lastStatusLine = raw
			}
		}
	}

	/**
	 * Prefixes every non-blank line of [raw] with the timing prefix. Blank lines are kept
	 * unprefixed so separator lines stay blank, and the trailing newline is preserved as-is.
	 */
	private fun formatOutput(raw: String): String {
		val nowWallClock = System.currentTimeMillis()
		val nowMonotonic = SystemClock.elapsedRealtime()
		val stepDeltaMs = maxOf(0L, nowMonotonic - lastOutputTimeMs)
		lastOutputTimeMs = nowMonotonic

		val prefix = BuildOutputViewModel.formatLinePrefix(nowWallClock, stepDeltaMs)
		val hadTrailingNewline = raw.endsWith("\n")
		val body = if (hadTrailingNewline) raw.dropLast(1) else raw
		val prefixed =
			body.lineSequence().joinToString("\n") { line ->
				if (line.isEmpty()) line else prefix + line
			}
		return if (hadTrailingNewline) prefixed + "\n" else prefixed
	}

	private fun analyzeCurrentFile() {
		checkActivity("analyzeCurrentFile") ?: return

		val editorView = activityOrNull?.getCurrentEditor()
		if (editorView != null) {
			val editor = editorView.editor
			editor?.analyze()
		}
	}

	private fun checkActivity(action: String): EditorHandlerActivity? {
		if (!enabled) return null

		return activityOrNull.also {
			if (it == null) {
				log.warn("[{}] Activity reference has been destroyed!", action)
				enabled = false
			}
		}
	}
}
