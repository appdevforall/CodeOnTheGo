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

package com.itsaky.androidide.utils

import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BuildCancellation")

/**
 * Asks [service] to stop the running build, and says so when it will not.
 *
 * A Stop the server turns down -- there is no build to cancel, or Gradle refused the request --
 * reached a log line at one call site and nothing at all at the other, so the button appeared to
 * do nothing and the build carried on. ADFA-5542 removed `EventListener.onBuildCancelRequested`,
 * which was the only request-time signal, because it guessed the outcome rather than reporting
 * one; nothing replaced the half of it that mattered. This is that half, in one place, because
 * two call sites written separately are how they came to disagree.
 *
 * Success is deliberately silent: the build stopping is its own feedback, and
 * [com.itsaky.androidide.handlers.EditorBuildEventListener.onBuildFailed] reports the outcome the
 * server actually reached.
 */
fun requestBuildCancellation(service: BuildService) {
	log.info("Sending build cancellation request...")
	service.cancelCurrentBuild().whenComplete { result, error ->
		if (error != null) {
			log.error("Failed to send build cancellation request", error)
			flashError(R.string.msg_build_cancel_failed)
			return@whenComplete
		}

		if (!result.wasEnqueued) {
			// failureReason is nullable on the wire, so this reads it rather than asserting it.
			// A refusal with no reason still has to reach the user.
			log.warn(
				"Unable to enqueue cancellation request reason={} reason.message={}",
				result.failureReason,
				result.failureReason?.message,
			)
			flashError(R.string.msg_build_cancel_failed)
			return@whenComplete
		}

		log.info("Build cancellation request was successfully enqueued...")
	}
}
