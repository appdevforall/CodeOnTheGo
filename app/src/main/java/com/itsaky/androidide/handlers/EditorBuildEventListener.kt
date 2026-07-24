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

import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.plugins.manager.services.IdeBuildServiceImpl as IdeBuildService
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.builder.BuildResult
import com.itsaky.androidide.projects.builder.LaunchResult
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.events.ProgressEvent
import com.itsaky.androidide.tooling.events.configuration.ProjectConfigurationStartEvent
import com.itsaky.androidide.tooling.events.task.TaskStartEvent
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handles events received from [GradleBuildService] updates [EditorHandlerActivity].
 * @author Akash Yadav
 */
class EditorBuildEventListener : GradleBuildService.EventListener {

private var lastStatusLine: String = ""

private var buildStartTimeMs: Long = 0L
private var lastOutputTimeMs: Long = 0L
private var lineCounter: Int = 1

private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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

private val _activity: EditorHandlerActivity?
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
	checkActivity("prepareBuild") ?: return

	pluginBuildService?.setBuildInProgress(true)

	val isFirstBuild = GeneralPreferences.isFirstBuild
	activity
	.setStatus(
		activity.getString(if (isFirstBuild) string.preparing_first else string.preparing)
	)

	if (isFirstBuild) {
	activity.showFirstBuildNotice()
	}

	resetBuildTimers()

	activity.editorViewModel.isBuildInProgress = true
	activity.content.bottomSheet.clearBuildOutput()

	if (buildInfo.tasks.isNotEmpty()) {
	onOutput(
		activity.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks
	)
	}
}

private fun resetBuildTimers() {
	buildStartTimeMs = System.currentTimeMillis()
	lastOutputTimeMs = buildStartTimeMs
	lineCounter = 1
}

override fun onBuildSuccessful(tasks: List<String?>) {
	val act = checkActivity("onBuildSuccessful") ?: return

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
		launchResult = launchResult
	)
	)

	lastStatusLine = ""
}

override fun onProgressEvent(event: ProgressEvent) {
	checkActivity("onProgressEvent") ?: return

	if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
	activity.setStatus(event.descriptor.displayName)
	}
}

override fun onBuildFailed(tasks: List<String?>) {
	val act = checkActivity("onBuildFailed") ?: return

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

private fun formatOutput(raw: String): String {
	val now = System.currentTimeMillis()
	if (buildStartTimeMs == 0L) {
	buildStartTimeMs = now
	lastOutputTimeMs = now
	}

	val totalDeltaMs = now - buildStartTimeMs
	val stepDeltaMs = now - lastOutputTimeMs
	lastOutputTimeMs = now

	val timeStr = timestampFormat.format(Date(now))
	val totalMins = TimeUnit.MILLISECONDS.toMinutes(totalDeltaMs)
	val totalSecs = TimeUnit.MILLISECONDS.toSeconds(totalDeltaMs) % 60
	val totalMillis = totalDeltaMs % 1000
	val totalDeltaStr = String.format(Locale.US, "+%02d:%02d.%03d", totalMins, totalSecs, totalMillis)
	val stepDeltaStr = String.format(Locale.US, "Δ%dms", stepDeltaMs)

	val lines = raw.split("\n")
	val builder = StringBuilder()
	for (i in lines.indices) {
	val l = lines[i]
	if (i == lines.lastIndex && l.isEmpty() && lines.size > 1) {
		continue
	}
	val lineNo = lineCounter++
	builder.append(
		String.format(
		Locale.US,
		"%5d | [%s] [%s] (%s) %s",
		lineNo,
		timeStr,
		totalDeltaStr,
		stepDeltaStr,
		l
		)
	)
	if (i < lines.size - 1 || raw.endsWith("\n")) {
		builder.append("\n")
	}
	}
	return builder.toString()
}

private fun analyzeCurrentFile() {
	checkActivity("analyzeCurrentFile") ?: return

	val editorView = _activity?.getCurrentEditor()
	if (editorView != null) {
	val editor = editorView.editor
	editor?.analyze()
	}
}

private fun checkActivity(action: String): EditorHandlerActivity? {
	if (!enabled) return null

	return _activity.also {
	if (it == null) {
		log.warn("[{}] Activity reference has been destroyed!", action)
		enabled = false
	}
	}
}
}
