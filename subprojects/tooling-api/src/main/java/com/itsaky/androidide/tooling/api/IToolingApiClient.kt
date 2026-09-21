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

package com.itsaky.androidide.tooling.api

import com.itsaky.androidide.tooling.api.messages.ClientGradleBuildConfig
import com.itsaky.androidide.tooling.api.messages.LogMessageParams
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import com.itsaky.androidide.tooling.api.messages.result.BuildResult
import com.itsaky.androidide.tooling.api.messages.result.GradleWrapperCheckResult
import com.itsaky.androidide.tooling.events.ProgressEvent
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import java.util.concurrent.CompletableFuture

/**
 * A client consumes services provided by [IToolingApiServer].
 *
 * @author Akash Yadav
 */
@JsonSegment("client")
interface IToolingApiClient {
	/**
	 * Log the given log message.
	 *
	 * @param params The parameters to log the message.
	 */
	@JsonNotification
	fun logMessage(params: LogMessageParams)

	/**
	 * Log the build output received from Gradle.
	 *
	 * @param line The line of the build output to log.
	 */
	@JsonNotification
	fun logOutput(line: String)

	/**
	 * Called just before a build is started.
	 *
	 * @param buildInfo The build information.
	 * @return The build configuration parameters for this build.
	 */
	@JsonRequest
	fun prepareBuild(buildInfo: BuildInfo): CompletableFuture<ClientGradleBuildConfig>

	/**
	 * Called when a build is successful.
	 *
	 * @param result The result containing the tasks that were run. Maybe an
	 *    empty list if no tasks were specified or if the build was not related
	 *    to any tasks.
	 */
	@JsonNotification
	fun onBuildSuccessful(result: BuildResult)

	/**
	 * Called when a build fails.
	 *
	 * @param result The result containing the tasks that were run. Maybe an
	 *    empty list if no tasks were specified or if the build was not related
	 *    to any tasks.
	 */
	@JsonNotification
	fun onBuildFailed(result: BuildResult)

	/**
	 * Called when the Gradle daemon this server drives has been identified.
	 *
	 * The daemon is a separate process, spawned by the Tooling API a moment after a build starts,
	 * and it is the largest memory consumer of the three -- larger than the IDE and the tooling
	 * server together on a Compose project. Only the server can name it: the daemon is its own
	 * child, and the client has no handle on it (ADFA-5514).
	 *
	 * Reported once per daemon, not once per build. A daemon outlives the build that spawned it and
	 * goes on holding its heap while idle, which is exactly what a user on a small device needs to
	 * see.
	 *
	 * @param pid The process id of the Gradle daemon.
	 */
	@JsonNotification
	fun onGradleDaemonStarted(pid: Int)

	/**
	 * Called when the Gradle daemon reported by [onGradleDaemonStarted] has exited.
	 *
	 * @param pid The process id of the daemon that exited.
	 */
	@JsonNotification
	fun onGradleDaemonExited(pid: Int)

	/**
	 * Called when a [ProgressEvent] is received from Gradle build.
	 *
	 * @param event The [ProgressEvent] model describing the event.
	 */
	@JsonNotification
	fun onProgressEvent(event: ProgressEvent)

	/**
	 * Tells the client to check if the Gradle wrapper files are available.
	 *
	 * @return A [CompletableFuture] which completes when the client is done
	 *    checking the wrapper availability. The future provides a result which
	 *    tells if the wrapper is available or not.
	 */
	@JsonRequest
	fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult>
}
